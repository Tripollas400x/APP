using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using Nefarius.ViGEm.Client.Targets.Xbox360;
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
///
/// Con la API de Nefarius.ViGEm.Client 1.21.x las clases concretas son internas
/// (solo se exponen IXbox360Controller/IDualShock4Controller) y cada llamada
/// Set* concreta reenvía el informe al bus (AutoSubmitReport interno = true).
/// Para no martillear el driver con un envío por cada campo, este componente
/// escribe el informe completo mediante las propiedades `ref` y los setters en
/// lote (SetButtonsFull / SetSpecialButtonsFull, que NO reenvían) y provoca
/// exactamente UN envío nativo por frame de entrada.
/// </summary>
public sealed class VirtualController : IDisposable
{
    private ViGEmClient? _client;
    private IXbox360Controller? _xbox;
    private IDualShock4Controller? _ds4;

    /// <summary>Rumble del juego (motor pequeño, motor grande), ambos modos.</summary>
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
#pragma warning disable CS0618
            // Evento marcado obsoleto por el autor de la librería ("puede no
            // comportarse como se espera"); la alternativa oficial exige un
            // hilo bloqueado en AwaitRawOutputReport() y parsear el informe
            // crudo. Se deja como mejor esfuerzo: el rumble garantizado está
            // en modo Xbox 360. Limitación documentada en el README.
            pad.FeedbackReceived += OnDualShock4Feedback;
#pragma warning restore CS0618
            pad.Connect();
            _ds4 = pad;
            UserIndex = -1; // la librería no expone el índice asignado para DS4
        }
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

        // Único envío nativo del frame: SetAxisValue dispara el auto-submit
        // interno con el informe ya completo (valor idempotente).
        p.SetAxisValue(Xbox360Axis.LeftThumbX, AxisX(f.LX));
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

        // Único envío nativo del frame: SetDPadDirection arregla el nibble del
        // D-pad (0 = Norte en DS4, por eso va al final) y dispara el auto-submit.
        p.SetDPadDirection(DPadDirection(f));
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
