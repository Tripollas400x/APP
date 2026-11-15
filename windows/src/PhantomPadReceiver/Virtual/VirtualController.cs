using Microsoft.Win32;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using Nefarius.ViGEm.Client.Targets.Xbox360.Exceptions;
using PhantomPadReceiver.Protocol;

namespace PhantomPadReceiver.Virtual;

public enum PadMode
{
    Xbox360,
    DualShock4
}

/// <summary>
/// Error al crear el mando virtual con un mensaje de verdad: indica si el
/// driver ViGEmBus está instalado y qué falló en cada intento, en vez de los
/// mensajes vacíos o absurdos (p. ej. "La operación se completó
/// correctamente") que lanza la librería de Nefarius.
/// </summary>
public sealed class VirtualPadException : Exception
{
    public VirtualPadException(string message, Exception? inner = null) : base(message, inner) { }
}

public enum ControllerState
{
    NotInitialized, ConnectingToBus, BusConnected, WindowsEnumerating,
    InputReady, Degraded, Failed, Disposed
}

/// <summary>Mando ViGEm. El motor serializa creación, entrada, sondeo y liberación.</summary>
public sealed class VirtualController : IDisposable
{
    private ViGEmClient? _client;
    private IXbox360Controller? _xbox;
    private IDualShock4Controller? _ds4;
    private XInputReport _lastReport;
    private int _correlatedIndex = -1;
    private int _verifiedIndex = -1;
    private bool _wasVerified;

    public event Action<string>? Status;
    public event Action<byte, byte>? RumbleRequested;

    public PadMode Mode { get; }
    public PadMode EffectiveMode => _ds4 != null ? PadMode.DualShock4 : Mode;
    public ControllerState State { get; private set; }
    public bool HasTarget => _xbox != null || _ds4 != null;
    public int RawUserIndex { get; private set; } = -1;
    public int UserIndex { get; private set; } = -1;
    public string XInputStatus { get; private set; } = "pendiente";
    public string Identity => HasTarget
        ? EffectiveMode == PadMode.Xbox360 ? "Xbox 360 · VID:PID 045E:028E" : "DualShock 4 · VID:PID 054C:05C4"
        : "—";

    public VirtualController(PadMode mode) => Mode = mode;

    public static bool DriverInstalled
    {
        get
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ViGEmBus");
            return key != null;
        }
    }

    public void Start()
    {
        ObjectDisposedException.ThrowIf(State == ControllerState.Disposed, this);
        if (HasTarget) return;

        State = ControllerState.ConnectingToBus;
        try
        {
            if (Mode == PadMode.Xbox360)
            {
                try
                {
                    ConnectTarget(Mode);
                }
                catch (Exception ex)
                {
                    Status?.Invoke("Xbox 360: " + Bluetooth.RfcommServer.Detail(ex) + ". Probando DualShock 4.");
                    ConnectTarget(PadMode.DualShock4);
                }
            }
            else
            {
                ConnectTarget(Mode);
            }
            State = ControllerState.BusConnected;
            Status?.Invoke($"{EffectiveMode} registrado en ViGEm. Verificando entrada en Windows.");
        }
        catch (Exception ex)
        {
            State = ControllerState.Failed;
            throw new VirtualPadException(
                (DriverInstalled ? "ViGEmBus instalado. " : "ViGEmBus no detectado. ") +
                Bluetooth.RfcommServer.Detail(ex), ex);
        }
    }

    private void ConnectTarget(PadMode mode)
    {
        ViGEmClient? client = null;
        IXbox360Controller? xbox = null;
        IDualShock4Controller? ds4 = null;
        try
        {
            client = new ViGEmClient();
            if (mode == PadMode.Xbox360)
            {
                xbox = client.CreateXbox360Controller();
                xbox.FeedbackReceived += OnXboxFeedback;
                xbox.AutoSubmitReport = false;
                xbox.Connect();
            }
            else
            {
                ds4 = client.CreateDualShock4Controller();
#pragma warning disable CS0618
                ds4.FeedbackReceived += OnDualShock4Feedback;
#pragma warning restore CS0618
                ds4.AutoSubmitReport = false;
                ds4.Connect();
            }
        }
        catch
        {
            // Connect puede enchufar el target y luego fallar al registrar sus callbacks.
            Cleanup(client, xbox, ds4);
            throw;
        }
        _client = client;
        _xbox = xbox;
        _ds4 = ds4;
    }

    public void ProbeXInput()
    {
        if (_xbox == null)
        {
            if (_ds4 != null) XInputStatus = "DS4/HID: comprueba la entrada en joy.cpl";
            return;
        }

        try { RawUserIndex = _xbox.UserIndex; }
        catch (Xbox360UserIndexNotReportedException) { RawUserIndex = -1; }

        var slots = XInput.Scan();
        int index = XInput.IsPlayerIndex(RawUserIndex) ? RawUserIndex : _correlatedIndex;
        if (XInput.IsPlayerIndex(index) && !slots[index].Connected)
        {
            _verifiedIndex = -1;
            _correlatedIndex = -1;
        }
        UserIndex = XInput.IsPlayerIndex(index) && slots[index].Connected ? index : -1;
        if (UserIndex != _verifiedIndex) _verifiedIndex = -1;
        if (UserIndex == RawUserIndex && UserIndex >= 0 &&
            _lastReport != default && slots[index].Report.WithoutGuide() == _lastReport)
        {
            _verifiedIndex = index;
        }

        string results = string.Join(" ", slots.Select((slot, i) => $"{i}={slot.Result}"));
        string attribution = UserIndex < 0 ? "sin atribuir" :
            UserIndex == RawUserIndex ? $"ViGEm #{UserIndex}" : $"prueba local #{UserIndex}";
        XInputStatus = $"GetState {results} · {attribution}";
        if (_verifiedIndex >= 0)
        {
            _wasVerified = true;
            State = ControllerState.InputReady;
        }
        else
        {
            State = _wasVerified ? ControllerState.Degraded :
                UserIndex >= 0 ? ControllerState.BusConnected : ControllerState.WindowsEnumerating;
        }
    }

    internal void SampleLocalTest(XInputCorrelation correlation)
    {
        if (_xbox != null) correlation.Observe(_lastReport, XInput.Scan());
    }

    internal void CompleteLocalTest(XInputCorrelation correlation)
    {
        int index = correlation.VerifiedIndex;
        if (_xbox == null || !XInput.IsPlayerIndex(index)) return;
        if (XInput.IsPlayerIndex(RawUserIndex) && RawUserIndex != index) return;
        _correlatedIndex = index;
        _verifiedIndex = index;
        ProbeXInput();
    }

    private void Cleanup(ViGEmClient? client, IXbox360Controller? xbox, IDualShock4Controller? ds4)
    {
        if (xbox != null) xbox.FeedbackReceived -= OnXboxFeedback;
#pragma warning disable CS0618
        if (ds4 != null) ds4.FeedbackReceived -= OnDualShock4Feedback;
#pragma warning restore CS0618
        IVirtualGamepad? pad = xbox as IVirtualGamepad ?? ds4;
        if (pad != null) TryCleanup(pad.Disconnect);
        if (pad is IDisposable target) TryCleanup(target.Dispose);
        if (client != null) TryCleanup(client.Dispose);
    }

    private void TryCleanup(Action action)
    {
        try { action(); }
        catch (Exception ex) { Status?.Invoke("ViGEm limpieza: " + Bluetooth.RfcommServer.Detail(ex)); }
    }

    private void OnXboxFeedback(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        RumbleRequested?.Invoke(e.SmallMotor, e.LargeMotor);
    }

    private void OnDualShock4Feedback(object sender, DualShock4FeedbackReceivedEventArgs e)
    {
        RumbleRequested?.Invoke(e.SmallMotor, e.LargeMotor);
    }

    public void Apply(in InputFrame f)
    {
        if (_xbox != null)
        {
            _lastReport = ApplyXbox(_xbox, f).WithoutGuide();
        }
        else if (_ds4 != null)
        {
            ApplyDualShock4(_ds4, f);
        }
    }

    public void ReleaseAll()
    {
        var zero = new InputFrame(
            0, 0,
            0, 0, 0, 0,
            0, 0,
            0,
            0, 0, 0,
            0, 0, 0);
        Apply(zero);
    }

    // ------------------------------------------------------------------ Xbox

    /// <summary>Eje i8 (-128..127) a i16 XInput (-32768..32767).</summary>
    private static short AxisX(sbyte v) => v == -128 ? short.MinValue : (short)(v * 257);

    /// <summary>Como AxisX pero invierte Y: en pantalla "arriba" es negativo;
    /// en XInput, "arriba" es positivo.</summary>
    private static short AxisY(sbyte v) => v == -128 ? short.MaxValue : (short)(-v * 257);

    private static XInputReport ApplyXbox(IXbox360Controller p, in InputFrame f)
    {
        // Escrituras directas en el informe nativo (sin envío).
        p.LeftThumbX = AxisX(f.LX);
        p.LeftThumbY = AxisY(f.LY);
        p.RightThumbX = AxisX(f.RX);
        p.RightThumbY = AxisY(f.RY);
        p.LeftTrigger = f.L2;
        p.RightTrigger = f.R2;

        ushort buttons = 0;
        if (f.Buttons.HasFlag(PadButtons.Cross)) buttons |= Xbox360Button.A.Value;
        if (f.Buttons.HasFlag(PadButtons.Circle)) buttons |= Xbox360Button.B.Value;
        if (f.Buttons.HasFlag(PadButtons.Square)) buttons |= Xbox360Button.X.Value;
        if (f.Buttons.HasFlag(PadButtons.Triangle)) buttons |= Xbox360Button.Y.Value;
        if (f.Buttons.HasFlag(PadButtons.DpadUp)) buttons |= Xbox360Button.Up.Value;
        if (f.Buttons.HasFlag(PadButtons.DpadDown)) buttons |= Xbox360Button.Down.Value;
        if (f.Buttons.HasFlag(PadButtons.DpadLeft)) buttons |= Xbox360Button.Left.Value;
        if (f.Buttons.HasFlag(PadButtons.DpadRight)) buttons |= Xbox360Button.Right.Value;
        if (f.Buttons.HasFlag(PadButtons.L1)) buttons |= Xbox360Button.LeftShoulder.Value;
        if (f.Buttons.HasFlag(PadButtons.R1)) buttons |= Xbox360Button.RightShoulder.Value;
        if (f.Buttons.HasFlag(PadButtons.L3)) buttons |= Xbox360Button.LeftThumb.Value;
        if (f.Buttons.HasFlag(PadButtons.R3)) buttons |= Xbox360Button.RightThumb.Value;
        if (f.Buttons.HasFlag(PadButtons.Options)) buttons |= Xbox360Button.Start.Value;
        if (f.Buttons.HasFlag(PadButtons.Share)) buttons |= Xbox360Button.Back.Value;
        if (f.Buttons.HasFlag(PadButtons.Ps)) buttons |= Xbox360Button.Guide.Value;
        p.ButtonState = buttons; // escritura en lote, sin envío

        p.SubmitReport();
        return new(buttons, f.L2, f.R2, p.LeftThumbX, p.LeftThumbY, p.RightThumbX, p.RightThumbY);
    }

    // ------------------------------------------------------------- DualShock 4

    private static void ApplyDualShock4(IDualShock4Controller p, in InputFrame f)
    {
        // Escrituras directas en el informe nativo (sin envío).
        p.LeftThumbX = (byte)(f.LX + 128);
        p.LeftThumbY = (byte)(f.LY + 128);
        p.RightThumbX = (byte)(f.RX + 128);
        p.RightThumbY = (byte)(f.RY + 128);
        p.LeftTrigger = f.L2;
        p.RightTrigger = f.R2;

        ushort buttons = 0;
        if (f.Buttons.HasFlag(PadButtons.Triangle)) buttons |= DualShock4Button.Triangle.Value;
        if (f.Buttons.HasFlag(PadButtons.Circle)) buttons |= DualShock4Button.Circle.Value;
        if (f.Buttons.HasFlag(PadButtons.Cross)) buttons |= DualShock4Button.Cross.Value;
        if (f.Buttons.HasFlag(PadButtons.Square)) buttons |= DualShock4Button.Square.Value;
        if (f.Buttons.HasFlag(PadButtons.L1)) buttons |= DualShock4Button.ShoulderLeft.Value;
        if (f.Buttons.HasFlag(PadButtons.R1)) buttons |= DualShock4Button.ShoulderRight.Value;
        if (f.Buttons.HasFlag(PadButtons.L3)) buttons |= DualShock4Button.ThumbLeft.Value;
        if (f.Buttons.HasFlag(PadButtons.R3)) buttons |= DualShock4Button.ThumbRight.Value;
        if (f.Buttons.HasFlag(PadButtons.Options)) buttons |= DualShock4Button.Options.Value;
        if (f.Buttons.HasFlag(PadButtons.Share)) buttons |= DualShock4Button.Share.Value;
        p.SetButtonsFull(buttons); // escritura en lote, sin envío

        // PS y el clic del touchpad van en el byte de botones especiales del DS4.
        byte special = 0;
        if (f.Buttons.HasFlag(PadButtons.Ps)) special |= (byte)DualShock4SpecialButton.Ps.Value;
        if (f.Buttons.HasFlag(PadButtons.Touchpad)) special |= (byte)DualShock4SpecialButton.Touchpad.Value;
        p.SetSpecialButtonsFull(special); // escritura en lote, sin envío

        p.SetDPadDirection(DPadDirection(f));
        p.SubmitReport();
    }

    private static DualShock4DPadDirection DPadDirection(in InputFrame f)
    {
        bool up = f.Buttons.HasFlag(PadButtons.DpadUp);
        bool down = f.Buttons.HasFlag(PadButtons.DpadDown);
        bool left = f.Buttons.HasFlag(PadButtons.DpadLeft);
        bool right = f.Buttons.HasFlag(PadButtons.DpadRight);
        if (up && right) return DualShock4DPadDirection.Northeast;
        if (down && right) return DualShock4DPadDirection.Southeast;
        if (down && left) return DualShock4DPadDirection.Southwest;
        if (up && left) return DualShock4DPadDirection.Northwest;
        if (up) return DualShock4DPadDirection.North;
        if (down) return DualShock4DPadDirection.South;
        if (left) return DualShock4DPadDirection.West;
        if (right) return DualShock4DPadDirection.East;
        return DualShock4DPadDirection.None;
    }

    public void Dispose()
    {
        if (State == ControllerState.Disposed) return;
        State = ControllerState.Disposed;
        Cleanup(_client, _xbox, _ds4);
        _xbox = null;
        _ds4 = null;
        _client = null;
    }
}
