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

/// <summary>
/// Mando virtual sobre ViGEmBus. Modo Xbox 360 (XInput, recomendado) y modo
/// DualShock 4.
///
/// COMPORTAMIENTO VERIFICADO de Nefarius.ViGEm.Client 1.21.256 (decompilado):
///   - Connect() = vigem_target_add (plug) + registro de la notificación.
///     Devuelve cuando el BUS acepta el plug, no cuando Windows termina de
///     enumerar el dispositivo.
///   - UserIndex SOLO se asigna cuando el bus envía su primera notificación
///     (asíncrona; ocurre cuando el host XUSB de Windows inicializa el mando).
///     Leer UserIndex antes de eso lanza Xbox360UserIndexNotReportedException.
///
/// Por eso aquí la creación, la conexión y la consulta del índice están
/// SEPARADAS: si Connect() no lanza, el mando queda enchufado y operativo
/// aunque el índice tarde; el índice se consulta en segundo plano de forma
/// tolerante ("Índice pendiente") y NUNCA se destruye el mando por una
/// consulta prematura. El fallback a DS4 solo ocurre si Connect() falla de
/// verdad (bus no encontrado, sin hueco, etc.).
///
/// Con la API 1.21.x las clases concretas son internas (solo se exponen
/// IXbox360Controller/IDualShock4Controller) y cada llamada Set* concreta
/// reenvía el informe al bus (AutoSubmitReport interno = true). Para no
/// martillear el driver con un envío por cada campo, este componente escribe
/// el informe completo mediante las propiedades `ref` y los setters en lote
/// (SetButtonsFull / SetSpecialButtonsFull, que NO reenvían) y provoca
/// exactamente UN envío nativo por frame de entrada.
/// </summary>
public sealed class VirtualController : IDisposable
{
    private ViGEmClient? _client;
    private IXbox360Controller? _xbox;
    private IDualShock4Controller? _ds4;
    private System.Threading.Timer? _indexTimer;

    private const int MaxAttempts = 3;
    private const int RetryDelayMs = 400;
    private const int IndexPollFirstMs = 300;
    private const int IndexPollPeriodMs = 500;
    private const int IndexPollMax = 120; // 60 s de sondeo; luego deja de avisar

    /// <summary>Avisos de progreso (reintentos, fallback) para el registro.</summary>
    public event Action<string>? Status;

    /// <summary>Rumble del juego (motor pequeño, motor grande), ambos modos.</summary>
    public event Action<byte, byte>? RumbleRequested;

    public PadMode Mode { get; }

    /// <summary>true si el modo pedido era Xbox 360 pero solo respondió DS4.</summary>
    public bool FellBackToDs4 { get; private set; }

    /// <summary>
    /// Índice del jugador reportado por el bus, o -1 si aún no llegó o el modo
    /// DS4 no lo expone. NO lanza excepciones nunca.
    /// </summary>
    public int UserIndex { get; private set; } = -1;

    /// <summary>true mientras el índice Xbox 360 todavía no ha sido reportado.</summary>
    public bool UserIndexPending { get; private set; }

    public bool Ready => _xbox != null || _ds4 != null;

    public VirtualController(PadMode mode)
    {
        Mode = mode;
    }

    /// <summary>El servicio ViGEmBus existe en el sistema (driver instalado).</summary>
    public static bool DriverInstalled =>
        Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\ViGEmBus") != null;

    public void Start()
    {
        var errors = new List<string>();

        if (Mode == PadMode.Xbox360)
        {
            if (TryStartXbox360(errors))
            {
                return;
            }

            // Fallback SOLO si el plug de Xbox 360 falló de verdad en todos los
            // intentos (no por una consulta de índice).
            Status?.Invoke(
                "Xbox 360 no pudo enchufarse tras " + MaxAttempts + " intentos; probando DualShock 4..."
            );
            ResetPads();
            if (TryStartDualShock4(errors))
            {
                FellBackToDs4 = true;
                return;
            }
        }
        else if (TryStartDualShock4(errors))
        {
            return;
        }

        throw new VirtualPadException(BuildFailureMessage(errors));
    }

    private bool TryStartXbox360(List<string> errors)
    {
        for (int attempt = 1; attempt <= MaxAttempts; attempt++)
        {
            try
            {
                var client = new ViGEmClient();
                IXbox360Controller? pad = null;
                try
                {
                    pad = client.CreateXbox360Controller();
                    pad.FeedbackReceived += OnXboxFeedback;
                    // Único punto de fallo duro: si esto no lanza, el mando
                    // está ENCHUFADO en el bus y operativo.
                    pad.Connect();
                }
                catch
                {
                    if (pad != null) pad.FeedbackReceived -= OnXboxFeedback;
                    client.Dispose();
                    throw;
                }
                _client = client;
                _xbox = pad;
                UserIndex = -1;
                UserIndexPending = true;
                Status?.Invoke(
                    "Xbox 360 enchufado en el bus ViGEm (intento " + attempt + "). " +
                    "Esperando el índice del jugador (llega asíncronamente)..."
                );
                StartIndexPoll();
                return true;
            }
            catch (Exception ex)
            {
                errors.Add(Describe(attempt, "Xbox360", ex));
                ResetPads();
                if (attempt < MaxAttempts)
                {
                    Status?.Invoke(
                        "Intento " + attempt + "/" + MaxAttempts + " de Xbox 360 falló (" +
                        DescribeShort(ex) + "); reintentando en " + RetryDelayMs + " ms..."
                    );
                    Thread.Sleep(RetryDelayMs);
                }
            }
        }
        return false;
    }

    private bool TryStartDualShock4(List<string> errors)
    {
        for (int attempt = 1; attempt <= MaxAttempts; attempt++)
        {
            try
            {
                var client = new ViGEmClient();
                IDualShock4Controller? pad = null;
                try
                {
                    pad = client.CreateDualShock4Controller();
#pragma warning disable CS0618
                    // Evento marcado obsoleto por el autor de la librería ("puede no
                    // comportarse como se espera"); la alternativa oficial exige un
                    // hilo bloqueado en AwaitRawOutputReport() y parsear el informe
                    // crudo. Se deja como mejor esfuerzo: el rumble garantizado está
                    // en modo Xbox 360. Limitación documentada en el README.
                    pad.FeedbackReceived += OnDualShock4Feedback;
#pragma warning restore CS0618
                    pad.Connect();
                }
                catch
                {
                    if (pad != null)
                    {
#pragma warning disable CS0618
                        pad.FeedbackReceived -= OnDualShock4Feedback;
#pragma warning restore CS0618
                    }
                    client.Dispose();
                    throw;
                }
                _client = client;
                _ds4 = pad;
                UserIndex = -1;
                UserIndexPending = false;
                Status?.Invoke("DualShock 4 enchufado en el bus ViGEm (intento " + attempt + ").");
                return true;
            }
            catch (Exception ex)
            {
                errors.Add(Describe(attempt, "DualShock4", ex));
                ResetPads();
                if (attempt < MaxAttempts)
                {
                    Thread.Sleep(RetryDelayMs);
                }
            }
        }
        return false;
    }

    /// <summary>
    /// Sondeo tolerante del índice del jugador: consulta UserIndex cada 500 ms
    /// atrapando la excepción "no reportado". NUNCA destruye ni recrea el
    /// mando; solo informa. El mando funciona igual mientras el índice llegue
    /// (los informes se envían por el handle del target, no por el índice).
    /// </summary>
    private void StartIndexPoll()
    {
        StopIndexPoll();
        int polls = 0;
        _indexTimer = new System.Threading.Timer(_ =>
        {
            var pad = _xbox;
            if (pad == null || !UserIndexPending)
            {
                StopIndexPoll();
                return;
            }
            try
            {
                int idx = pad.UserIndex; // lanza Xbox360UserIndexNotReportedException si aún no llegó
                UserIndex = idx;
                UserIndexPending = false;
                Status?.Invoke("Índice del jugador Xbox 360 reportado por el bus: " + idx + ".");
                StopIndexPoll();
            }
            catch (Xbox360UserIndexNotReportedException)
            {
                polls++;
                if (polls == 1 || polls % 8 == 0)
                {
                    Status?.Invoke(
                        "Índice del jugador pendiente (" + polls + " consultas): el mando ya está " +
                        "enchufado y operativo; Windows lo inicializará en cuanto el host XUSB lo enumere " +
                        "(mira joy.cpl). No hace falta reintentar."
                    );
                }
                if (polls >= IndexPollMax)
                {
                    Status?.Invoke(
                        "El bus no reportó el índice del jugador en 60 s (el mando sigue enchufado). " +
                        "Si joy.cpl no lo muestra, cierra DS4Windows/reWASD/x360ce o reinstala ViGEmBus."
                    );
                    StopIndexPoll();
                }
            }
            catch
            {
                // el target se destruyó (Stop/Dispose): cortar el sondeo sin ruido
                StopIndexPoll();
            }
        }, null, IndexPollFirstMs, IndexPollPeriodMs);
    }

    private void StopIndexPoll()
    {
        _indexTimer?.Dispose();
        _indexTimer = null;
    }

    private void ResetPads()
    {
        StopIndexPoll();
        try { _xbox?.Disconnect(); } catch { /* driver puede ya no estar */ }
        try { _ds4?.Disconnect(); } catch { /* idem */ }
        _xbox = null;
        _ds4 = null;
        try { _client?.Dispose(); } catch { }
        _client = null;
        UserIndex = -1;
        UserIndexPending = false;
    }

    /// <summary>Tipo + mensaje (o "(sin mensaje)") + HRESULT si es relevante.</summary>
    private static string DescribeShort(Exception ex)
    {
        string msg = string.IsNullOrWhiteSpace(ex.Message) ? "(sin mensaje)" : ex.Message;
        string hr = ex.HResult != 0 ? $" (HRESULT 0x{ex.HResult:X8})" : "";
        return $"{ex.GetType().Name}: {msg}{hr}";
    }

    private static string Describe(int attempt, string target, Exception ex)
        => $"{target} intento {attempt}: {DescribeShort(ex)}\n  en {FirstStackLine(ex)}";

    private static string FirstStackLine(Exception ex)
    {
        var lines = (ex.StackTrace ?? "").Split('\n');
        return lines.Length > 0 ? lines[0].Trim() : "(sin stack)";
    }

    private static string BuildFailureMessage(List<string> errors)
    {
        string detail = string.Join(" | ", errors.TakeLast(3));
        return DriverInstalled
            ? "El driver ViGEmBus está instalado pero el bus no aceptó ningún mando. " +
              "Cierra apps que también crean mandos virtuales (DS4Windows, reWASD, x360ce), " +
              "y si sigue igual reinstala ViGEmBus desde https://vigem.org/downloads/ y reinicia. " +
              "Detalles: " + detail
            : "El driver ViGEmBus NO está instalado. Descárgalo de https://vigem.org/downloads/, " +
              "instálalo y reinicia el PC. Detalles: " + detail;
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
        ResetPads();
    }
}
