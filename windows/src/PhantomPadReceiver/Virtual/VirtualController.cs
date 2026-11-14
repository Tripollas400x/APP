using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using PhantomPadReceiver.Protocol;

namespace PhantomPadReceiver.Virtual;

public enum PadMode
{
    Xbox360,
    DualShock4
}

/// <summary>
/// Mando virtual sobre ViGEmBus. Modo Xbox 360 (XInput, recomendado) y modo
/// DualShock 4 (con las limitaciones conocidas documentadas en el README).
/// Si el driver ViGEmBus no está instalado, Start() lanza y el motor informa.
/// </summary>
public sealed class VirtualController : IDisposable
{
    private ViGEmClient? _client;
    private IXbox360Controller? _xbox;
    private IDualShock4Controller? _ds4;

    /// <summary>Rumble del juego (motor pequeño, motor grande), solo modo Xbox 360.</summary>
    public event Action<byte, byte>? RumbleRequested;

    public PadMode Mode { get; }
    public int UserIndex { get; private set; } = -1;
    public bool Ready => _xbox != null || _ds4 != null;

    public VirtualController(PadMode mode)
    {
        Mode = mode;
    }

    public void Start()
    {
        _client = new ViGEmClient();
        if (Mode == PadMode.Xbox360)
        {
            var pad = _client.CreateXbox360Controller();
            pad.FeedbackReceived += OnXboxFeedback;
            pad.Connect();
            _xbox = pad;
            UserIndex = pad.UserIndex;
        }
        else
        {
            var pad = _client.CreateDualShock4Controller();
            pad.Connect();
            _ds4 = pad;
            UserIndex = pad.UserIndex;
        }
    }

    private void OnXboxFeedback(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        RumbleRequested?.Invoke(e.SmallMotor, e.LargeMotor);
    }

    public void Apply(in InputFrame f)
    {
        if (_xbox != null)
        {
            ApplyXbox(_xbox, f);
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

    private static void ApplyXbox(IXbox360Controller p, in InputFrame f)
    {
        p.SetAxisValue(Xbox360Axis.LeftThumbX, AxisX(f.LX));
        p.SetAxisValue(Xbox360Axis.LeftThumbY, AxisY(f.LY));
        p.SetAxisValue(Xbox360Axis.RightThumbX, AxisX(f.RX));
        p.SetAxisValue(Xbox360Axis.RightThumbY, AxisY(f.RY));
        p.SetSliderValue(Xbox360Slider.LeftTrigger, f.L2);
        p.SetSliderValue(Xbox360Slider.RightTrigger, f.R2);

        p.SetButtonState(Xbox360Button.A, f.Buttons.HasFlag(PadButtons.Cross));
        p.SetButtonState(Xbox360Button.B, f.Buttons.HasFlag(PadButtons.Circle));
        p.SetButtonState(Xbox360Button.X, f.Buttons.HasFlag(PadButtons.Square));
        p.SetButtonState(Xbox360Button.Y, f.Buttons.HasFlag(PadButtons.Triangle));
        p.SetButtonState(Xbox360Button.DPadUp, f.Buttons.HasFlag(PadButtons.DpadUp));
        p.SetButtonState(Xbox360Button.DPadDown, f.Buttons.HasFlag(PadButtons.DpadDown));
        p.SetButtonState(Xbox360Button.DPadLeft, f.Buttons.HasFlag(PadButtons.DpadLeft));
        p.SetButtonState(Xbox360Button.DPadRight, f.Buttons.HasFlag(PadButtons.DpadRight));
        p.SetButtonState(Xbox360Button.LeftShoulder, f.Buttons.HasFlag(PadButtons.L1));
        p.SetButtonState(Xbox360Button.RightShoulder, f.Buttons.HasFlag(PadButtons.R1));
        p.SetButtonState(Xbox360Button.LeftThumb, f.Buttons.HasFlag(PadButtons.L3));
        p.SetButtonState(Xbox360Button.RightThumb, f.Buttons.HasFlag(PadButtons.R3));
        p.SetButtonState(Xbox360Button.Start, f.Buttons.HasFlag(PadButtons.Options));
        p.SetButtonState(Xbox360Button.Back, f.Buttons.HasFlag(PadButtons.Share));
        p.SetButtonState(Xbox360Button.Guide, f.Buttons.HasFlag(PadButtons.Ps));

        p.SubmitReport();
    }

    // ------------------------------------------------------------- DualShock 4

    private static void ApplyDualShock4(IDualShock4Controller p, in InputFrame f)
    {
        p.SetAxisValue(DualShock4Axis.LeftX, (byte)(f.LX + 128));
        p.SetAxisValue(DualShock4Axis.LeftY, (byte)(f.LY + 128));
        p.SetAxisValue(DualShock4Axis.RightX, (byte)(f.RX + 128));
        p.SetAxisValue(DualShock4Axis.RightY, (byte)(f.RY + 128));
        p.SetSliderValue(DualShock4Slider.LeftTrigger, f.L2);
        p.SetSliderValue(DualShock4Slider.RightTrigger, f.R2);

        p.SetButtonState(DualShock4Button.Triangle, f.Buttons.HasFlag(PadButtons.Triangle));
        p.SetButtonState(DualShock4Button.Circle, f.Buttons.HasFlag(PadButtons.Circle));
        p.SetButtonState(DualShock4Button.Cross, f.Buttons.HasFlag(PadButtons.Cross));
        p.SetButtonState(DualShock4Button.Square, f.Buttons.HasFlag(PadButtons.Square));
        p.SetButtonState(DualShock4Button.L1, f.Buttons.HasFlag(PadButtons.L1));
        p.SetButtonState(DualShock4Button.R1, f.Buttons.HasFlag(PadButtons.R1));
        p.SetButtonState(DualShock4Button.L3, f.Buttons.HasFlag(PadButtons.L3));
        p.SetButtonState(DualShock4Button.R3, f.Buttons.HasFlag(PadButtons.R3));
        p.SetButtonState(DualShock4Button.Options, f.Buttons.HasFlag(PadButtons.Options));
        p.SetButtonState(DualShock4Button.Share, f.Buttons.HasFlag(PadButtons.Share));
        p.SetButtonState(DualShock4Button.PS, f.Buttons.HasFlag(PadButtons.Ps));
        p.SetButtonState(DualShock4Button.TouchPad, f.Buttons.HasFlag(PadButtons.Touchpad));
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
        try { _xbox?.Disconnect(); } catch { /* driver puede ya no estar */ }
        try { _ds4?.Disconnect(); } catch { /* idem */ }
        _xbox = null;
        _ds4 = null;
        _client?.Dispose();
        _client = null;
    }
}
