using System.Diagnostics;
using PhantomPadReceiver.Bluetooth;
using PhantomPadReceiver.Protocol;
using PhantomPadReceiver.Virtual;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

// Con UseWindowsForms, "Timer" choca entre Forms y Threading; aquí siempre es el de hilo de fondo.
using Timer = System.Threading.Timer;

namespace PhantomPadReceiver.Core;

/// <summary>
/// Corazón del receptor: publica el servicio Bluetooth, decodifica los frames
/// del teléfono (parser incremental FramePipe), alimenta el mando virtual,
/// responde PING para que el teléfono mida el RTT, reenvía rumble y aplica el
/// failsafe anti-botones-atascados (una sola vez por pérdida, con aviso de
/// recuperación).
///
/// Separación de responsabilidades: el servicio Bluetooth arranca y funciona
/// aunque ViGEmBus falle (los frames del teléfono se reciben, decodifican y
/// muestran igualmente), y el mando virtual se reintent a en segundo plano si
/// el bus no lo aceptó. Cada conexión entrante tiene un ID de sesión para que
/// el cierre de una sesión vieja no toque el estado de la nueva.
/// </summary>
public sealed class ReceiverEngine : IDisposable
{
    public event Action<string>? Log;
    public event Action? StateChanged;

    private const int PadRetryFirstDelayMs = 3000;
    private const int PadRetryPeriodMs = 5000;
    private const int ServerRetryFirstDelayMs = 4000;
    private const int ServerRetryPeriodMs = 5000;
    private const int FailsafeTimeoutMs = 1000;
    private const int LocalTestStepMs = 120;

    private readonly RfcommServer _server = new();
    private VirtualController? _pad;
    private StreamSocket? _active;
    private DataWriter? _writer;
    private CancellationTokenSource? _linkCts;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private Timer? _failsafeTimer;
    private Timer? _padRetryTimer;
    private Timer? _serverRetryTimer;
    private long _lastFrameTimestamp;
    private int _failsafeEvents;
    private long _frames;
    private int _padRetryCount;
    private int _serverRetryCount;
    private int _sessionCounter;
    private int _activeSession;
    private bool _linkDegraded;
    private bool _localTestRunning;
    private volatile bool _running;

    public PadMode Mode { get; private set; } = PadMode.Xbox360;
    public bool PhoneConnected { get; private set; }
    public string PhoneName { get; private set; } = "-";
    public InputFrame LastFrame { get; private set; }
    public bool VigemReady => _pad?.Ready ?? false;
    public int VirtualPadIndex => _pad?.UserIndex ?? -1;
    public bool PadIndexPending => _pad is { UserIndexPending: true };
    public bool PadFellBack => _pad?.FellBackToDs4 ?? false;

    /// <summary>Mensaje del último fallo del mando virtual (null si está listo).</summary>
    public string? PadError { get; private set; }

    /// <summary>Mensaje del último fallo del servicio Bluetooth (null si publicado).</summary>
    public string? ServerError => _server.LastError;

    /// <summary>Último error completo (con tipo y HRESULT) para diagnóstico.</summary>
    public string? LastDiagnosticError { get; private set; }

    public bool Advertising => _server.IsAdvertising;
    public long TotalFrames => Interlocked.Read(ref _frames);
    public int FailsafeEvents => _failsafeEvents;
    public int RejectedFrames { get; private set; }
    public double FramesPerSecond { get; private set; }

    public bool LocalTestRunning => _localTestRunning;

    public async Task StartAsync(PadMode mode)
    {
        StopLinkOnly();
        Mode = mode;
        _running = true;

        StartPadOrScheduleRetry();

        _server.ConnectionAccepted = OnAccepted;
        _server.Log += m => Log?.Invoke(m);
        try
        {
            await _server.StartAsync();
            StopServerRetry();
        }
        catch (RfcommServerException ex)
        {
            LastDiagnosticError = ex.Message;
            Log?.Invoke(ex.Message);
            Log?.Invoke("El servicio Bluetooth reintentará publicarse automáticamente cada " +
                        ServerRetryPeriodMs / 1000 + " s; mientras tanto puedes ver el estado del mando virtual.");
            ScheduleServerRetry();
        }

        _failsafeTimer?.Dispose();
        _failsafeTimer = new Timer(_ => CheckFailsafe(), null, 500, 500);
        StateChanged?.Invoke();
    }

    // ------------------------------------------------------------- mando virtual

    private void StartPadOrScheduleRetry()
    {
        _pad?.Dispose();
        _pad = null;
        _padRetryCount = 0;
        PadError = null;
        VirtualController? pad = null;
        try
        {
            pad = new VirtualController(Mode);
            pad.Status += m => Log?.Invoke(m);
            pad.RumbleRequested += OnRumbleRequested;
            pad.Start();
            _pad = pad;
            PadError = null;
            Log?.Invoke(pad.FellBackToDs4
                ? "Mando virtual listo: DualShock 4 (Xbox 360 no pudo enchufarse; fallback automático)."
                : "Mando virtual listo: Xbox 360" + (pad.UserIndexPending
                    ? " (índice del jugador pendiente: el mando ya es operativo)."
                    : $" (índice {pad.UserIndex})."));
            StateChanged?.Invoke();
        }
        catch (VirtualPadException ex)
        {
            pad?.Dispose();
            PadError = ex.Message;
            LastDiagnosticError = ex.Message;
            Log?.Invoke("Mando virtual no disponible: " + ex.Message);
            SchedulePadRetry();
        }
        catch (Exception ex)
        {
            pad?.Dispose();
            PadError = $"{ex.GetType().Name}: {ex.Message}";
            LastDiagnosticError = RfcommServer.Detail(ex);
            Log?.Invoke("Mando virtual no disponible (" + PadError + "). Reintentaré automáticamente.");
            SchedulePadRetry();
        }
    }

    /// <summary>Reintenta crear el mando virtual en segundo plano, sin que el
    /// usuario tenga que tocar nada.</summary>
    private void SchedulePadRetry()
    {
        if (!_running) return;
        _padRetryTimer?.Dispose();
        _padRetryTimer = new Timer(_ => TryRecreatePad(), null, PadRetryFirstDelayMs, PadRetryPeriodMs);
    }

    private void TryRecreatePad()
    {
        if (!_running) return;
        if (_pad is { Ready: true }) { _padRetryTimer?.Dispose(); return; }

        VirtualController? fresh = null;
        try
        {
            fresh = new VirtualController(Mode);
            fresh.Status += m => Log?.Invoke(m);
            fresh.RumbleRequested += OnRumbleRequested;
            fresh.Start();
        }
        catch (Exception ex)
        {
            fresh?.Dispose();
            _padRetryCount++;
            if (_padRetryCount == 1 || _padRetryCount % 4 == 0)
            {
                Log?.Invoke($"Reintento automático #{_padRetryCount} del mando virtual falló " +
                            $"({RfcommServer.Detail(ex)}); sigo probando cada {PadRetryPeriodMs / 1000} s...");
            }
            return;
        }

        _padRetryTimer?.Dispose();
        _padRetryTimer = null;
        var old = _pad;
        _pad = fresh;
        PadError = null;
        _padRetryCount = 0;
        try { old?.Dispose(); } catch { /* dispose tolerante */ }
        try { fresh.ReleaseAll(); } catch { }
        Log?.Invoke("Mando virtual LISTO tras reintento automático. Ya puedes jugar.");
        StateChanged?.Invoke();
    }

    // ------------------------------------------------------------- servidor BT

    private void ScheduleServerRetry()
    {
        if (!_running) return;
        _serverRetryTimer?.Dispose();
        _serverRetryTimer = new Timer(
            _ => _ = TryRestartServerAsync(), null, ServerRetryFirstDelayMs, ServerRetryPeriodMs);
    }

    private async Task TryRestartServerAsync()
    {
        if (!_running || Advertising)
        {
            if (Advertising) StopServerRetry();
            return;
        }
        try
        {
            await _server.StartAsync();
        }
        catch (RfcommServerException ex)
        {
            _serverRetryCount++;
            if (_serverRetryCount == 1 || _serverRetryCount % 4 == 0)
            {
                Log?.Invoke($"Reintento automático #{_serverRetryCount} del servicio Bluetooth falló: {ex.Message}");
            }
            return;
        }
        StopServerRetry();
        Log?.Invoke("[BT] Servicio RFCOMM publicado tras reintento automático.");
        StateChanged?.Invoke();
    }

    private void StopServerRetry()
    {
        _serverRetryTimer?.Dispose();
        _serverRetryTimer = null;
        _serverRetryCount = 0;
    }

    // ------------------------------------------------------------- ciclo de vida

    public void StopAll()
    {
        _running = false;
        _padRetryTimer?.Dispose();
        _padRetryTimer = null;
        _serverRetryTimer?.Dispose();
        _serverRetryTimer = null;
        _failsafeTimer?.Dispose();
        _failsafeTimer = null;
        StopLinkOnly();
        _server.Stop();
        _pad?.Dispose();
        _pad = null;
        StateChanged?.Invoke();
    }

    private void StopLinkOnly()
    {
        _linkCts?.Cancel();
        _linkCts = null;
        try { _writer?.Dispose(); } catch { }
        _writer = null;
        try { _active?.Dispose(); } catch { }
        _active = null;
        PhoneConnected = false;
        PhoneName = "-";
    }

    // ------------------------------------------------------------- sesión activa

    private async Task OnAccepted(StreamSocket socket)
    {
        // Cada conexión entrante es una sesión con ID propio: si una conexión
        // nueva reemplaza a la anterior, el cierre tardío de la vieja ya no
        // puede marcar como desconectada a la nueva.
        int session = Interlocked.Increment(ref _sessionCounter);
        var oldCts = _linkCts;
        var oldSocket = _active;
        _linkCts = new CancellationTokenSource();
        _active = socket;
        _activeSession = session;
        oldCts?.Cancel();
        try { oldSocket?.Dispose(); } catch { }

        PhoneConnected = true;
        try { PhoneName = socket.Information.RemoteAddress.DisplayName; }
        catch { PhoneName = "teléfono"; }
        Interlocked.Exchange(ref _frames, 0);
        RejectedFrames = 0;
        _linkDegraded = false;
        _lastFrameTimestamp = Stopwatch.GetTimestamp();
        StateChanged?.Invoke();
        Log?.Invoke("[BT] Primer paquete esperado. Teléfono conectado (" + PhoneName + "). " +
                    (VigemReady
                        ? "Los estados llegan al mando virtual."
                        : "El mando virtual aún no está listo; en cuanto lo esté, los controles llegarán solos."));

        try
        {
            await ReadLoop(socket, _linkCts.Token, session);
        }
        finally
        {
            if (_activeSession == session)
            {
                PhoneConnected = false;
                ReleaseAllNow();
                StateChanged?.Invoke();
                Log?.Invoke("Teléfono desconectado. Mando liberado. El servidor sigue esperando otra conexión.");
            }
            else
            {
                Log?.Invoke("Sesión anterior cerrada y reemplazada por una conexión nueva.");
            }
        }
    }

    private async Task ReadLoop(StreamSocket socket, CancellationToken ct, int session)
    {
        using var reader = new DataReader(socket.InputStream);
        try { _writer?.Dispose(); } catch { }
        _writer = new DataWriter(socket.OutputStream);

        var pipe = new FramePipe();
        bool firstFrame = true;
        var chunk = new byte[4096];

        while (!ct.IsCancellationRequested)
        {
            uint n;
            try
            {
                // Lee TODO lo que haya disponible: tolera frames fragmentados
                // (varias lecturas) y concatenados (varios frames por lectura).
                n = await reader.LoadAsync(4096).AsTask(ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception)
            {
                break; // socket cerrado por el teléfono
            }
            if (n == 0) break;
            var tmp = new byte[n];
            reader.ReadBytes(tmp);
            pipe.Append(tmp.AsSpan(0, (int)n));

            while (pipe.TryReadFrame(out var frame, out var type))
            {
                if (firstFrame)
                {
                    firstFrame = false;
                    Log?.Invoke("[BT] Primer paquete recibido: enlace bidireccional confirmado.");
                }
                HandleFrame(type, frame);
            }
        }
    }

    private void HandleFrame(FrameType type, byte[] frame)
    {
        switch (type)
        {
            case FrameType.Input:
                if (!PhantomProtocol.TryParse(frame, out _, out var input))
                {
                    RejectedFrames++;
                    return;
                }
                LastFrame = input;
                _pad?.Apply(input);
                Interlocked.Increment(ref _frames);
                _lastFrameTimestamp = Stopwatch.GetTimestamp();
                break;

            case FrameType.Ping:
                {
                    // seq se extrae ANTES de cualquier await (el frame se descarta al salir).
                    ushort seq = PhantomProtocol.ReadSeq(frame);
                    _ = SendAsync(PhantomProtocol.BuildPong(seq));
                    break;
                }

            case FrameType.ReleaseAll:
                ReleaseAllNow();
                break;
        }
    }

    private async Task SendAsync(byte[] data)
    {
        var writer = _writer;
        if (writer == null) return;
        await _writeLock.WaitAsync();
        try
        {
            writer.WriteBytes(data);
            await writer.StoreAsync();
        }
        catch
        {
            // la conexión pudo morir entre medias; el bucle principal lo detecta
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private void OnRumbleRequested(byte small, byte large)
    {
        if (!PhoneConnected) return;
        _ = SendAsync(PhantomProtocol.BuildRumble(small, large));
    }

    /// <summary>
    /// Failsafe anti-botones-atascados: libera UNA vez al perder datos y avisa
    /// de la recuperación; no satura el registro repitiendo el aviso.
    /// </summary>
    private void CheckFailsafe()
    {
        if (!PhoneConnected)
        {
            _linkDegraded = false;
            return;
        }
        double elapsedMs =
            (double)(Stopwatch.GetTimestamp() - _lastFrameTimestamp) / Stopwatch.Frequency * 1000.0;
        if (elapsedMs > FailsafeTimeoutMs)
        {
            if (!_linkDegraded)
            {
                _linkDegraded = true;
                ReleaseAllNow();
                Interlocked.Increment(ref _failsafeEvents);
                Log?.Invoke("Failsafe: sin datos del teléfono durante 1 s; botones liberados (enlace degradado).");
                StateChanged?.Invoke();
            }
        }
        else if (_linkDegraded && elapsedMs < FailsafeTimeoutMs / 2)
        {
            _linkDegraded = false;
            Log?.Invoke("Enlace recuperado: el teléfono vuelve a enviar datos.");
        }
    }

    private void ReleaseAllNow()
    {
        try { _pad?.ReleaseAll(); } catch { /* el driver pudo desaparecer */ }
    }

    // ------------------------------------------------------------- prueba local

    /// <summary>
    /// Test A (sin teléfono): barrido de ejes, gatillos y todos los botones a
    /// través del mando virtual para verificarlo en joy.cpl sin el celular.
    /// </summary>
    public async Task RunLocalTestAsync()
    {
        if (_pad is not { Ready: true })
        {
            Log?.Invoke("PRUEBA LOCAL: no hay mando virtual listo. Pulsa Iniciar primero.");
            return;
        }
        if (_localTestRunning) return;
        _localTestRunning = true;
        Log?.Invoke("PRUEBA LOCAL: barrido de ~9 s por ejes, gatillos y botones. Abre joy.cpl para verlo.");
        try
        {
            // 1) Barrido de ejes (2 s)
            for (int t = 0; t < 20; t++)
            {
                double a = t / 20.0 * 2 * Math.PI;
                var f = new InputFrame(
                    (ushort)t, 0,
                    (sbyte)(Math.Sin(a) * 127), (sbyte)(Math.Cos(a) * 127),
                    (sbyte)(-Math.Sin(a) * 127), (sbyte)(-Math.Cos(a) * 127),
                    (byte)(t * 255 / 20), (byte)(255 - t * 255 / 20),
                    0, 0, 0, 0, 0, 0, 0);
                _pad.Apply(f);
                await Task.Delay(100).ConfigureAwait(false);
            }
            // 2) Botones de a uno (7 s)
            PadButtons[] order =
            {
                PadButtons.Cross, PadButtons.Circle, PadButtons.Square, PadButtons.Triangle,
                PadButtons.DpadUp, PadButtons.DpadDown, PadButtons.DpadLeft, PadButtons.DpadRight,
                PadButtons.L1, PadButtons.R1, PadButtons.L3, PadButtons.R3,
                PadButtons.Share, PadButtons.Options, PadButtons.Ps, PadButtons.Touchpad
            };
            foreach (var b in order)
            {
                _pad.Apply(new InputFrame((ushort)b.GetHashCode(), b,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
                await Task.Delay(LocalTestStepMs).ConfigureAwait(false);
            }
            // 3) Reposo
            ReleaseAllNow();
            Log?.Invoke("PRUEBA LOCAL completada: el mando virtual quedó en reposo. " +
                        "Si joy.cpl no mostró el barrido, revisa ViGEmBus (docs/INSTALL_WINDOWS.md).");
        }
        catch (Exception ex)
        {
            Log?.Invoke("PRUEBA LOCAL interrumpida: " + RfcommServer.Detail(ex));
        }
        finally
        {
            ReleaseAllNow();
            _localTestRunning = false;
        }
    }

    public void Dispose() => StopAll();
}
