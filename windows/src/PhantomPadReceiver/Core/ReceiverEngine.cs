using System.Diagnostics;
using PhantomPadReceiver.Bluetooth;
using PhantomPadReceiver.Protocol;
using PhantomPadReceiver.Virtual;
using Windows.Networking.Sockets;

namespace PhantomPadReceiver.Core;

public readonly record struct ReceiverDiagnostics(
    int SessionId, string Peer, long Bytes, long Inputs, long Frames, long MissingInputs,
    int CrcErrors, int UnknownFrames, int DroppedBytes, int PendingBytes,
    int ReadBytes, int ReadFrames, long LateInputs, double ReadGapMs, double InputGapMs, double MaximumInputGapMs,
    double ProcessingMs, double? InputAgeMs, double? RttMs, int Failsafes, bool LinkDegraded,
    ControllerState PadState, string PadIdentity, int RawIndex, string XInput, string? Error);

public sealed class ReceiverEngine
{
    public event Action<string>? Log;
    public event Action? StateChanged;

    private readonly object _gate = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly RfcommServer _server = new();
    private CancellationTokenSource? _lifetime;
    private Task? _listenerTask;
    private Task? _maintenanceTask;
    private readonly HashSet<Task> _connectionTasks = new();
    private VirtualController? _pad;
    private ReceiverSession? _session;
    private CancellationTokenSource? _localTest;
    private string _peer = "—";
    private long _nextPadAttempt;
    private int _sessionCounter;
    private int _failsafes;
    private InputFrame _lastFrame;
    private ReceiverDiagnostics _lastSession;

    public ReceiverEngine()
    {
        _server.ConnectionAccepted = OnAccepted;
        _server.Log += message => Log?.Invoke(message);
    }

    public PadMode Mode { get; private set; } = PadMode.Xbox360;
    public bool Running { get { lock (_gate) return _lifetime != null; } }
    public bool PhoneConnected { get { lock (_gate) return _session != null; } }
    public string PhoneName { get { lock (_gate) return _peer; } }
    public InputFrame LastFrame { get { lock (_gate) return _lastFrame; } }
    public bool CanTest { get { lock (_gate) return _pad is { HasTarget: true } && _session == null && _localTest == null; } }
    public string? PadError { get; private set; }
    public string? ServerError => _server.LastError;
    public bool Advertising => _server.IsAdvertising;

    public ReceiverDiagnostics Diagnostics
    {
        get
        {
            lock (_gate)
            {
                var session = _session;
                var telemetry = session == null ? _lastSession with { SessionId = 0 } :
                    new ReceiverDiagnostics(session.Id, _peer, session.Bytes, session.Inputs, session.ValidFrames,
                        session.MissingInputs, session.Pipe.RejectedFrames, session.Pipe.UnknownFrames,
                        session.Pipe.DroppedBytes, session.Pipe.PendingBytes, session.LastReadBytes,
                        session.LastReadFrames, session.LateInputs, session.ReadGapMs, session.InputGapMs, session.MaximumInputGapMs,
                        session.ProcessingMs, session.Watchdog.LastInputAt == 0 ? null :
                        Stopwatch.GetElapsedTime(session.Watchdog.LastInputAt).TotalMilliseconds,
                        session.RttMs, _failsafes, session.Watchdog.Degraded, default, "", -1, "", null);
                return telemetry with
                {
                    PadState = _pad?.State ?? (PadError != null ? ControllerState.Failed : ControllerState.NotInitialized),
                    PadIdentity = _pad?.Identity ?? "—",
                    RawIndex = _pad?.RawUserIndex ?? -1,
                    XInput = _pad?.XInputStatus ?? "—",
                    Error = PadError ?? ServerError
                };
            }
        }
    }

    public async Task StartAsync(PadMode mode)
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (_gate)
            {
                if (_lifetime != null) return;
                Mode = mode;
                _failsafes = 0;
                _lastSession = default;
                _nextPadAttempt = 0;
                var lifetime = new CancellationTokenSource();
                _lifetime = lifetime;
                _listenerTask = Task.Run(() => ListenAsync(lifetime.Token));
                _maintenanceTask = Task.Run(() => MaintainAsync(lifetime.Token));
            }
        }
        finally { _lifecycle.Release(); }
        StateChanged?.Invoke();
    }

    public async Task StopAsync()
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            CancellationTokenSource? lifetime;
            Task[] connections;
            lock (_gate)
            {
                lifetime = _lifetime;
                if (lifetime == null) return;
                _lifetime = null;
                _localTest?.Cancel();
                _localTest = null;
                connections = _connectionTasks.ToArray();
                _connectionTasks.Clear();
                EndSession();
                ReleaseInput();
                _pad?.Dispose();
                _pad = null;
                PadError = null;
                _lastSession = default;
            }
            await lifetime.CancelAsync().ConfigureAwait(false);
            try
            {
                await Task.WhenAll(connections.Append(_listenerTask!).Append(_maintenanceTask!)).ConfigureAwait(false);
            }
            finally { lifetime.Dispose(); }
        }
        finally { _lifecycle.Release(); }
        StateChanged?.Invoke();
    }

    private async Task ListenAsync(CancellationToken ct)
    {
        try
        {
            for (int attempt = 0; !ct.IsCancellationRequested; attempt++)
            {
                try
                {
                    await _server.StartAsync(ct).ConfigureAwait(false);
                    StateChanged?.Invoke();
                    await _server.WaitForRadioLossAsync(ct).ConfigureAwait(false);
                    _server.Stop();
                    lock (_gate)
                    {
                        if (_lifetime?.Token != ct) return;
                        EndSession();
                        ReleaseInput();
                    }
                    attempt = -1;
                    Log?.Invoke("[BT] Radio apagada. Se volverá a publicar el servicio al recuperarla.");
                    StateChanged?.Invoke();
                }
                catch (RfcommServerException ex)
                {
                    if (attempt % 4 == 0) Log?.Invoke(ex.Message + " Reintento en 5 s.");
                    StateChanged?.Invoke();
                    await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        finally { _server.Stop(); }
    }

    private async Task MaintainAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(100));
        long lastProbe = 0;
        long lastPing = 0;
        try
        {
            do
            {
                lock (_gate)
                {
                    if (_lifetime?.Token != ct) return;
                    long now = Stopwatch.GetTimestamp();
                    if (_session?.Watchdog.Check(now) == true)
                    {
                        ReleaseInput();
                        _failsafes++;
                        Log?.Invoke("Failsafe: 1 s sin INPUT válido. Mando liberado.");
                        StateChanged?.Invoke();
                    }
                    if (_pad == null && now >= _nextPadAttempt) StartPad(now);
                    if (Stopwatch.GetElapsedTime(lastProbe, now) >= TimeSpan.FromMilliseconds(500))
                    {
                        lastProbe = now;
                        UsePad(pad => pad.ProbeXInput(), retryOnFailure: false);
                    }
                    if (Stopwatch.GetElapsedTime(lastPing, now) >= TimeSpan.FromSeconds(1))
                    {
                        lastPing = now;
                        _session?.Ping(now);
                    }
                }
            } while (await timer.WaitForNextTickAsync(ct).ConfigureAwait(false));
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
    }

    private void StartPad(long now)
    {
        var pad = new VirtualController(Mode);
        pad.Status += message => Log?.Invoke(message);
        pad.RumbleRequested += (small, large) =>
            Volatile.Read(ref _session)?.SendRumble(small, large);
        try
        {
            pad.Start();
            _pad = pad;
            PadError = null;
            var current = _session is { Watchdog.Degraded: false } session &&
                session.Watchdog.LastInputAt != 0 ? _lastFrame : default;
            UsePad(target => target.Apply(current));
            StateChanged?.Invoke();
        }
        catch (VirtualPadException ex)
        {
            pad.Dispose();
            _nextPadAttempt = now + Stopwatch.Frequency * 5;
            ReportPadError(ex);
        }
    }

    private void UsePad(Action<VirtualController> action, bool retryOnFailure = true)
    {
        if (_pad == null) return;
        try { action(_pad); }
        catch (Exception ex)
        {
            if (retryOnFailure)
            {
                _pad.Dispose();
                _pad = null;
                _nextPadAttempt = Stopwatch.GetTimestamp() + Stopwatch.Frequency * 5;
                _localTest?.Cancel();
            }
            ReportPadError(ex);
        }
    }

    private void ReportPadError(Exception ex)
    {
        string detail = RfcommServer.Detail(ex);
        if (PadError == detail) return;
        PadError = detail;
        Log?.Invoke("ViGEm/XInput: " + detail);
        StateChanged?.Invoke();
    }

    private Task OnAccepted(StreamSocket socket, CancellationToken listenerLifetime)
    {
        lock (_gate)
        {
            if (_lifetime?.Token != listenerLifetime || listenerLifetime.IsCancellationRequested)
            {
                socket.Dispose();
                return Task.CompletedTask;
            }

            _localTest?.Cancel();
            EndSession();
            ReleaseInput();

            try { _peer = socket.Information.RemoteAddress.DisplayName; }
            catch (Exception) { _peer = "teléfono"; }
            var session = new ReceiverSession(++_sessionCounter, socket, listenerLifetime);
            Volatile.Write(ref _session, session);
            _connectionTasks.RemoveWhere(task => task.IsCompleted);
            var connection = RunSession(session);
            _connectionTasks.Add(connection);
            Log?.Invoke($"[BT] Sesión #{session.Id} de {_peer}. Esperando INPUT.");
            StateChanged?.Invoke();
            return connection;
        }
    }

    private async Task RunSession(ReceiverSession session)
    {
        try { await session.RunAsync(OnRead).ConfigureAwait(false); }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            lock (_gate)
                if (ReferenceEquals(_session, session)) Log?.Invoke("[BT] Enlace: " + RfcommServer.Detail(ex));
        }
        finally
        {
            lock (_gate)
            {
                if (ReferenceEquals(_session, session))
                {
                    EndSession();
                    ReleaseInput();
                    Log?.Invoke("[BT] Teléfono desconectado. Mando liberado.");
                    StateChanged?.Invoke();
                }
            }
        }
    }

    private void EndSession()
    {
        var old = _session;
        if (old == null) return;
        _lastSession = Diagnostics;
        Volatile.Write(ref _session, null);
        _peer = "—";
        try { old.Cancel(); }
        catch (Exception ex) { Log?.Invoke("[BT] Cierre de sesión: " + RfcommServer.Detail(ex)); }
    }

    private void OnRead(ReceiverSession session, byte[] bytes, long arrivedAt)
    {
        lock (_gate)
        {
            if (!ReferenceEquals(_session, session)) return;
            session.BeginRead(bytes.Length, arrivedAt);
            session.Pipe.Append(bytes);
            while (session.Pipe.TryReadFrame(out var frame, out var type))
            {
                session.CountFrame();
                switch (type)
                {
                    case FrameType.Input:
                        if (!PhantomProtocol.TryParse(frame, out _, out var input)) break;
                        if (!session.RecordInput(input, arrivedAt)) break;
                        if (session.Watchdog.Accept(arrivedAt)) Log?.Invoke("[BT] INPUT recuperado.");
                        _lastFrame = input;
                        UsePad(pad => pad.Apply(input));
                        break;
                    case FrameType.ReleaseAll:
                        ReleaseInput();
                        break;
                    case FrameType.Ping:
                        if (!session.Send(PhantomProtocol.BuildPong(PhantomProtocol.ReadSeq(frame))))
                            session.Cancel();
                        break;
                    case FrameType.Pong:
                        session.ReceivePong(PhantomProtocol.ReadSeq(frame), arrivedAt);
                        break;
                }
            }
        }
    }

    private void ReleaseInput()
    {
        _lastFrame = default;
        UsePad(pad => pad.ReleaseAll());
    }

    public async Task RunLocalTestAsync()
    {
        CancellationTokenSource test;
        lock (_gate)
        {
            if (!CanTest) return;
            test = new CancellationTokenSource();
            _localTest = test;
        }
        Log?.Invoke("PRUEBA LOCAL: abre joy.cpl para ver ejes, gatillos y botones.");
        StateChanged?.Invoke();
        var correlation = new XInputCorrelation();
        try
        {
            for (int t = 0; t < 20; t++)
            {
                double angle = t * Math.PI / 10;
                // Cuatro ejes y dos gatillos cambian juntos para atribuir el slot.
                var frame = new InputFrame((ushort)t, 0,
                    (sbyte)(Math.Sin(angle) * 113), (sbyte)(Math.Cos(angle) * 79),
                    (sbyte)(Math.Cos(angle) * 95), (sbyte)(Math.Sin(angle) * 67),
                    (byte)(t * 11), (byte)(227 - t * 11), 0, 0, 0, 0, 0, 0, 0);
                lock (_gate) ApplyLocal(test, frame);
                await Task.Delay(120, test.Token).ConfigureAwait(false);
                lock (_gate)
                {
                    test.Token.ThrowIfCancellationRequested();
                    UsePad(pad => pad.SampleLocalTest(correlation), retryOnFailure: false);
                }
            }
            lock (_gate)
            {
                test.Token.ThrowIfCancellationRequested();
                UsePad(pad => pad.CompleteLocalTest(correlation), retryOnFailure: false);
            }

            foreach (var button in Enum.GetValues<PadButtons>())
            {
                lock (_gate) ApplyLocal(test, default(InputFrame) with { Buttons = button });
                await Task.Delay(120, test.Token).ConfigureAwait(false);
            }
            Log?.Invoke("PRUEBA LOCAL completada. Consulta el resultado XInput o el barrido en joy.cpl.");
        }
        catch (OperationCanceledException)
        {
            Log?.Invoke("PRUEBA LOCAL interrumpida.");
        }
        finally
        {
            lock (_gate)
            {
                if (ReferenceEquals(_localTest, test))
                {
                    _localTest = null;
                    if (_session == null) ReleaseInput();
                }
                test.Dispose();
            }
            StateChanged?.Invoke();
        }
    }

    private void ApplyLocal(CancellationTokenSource test, InputFrame frame)
    {
        test.Token.ThrowIfCancellationRequested();
        _lastFrame = frame;
        UsePad(pad => pad.Apply(frame));
    }
}
