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
/// del teléfono, alimenta el mando virtual, responde PING para que el teléfono
/// mida el RTT, reenvía rumble y aplica el failsafe anti-botones-atascados.
/// Si el mando virtual falla al arrancar (driver ocupado, bus sin respuesta),
/// reintenta automáticamente cada pocos segundos sin que el usuario tenga que
/// pulsar Detener/Iniciar.
/// </summary>
public sealed class ReceiverEngine : IDisposable
{
    public event Action<string>? Log;
    public event Action? StateChanged;

    private const int PadRetryFirstDelayMs = 3000;
    private const int PadRetryPeriodMs = 5000;

    private readonly RfcommServer _server = new();
    private VirtualController? _pad;
    private StreamSocket? _active;
    private DataWriter? _writer;
    private CancellationTokenSource? _linkCts;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private Timer? _failsafeTimer;
    private Timer? _padRetryTimer;
    private long _lastFrameTimestamp;
    private int _failsafeEvents;
    private long _frames;
    private int _padRetryCount;
    private volatile bool _running;

    public PadMode Mode { get; private set; } = PadMode.Xbox360;
    public bool PhoneConnected { get; private set; }
    public string PhoneName { get; private set; } = "-";
    public InputFrame LastFrame { get; private set; }
    public bool VigemReady => _pad?.Ready ?? false;
    public int VirtualPadIndex => _pad?.UserIndex ?? -1;
    public bool PadFellBack => _pad?.FellBackToDs4 ?? false;

    /// <summary>Mensaje del último fallo del mando virtual (null si está listo).</summary>
    public string? PadError { get; private set; }

    public bool Advertising => _server.IsAdvertising;
    public long TotalFrames => Interlocked.Read(ref _frames);
    public int FailsafeEvents => _failsafeEvents;
    public double FramesPerSecond { get; private set; }

    public async Task StartAsync(PadMode mode)
    {
        StopLinkOnly();
        Mode = mode;
        _running = true;

        _pad?.Dispose();
        _pad = null;
        _padRetryCount = 0;
        PadError = null;
        try
        {
            var pad = new VirtualController(mode);
            pad.Status += m => Log?.Invoke(m);
            pad.RumbleRequested += OnRumbleRequested;
            pad.Start();
            _pad = pad;
            PadError = null;
            Log?.Invoke(pad.FellBackToDs4
                ? "Mando virtual listo: DualShock 4 (Xbox 360 no respondió; fallback automático)."
                : $"Mando virtual listo: {mode} (índice {pad.UserIndex}).");
        }
        catch (VirtualPadException ex)
        {
            PadError = ex.Message;
            Log?.Invoke("Mando virtual no disponible: " + ex.Message);
            SchedulePadRetry();
        }
        catch (Exception ex)
        {
            PadError = $"{ex.GetType().Name}: {ex.Message}";
            Log?.Invoke("Mando virtual no disponible (" + PadError + "). Reintentaré automáticamente.");
            SchedulePadRetry();
        }

        _server.ConnectionAccepted = OnAccepted;
        _server.Log += m => Log?.Invoke(m);
        await _server.StartAsync();

        _failsafeTimer?.Dispose();
        _failsafeTimer = new Timer(_ => CheckFailsafe(), null, 500, 500);
        StateChanged?.Invoke();
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
                            $"({ex.GetType().Name}); sigo probando cada {PadRetryPeriodMs / 1000} s...");
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
        Log?.Invoke("Mando virtual LISTO tras reintento automático. Ya puedes jugar.");
        StateChanged?.Invoke();
    }

    public void StopAll()
    {
        _running = false;
        _padRetryTimer?.Dispose();
        _padRetryTimer = null;
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

    private async Task OnAccepted(StreamSocket socket)
    {
        // v0.1: una conexión a la vez; la nueva reemplaza a la anterior.
        var oldCts = _linkCts;
        var oldSocket = _active;
        _linkCts = new CancellationTokenSource();
        _active = socket;
        oldCts?.Cancel();
        try { oldSocket?.Dispose(); } catch { }

        PhoneConnected = true;
        try { PhoneName = socket.Information.RemoteAddress.DisplayName; }
        catch { PhoneName = "teléfono"; }
        Interlocked.Exchange(ref _frames, 0);
        _lastFrameTimestamp = Stopwatch.GetTimestamp();
        StateChanged?.Invoke();
        Log?.Invoke(PhoneConnected && VigemReady
            ? $"Teléfono conectado ({PhoneName}). Los estados llegan al mando virtual."
            : $"Teléfono conectado ({PhoneName}), pero el mando virtual aún no está listo; " +
              "en cuanto lo esté, los controles llegarán solos.");

        try
        {
            await ReadLoop(socket, _linkCts.Token);
        }
        finally
        {
            PhoneConnected = false;
            ReleaseAllNow();
            StateChanged?.Invoke();
            Log?.Invoke("Teléfono desconectado. Mando liberado.");
        }
    }

    private async Task ReadLoop(StreamSocket socket, CancellationToken ct)
    {
        using var reader = new DataReader(socket.InputStream);
        try { _writer?.Dispose(); } catch { }
        _writer = new DataWriter(socket.OutputStream);

        var buffer = new byte[PhantomProtocol.InputLen];

        while (!ct.IsCancellationRequested)
        {
            if (!await ReadExactAsync(reader, buffer.AsMemory(0, PhantomProtocol.HeaderLen), ct)) break;
            if (buffer[0] != PhantomProtocol.Magic0 || buffer[1] != PhantomProtocol.Magic1)
            {
                continue; // desincronizado: descartar bytes hasta recuperar cabecera
            }
            var type = (FrameType)buffer[3];
            int expected = type switch
            {
                FrameType.Input => PhantomProtocol.InputLen,
                FrameType.Ping => PhantomProtocol.PingLen,
                FrameType.ReleaseAll => PhantomProtocol.ReleaseAllLen,
                _ => 0
            };
            if (expected == 0) continue;
            if (!await ReadExactAsync(
                    reader,
                    buffer.AsMemory(PhantomProtocol.HeaderLen, expected - PhantomProtocol.HeaderLen),
                    ct)) break;

            // ReadOnlyMemory (no ref struct) para poder cruzar el await del PING.
            var frame = buffer.AsMemory(0, expected);
            if (!PhantomProtocol.TryParse(frame.Span, out var parsedType, out var input))
            {
                Log?.Invoke("Frame con CRC inválido: descartado.");
                continue;
            }

            switch (parsedType)
            {
                case FrameType.Input:
                    LastFrame = input;
                    _pad?.Apply(input);
                    Interlocked.Increment(ref _frames);
                    _lastFrameTimestamp = Stopwatch.GetTimestamp();
                    break;

                case FrameType.Ping:
                    await SendAsync(PhantomProtocol.BuildPong(PhantomProtocol.ReadSeq(frame.Span)));
                    break;

                case FrameType.ReleaseAll:
                    ReleaseAllNow();
                    break;
            }
        }
    }

    private static async Task<bool> ReadExactAsync(DataReader reader, Memory<byte> dest, CancellationToken ct)
    {
        int total = 0;
        while (total < dest.Length)
        {
            uint n;
            try
            {
                n = await reader.LoadAsync((uint)(dest.Length - total)).AsTask(ct);
            }
            catch
            {
                return false;
            }
            if (n == 0) return false;
            var tmp = new byte[n];
            reader.ReadBytes(tmp);
            tmp.CopyTo(dest.Span.Slice(total));
            total += (int)n;
        }
        return true;
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

    private void CheckFailsafe()
    {
        if (!PhoneConnected) return;
        double elapsedMs =
            (double)(Stopwatch.GetTimestamp() - _lastFrameTimestamp) / Stopwatch.Frequency * 1000.0;
        if (elapsedMs > 1000.0)
        {
            ReleaseAllNow();
            Interlocked.Increment(ref _failsafeEvents);
            Log?.Invoke("Failsafe: sin datos 1 s, mando liberado.");
            StateChanged?.Invoke();
        }
    }

    private void ReleaseAllNow()
    {
        try { _pad?.ReleaseAll(); } catch { /* el driver pudo desaparecer */ }
    }

    public void Dispose() => StopAll();
}
