using System.Diagnostics;
using PhantomPadReceiver.Core;
using PhantomPadReceiver.Protocol;
using PhantomPadReceiver.Virtual;

namespace PhantomPadReceiver.Ui;

/// <summary>
/// Ventana principal (interfaz oscura estilo Phantom): píldora de estado en
/// vivo, estado del driver ViGEmBus, estadísticas, panel de prueba que dibuja
/// todos los controles en tiempo real y registro con colores por severidad.
/// </summary>
public sealed class MainForm : Form
{
    private const string AppVersion = "v0.2.0";

    private readonly ReceiverEngine _engine = new();

    private readonly ComboBox _modeCombo = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList,
        Width = 150,
        FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(28, 28, 40),
        ForeColor = Color.Gainsboro
    };

    private readonly Button _startButton = new() { Text = "Iniciar", Width = 110, Height = 32 };
    private readonly Button _stopButton = new() { Text = "Detener", Width = 110, Height = 32 };
    private readonly LinkLabel _helpLink = new() { Text = "Guía del driver ViGEmBus", AutoSize = true };
    private readonly Label _infoLabel = new() { AutoSize = true };
    private readonly Panel _padPanel = new() { Dock = DockStyle.Fill };
    private readonly RichTextBox _logBox = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        BackColor = Color.FromArgb(12, 12, 18),
        BorderStyle = BorderStyle.None,
        Font = new Font("Consolas", 9f)
    };
    private readonly Button _clearLogButton = new() { Text = "Limpiar", Width = 90, Height = 28 };
    private readonly Button _copyLogButton = new() { Text = "Copiar registro", Width = 130, Height = 28 };
    private readonly StatusPill _pill = new() { Width = 340, Height = 40 };
    private readonly System.Windows.Forms.Timer _uiTimer = new() { Interval = 250 };

    private long _lastFrames;
    private DateTime _lastFpsAt = DateTime.UtcNow;

    private static readonly Color AccentColor = Color.FromArgb(158, 123, 255);
    private static readonly Color PanelDark = Color.FromArgb(10, 10, 14);
    private static readonly Color HeaderDark = Color.FromArgb(14, 14, 20);
    private static readonly Color ControlOff = Color.FromArgb(40, 40, 56);
    private static readonly Color ControlHover = Color.FromArgb(56, 56, 78);
    private static readonly Color OkGreen = Color.FromArgb(76, 175, 80);
    private static readonly Color WarnAmber = Color.FromArgb(255, 183, 77);
    private static readonly Color ErrRed = Color.FromArgb(239, 83, 80);

    private static readonly (PadButtons Button, string Label, float Fx, float Fy, Color Color)[] FaceButtons =
    {
        (PadButtons.Triangle, "Y", 0.78f, 0.42f, Color.FromArgb(0, 168, 107)),
        (PadButtons.Circle,   "B", 0.86f, 0.55f, Color.FromArgb(244, 67, 54)),
        (PadButtons.Cross,    "A", 0.78f, 0.68f, Color.FromArgb(33, 150, 243)),
        (PadButtons.Square,   "X", 0.70f, 0.55f, Color.FromArgb(232, 121, 249))
    };

    public MainForm()
    {
        Text = "Phantom Pad Receiver " + AppVersion;
        Size = new Size(980, 720);
        MinimumSize = new Size(860, 600);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = PanelDark;
        Font = new Font("Segoe UI", 9.5f);
        DoubleBuffered = true;

        _modeCombo.DataSource = Enum.GetValues<PadMode>();

        // ---------------------------------------------------------- cabecera
        var top = new Panel { Dock = DockStyle.Top, Height = 186, BackColor = HeaderDark };

        var title = new Label
        {
            Text = "PHANTOM PAD RECEIVER",
            AutoSize = true,
            Font = new Font("Segoe UI", 15f, FontStyle.Bold),
            ForeColor = AccentColor,
            Location = new Point(16, 10)
        };
        var version = new Label
        {
            Text = AppVersion,
            AutoSize = true,
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            ForeColor = Color.FromArgb(120, 110, 160),
            Location = new Point(305, 20)
        };
        var subtitle = new Label
        {
            Text = "Bluetooth del teléfono → mando virtual (ViGEmBus) en Windows",
            AutoSize = true,
            ForeColor = Color.Silver,
            Location = new Point(16, 44)
        };

        var modeLabel = new Label { Text = "Modo:", AutoSize = true, Location = new Point(16, 82) };
        _modeCombo.Location = new Point(60, 78);
        _startButton.Location = new Point(224, 74);
        _stopButton.Location = new Point(342, 74);
        _helpLink.Location = new Point(468, 81);

        _pill.Location = new Point(16, 118);
        _infoLabel.Location = new Point(16, 160);
        _infoLabel.ForeColor = Color.Gainsboro;

        top.Controls.Add(title);
        top.Controls.Add(version);
        top.Controls.Add(subtitle);
        top.Controls.Add(modeLabel);
        top.Controls.Add(_modeCombo);
        top.Controls.Add(_startButton);
        top.Controls.Add(_stopButton);
        top.Controls.Add(_helpLink);
        top.Controls.Add(_pill);
        top.Controls.Add(_infoLabel);

        // --------------------------------------------------------------- log
        var logPanel = new Panel { Dock = DockStyle.Bottom, Height = 196, BackColor = PanelDark };
        var logButtons = new Panel { Dock = DockStyle.Top, Height = 38, BackColor = HeaderDark };
        _clearLogButton.Location = new Point(8, 5);
        _copyLogButton.Location = new Point(106, 5);
        StyleButton(_clearLogButton);
        StyleButton(_copyLogButton);
        logButtons.Controls.Add(_clearLogButton);
        logButtons.Controls.Add(_copyLogButton);
        logPanel.Controls.Add(_logBox);
        logPanel.Controls.Add(logButtons);

        _padPanel.BackColor = PanelDark;
        _padPanel.Paint += PadPanel_Paint;

        Controls.Add(top);
        Controls.Add(logPanel);
        Controls.Add(_padPanel);

        // ---------------------------------------------------------- eventos
        _startButton.Click += async (s, e) =>
        {
            _startButton.Enabled = false;
            _modeCombo.Enabled = false;
            try
            {
                await _engine.StartAsync((PadMode)_modeCombo.SelectedItem!);
            }
            catch (Exception ex)
            {
                AppendLog("ERROR: " + ex.Message);
            }
            UpdateUi();
        };

        _stopButton.Click += (s, e) =>
        {
            _engine.StopAll();
            UpdateUi();
        };

        _helpLink.LinkClicked += (s, e) =>
        {
            try
            {
                Process.Start(new ProcessStartInfo("https://vigem.org/downloads/") { UseShellExecute = true });
            }
            catch
            {
                AppendLog("No se pudo abrir el navegador. Descarga ViGEmBus desde https://vigem.org/downloads/");
            }
        };

        _clearLogButton.Click += (s, e) => _logBox.Clear();
        _copyLogButton.Click += (s, e) =>
        {
            if (_logBox.Text.Length > 0)
            {
                Clipboard.SetText(_logBox.Text);
                AppendLog("Registro copiado al portapapeles.");
            }
        };

        _uiTimer.Tick += (s, e) =>
        {
            UpdateStats();
            _padPanel.Invalidate();
        };
        _uiTimer.Start();

        _engine.Log += m => BeginInvoke(new Action(() => AppendLog(m)));
        _engine.StateChanged += () => BeginInvoke(new Action(UpdateUi));

        FormClosing += (s, e) =>
        {
            _uiTimer.Stop();
            _engine.Dispose();
        };

        AppendLog($"Phantom Pad Receiver {AppVersion}. Empareja el teléfono con el PC y pulsa Iniciar.");
        bool driver = VirtualController.DriverInstalled;
        AppendLog(
            driver
                ? "Driver ViGEmBus: detectado (OK)."
                : "Driver ViGEmBus: NO detectado. Instálalo desde https://vigem.org/downloads/ y reinicia el PC.",
            driver ? LogSeverity.Ok : LogSeverity.Error
        );
        if (!driver)
        {
            _helpLink.LinkBehavior = LinkBehavior.HoverUnderline;
        }
        UpdateUi();
    }

    // ------------------------------------------------------------------- UI

    private static void StyleButton(Button b)
    {
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = AccentColor;
        b.FlatAppearance.BorderSize = 1;
        b.FlatAppearance.MouseOverBackColor = ControlHover;
        b.BackColor = ControlOff;
        b.ForeColor = Color.White;
        b.Cursor = Cursors.Hand;
    }

    private void UpdateUi()
    {
        bool connected = _engine.PhoneConnected;
        bool advertising = _engine.Advertising;

        if (connected)
        {
            _pill.SetStatus(StatusPill.State.Connected, "Conectado a " + _engine.PhoneName);
        }
        else if (advertising)
        {
            _pill.SetStatus(StatusPill.State.Waiting, "Esperando teléfono… (Bluetooth visible)");
        }
        else if (_engine.PadError != null)
        {
            _pill.SetStatus(StatusPill.State.Error, "Mando virtual no disponible — reintentando…");
        }
        else
        {
            _pill.SetStatus(StatusPill.State.Idle, "Detenido");
        }

        var busy = advertising;
        _startButton.Enabled = !busy;
        _stopButton.Enabled = busy;
        _modeCombo.Enabled = !busy;
    }

    private void UpdateStats()
    {
        var now = DateTime.UtcNow;
        var dt = (now - _lastFpsAt).TotalSeconds;
        var fps = dt > 0.05 ? (_engine.TotalFrames - _lastFrames) / dt : 0.0;
        _lastFrames = _engine.TotalFrames;
        _lastFpsAt = now;

        string pad;
        if (!_engine.VigemReady)
        {
            pad = _engine.PadError != null ? "no listo (reintentando…)" : "no iniciado";
        }
        else if (_engine.PadFellBack)
        {
            pad = "DualShock 4 (fallback)";
        }
        else if (_engine.VirtualPadIndex >= 0)
        {
            pad = $"Xbox360 #{_engine.VirtualPadIndex}";
        }
        else
        {
            pad = "DualShock 4";
        }

        string driver = VirtualController.DriverInstalled ? "OK" : "NO instalado";
        _infoLabel.Text =
            $"Mando: {pad}    Driver ViGEmBus: {driver}    Teléfono: {_engine.PhoneName}    " +
            $"Frames: {_engine.TotalFrames} ({fps:F0} fps)    Failsafe: {_engine.FailsafeEvents}";
        _infoLabel.ForeColor = _engine.VigemReady ? Color.Gainsboro : WarnAmber;
    }

    private enum LogSeverity { Info, Ok, Warn, Error }

    private void AppendLog(string message) => AppendLog(message, Classify(message));

    private static LogSeverity Classify(string m)
    {
        if (m.Contains("ERROR") || m.Contains("FALLO") || m.Contains("no disponible") ||
            m.Contains("NO detectado") || m.Contains("no respondió") || m.Contains("falló"))
        {
            return LogSeverity.Error;
        }
        if (m.Contains("LISTO") || m.Contains("Conectado") || m.Contains("conectado desde") ||
            m.Contains("publicado") || m.Contains("OK"))
        {
            return LogSeverity.Ok;
        }
        if (m.Contains("Reintento") || m.Contains("reintent") || m.Contains("Esperando") ||
            m.Contains("Sin PONG") || m.Contains("Failsafe") || m.Contains("probando"))
        {
            return LogSeverity.Warn;
        }
        return LogSeverity.Info;
    }

    private void AppendLog(string message, LogSeverity severity)
    {
        Color color = severity switch
        {
            LogSeverity.Ok => OkGreen,
            LogSeverity.Warn => WarnAmber,
            LogSeverity.Error => ErrRed,
            _ => Color.Gainsboro
        };
        _logBox.SelectionStart = _logBox.TextLength;
        _logBox.SelectionLength = 0;
        _logBox.SelectionColor = Color.FromArgb(110, 110, 130);
        _logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] ");
        _logBox.SelectionColor = color;
        _logBox.AppendText(message + Environment.NewLine);
        _logBox.SelectionStart = _logBox.TextLength;
        _logBox.ScrollToCaret();
    }

    // ------------------------------------------------------------- panel test

    private void PadPanel_Paint(object? sender, PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

        var f = _engine.LastFrame;
        int w = _padPanel.ClientSize.Width;
        int h = _padPanel.ClientSize.Height;
        if (w < 50 || h < 50) return;

        DrawFaceButtons(g, f, w, h);
        DrawBumpers(g, f, w, h);
        DrawTriggers(g, f, w, h);
        DrawDpad(g, f, w, h);
        DrawTouchpad(g, f, w, h);
        DrawSticks(g, f, w, h);
    }

    private static void DrawCircleButton(Graphics g, float cx, float cy, float r, bool on, Color color, string? label = null)
    {
        using var fill = new SolidBrush(on ? color : ControlOff);
        using var pen = new Pen(on ? color : Color.FromArgb(70, 70, 90), 2f);
        g.FillEllipse(fill, cx - r, cy - r, r * 2, r * 2);
        g.DrawEllipse(pen, cx - r, cy - r, r * 2, r * 2);
        if (label != null)
        {
            using var text = new SolidBrush(on ? Color.White : Color.Silver);
            using var font = new Font("Segoe UI", Math.Max(8f, r * 0.55f), FontStyle.Bold);
            var size = g.MeasureString(label, font);
            g.DrawString(label, font, text, cx - size.Width / 2, cy - size.Height / 2);
        }
    }

    private void DrawFaceButtons(Graphics g, InputFrame f, int w, int h)
    {
        float r = Math.Min(w, h) * 0.038f;
        foreach (var (button, label, fx, fy, color) in FaceButtons)
        {
            DrawCircleButton(g, w * fx, h * fy, r, f.Buttons.HasFlag(button), color, label);
        }
    }

    private void DrawBumpers(Graphics g, InputFrame f, int w, int h)
    {
        float bw = w * 0.10f, bh = h * 0.055f;
        DrawRounded(g, w * 0.16f - bw / 2, h * 0.09f, bw, bh, f.Buttons.HasFlag(PadButtons.L1), "L1");
        DrawRounded(g, w * 0.84f - bw / 2, h * 0.09f, bw, bh, f.Buttons.HasFlag(PadButtons.R1), "R1");
        DrawRounded(g, w * 0.44f - bw / 2, h * 0.09f, bw, bh, f.Buttons.HasFlag(PadButtons.Share), "SHARE");
        DrawRounded(g, w * 0.56f - bw / 2, h * 0.09f, bw, bh, f.Buttons.HasFlag(PadButtons.Options), "OPTIONS");
        DrawRounded(g, w * 0.50f - h * 0.03f, h * 0.19f, bw, bh, f.Buttons.HasFlag(PadButtons.Ps), "PS");
    }

    private static void DrawRounded(Graphics g, float x, float y, float w, float h, bool on, string label)
    {
        var rect = new RectangleF(x, y, w, h);
        using var path = new System.Drawing.Drawing2D.GraphicsPath();
        float radius = h / 2;
        path.AddArc(x, y, radius, radius, 180, 90);
        path.AddArc(x + w - radius, y, radius, radius, 270, 90);
        path.AddArc(x + w - radius, y + h - radius, radius, radius, 0, 90);
        path.AddArc(x, y + h - radius, radius, radius, 90, 90);
        path.CloseFigure();

        using var fill = new SolidBrush(on ? AccentColor : ControlOff);
        using var pen = new Pen(on ? AccentColor : Color.FromArgb(70, 70, 90), 2f);
        g.FillPath(fill, path);
        g.DrawPath(pen, path);
        using var text = new SolidBrush(on ? Color.White : Color.Silver);
        using var font = new Font("Segoe UI", Math.Max(7f, h * 0.34f), FontStyle.Bold);
        var size = g.MeasureString(label, font);
        g.DrawString(label, font, text, x + w / 2 - size.Width / 2, y + h / 2 - size.Height / 2);
    }

    private void DrawTriggers(Graphics g, InputFrame f, int w, int h)
    {
        float bw = w * 0.035f, bh = h * 0.20f;
        DrawTriggerBar(g, w * 0.07f, h * 0.08f, bw, bh, f.L2, "L2");
        DrawTriggerBar(g, w * 0.93f, h * 0.08f, bw, bh, f.R2, "R2");
    }

    private static void DrawTriggerBar(Graphics g, float cx, float yTop, float bw, float bh, byte value, string label)
    {
        var rect = new RectangleF(cx - bw / 2, yTop, bw, bh);
        using var back = new SolidBrush(ControlOff);
        using var pen = new Pen(Color.FromArgb(70, 70, 90), 2f);
        g.FillRectangle(back, rect);
        float fillH = bh * value / 255f;
        if (fillH > 1)
        {
            using var fill = new SolidBrush(AccentColor);
            g.FillRectangle(fill, cx - bw / 2, yTop + bh - fillH, bw, fillH);
        }
        g.DrawRectangle(pen, cx - bw / 2, yTop, bw, bh);
        using var text = new SolidBrush(Color.Silver);
        using var font = new Font("Segoe UI", 9f, FontStyle.Bold);
        g.DrawString(label, font, text, cx - 12, yTop + bh + 2);
    }

    private void DrawDpad(Graphics g, InputFrame f, int w, int h)
    {
        float cx = w * 0.30f, cy = h * 0.62f;
        float arm = Math.Min(w, h) * 0.055f;
        float th = arm * 0.62f;

        DrawRect(g, cx - th / 2, cy - arm - th / 2, th, arm, f.Buttons.HasFlag(PadButtons.DpadUp));
        DrawRect(g, cx - th / 2, cy + th / 2, th, arm, f.Buttons.HasFlag(PadButtons.DpadDown));
        DrawRect(g, cx - arm - th / 2, cy - th / 2, arm, th, f.Buttons.HasFlag(PadButtons.DpadLeft));
        DrawRect(g, cx + th / 2, cy - th / 2, arm, th, f.Buttons.HasFlag(PadButtons.DpadRight));
        DrawCircleButton(g, cx, cy, th * 0.35f, false, ControlOff);
    }

    private static void DrawRect(Graphics g, float x, float y, float w, float h, bool on)
    {
        using var fill = new SolidBrush(on ? AccentColor : ControlOff);
        g.FillRectangle(fill, x, y, w, h);
    }

    private void DrawTouchpad(Graphics g, InputFrame f, int w, int h)
    {
        float x = w * 0.42f, y = h * 0.27f, tw = w * 0.16f, th = h * 0.12f;
        bool on = f.Buttons.HasFlag(PadButtons.Touchpad);
        using var fill = new SolidBrush(on ? Color.FromArgb(90, 80, 140) : Color.FromArgb(28, 28, 40));
        using var pen = new Pen(on ? AccentColor : Color.FromArgb(70, 70, 90), 2f);
        g.FillRectangle(fill, x, y, tw, th);
        g.DrawRectangle(pen, x, y, tw, th);
        using var text = new SolidBrush(Color.Silver);
        using var font = new Font("Segoe UI", 9f);
        g.DrawString("TOUCHPAD", font, text, x + tw / 2 - 34, y + th / 2 - 8);
    }

    private void DrawSticks(Graphics g, InputFrame f, int w, int h)
    {
        float r = Math.Min(w, h) * 0.075f;
        DrawStick(g, w * 0.30f, h * 0.82f, r, f.LX, f.LY, "L");
        DrawStick(g, w * 0.70f, h * 0.82f, r, f.RX, f.RY, "R");
    }

    private static void DrawStick(Graphics g, float cx, float cy, float r, sbyte x, sbyte y, string label)
    {
        using var ring = new Pen(Color.FromArgb(70, 70, 90), 2f);
        using var ringFill = new SolidBrush(Color.FromArgb(24, 24, 34));
        g.FillEllipse(ringFill, cx - r, cy - r, r * 2, r * 2);
        g.DrawEllipse(ring, cx - r, cy - r, r * 2, r * 2);

        float dx = x / 127f * r * 0.55f;
        float dy = -y / 127f * r * 0.55f; // invertir para que "arriba" se vea arriba
        float dotR = r * 0.38f;
        using var dot = new SolidBrush(AccentColor);
        g.FillEllipse(dot, cx + dx - dotR, cy + dy - dotR, dotR * 2, dotR * 2);

        using var text = new SolidBrush(Color.Silver);
        using var font = new Font("Segoe UI", 10f, FontStyle.Bold);
        g.DrawString(label, font, text, cx - 6, cy - r - 20);
    }

    /// <summary>Píldora de estado: punto de color + texto, estilo Phantom.</summary>
    private sealed class StatusPill : Control
    {
        public enum State { Idle, Waiting, Connected, Error }

        private State _state = State.Idle;
        private string _text = "Detenido";

        private static readonly Color IdleColor = Color.Silver;
        private static readonly Color WaitingColor = Color.FromArgb(255, 183, 77);
        private static readonly Color ConnectedColor = Color.FromArgb(76, 175, 80);
        private static readonly Color ErrorColor = Color.FromArgb(239, 83, 80);
        private static readonly Color PillBack = Color.FromArgb(22, 22, 32);

        public StatusPill()
        {
            DoubleBuffered = true;
            ResizeRedraw = true;
        }

        public void SetStatus(State state, string text)
        {
            if (_state == state && _text == text) return;
            _state = state;
            _text = text;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;

            var color = _state switch
            {
                State.Connected => ConnectedColor,
                State.Waiting => WaitingColor,
                State.Error => ErrorColor,
                _ => IdleColor
            };

            using var back = new SolidBrush(PillBack);
            using var path = new System.Drawing.Drawing2D.GraphicsPath();
            int radius = Height / 2;
            path.AddArc(0, 0, radius, radius, 180, 90);
            path.AddArc(Width - radius, 0, radius, radius, 270, 90);
            path.AddArc(Width - radius, Height - radius, radius, radius, 0, 90);
            path.AddArc(0, Height - radius, radius, radius, 90, 90);
            path.CloseFigure();
            g.FillPath(back, path);
            using var border = new Pen(Color.FromArgb(50, 50, 70), 1f);
            g.DrawPath(border, path);

            // punto con halo
            float dotX = 22, dotY = Height / 2f, dotR = 6;
            using (var halo = new SolidBrush(Color.FromArgb(40, color)))
            {
                g.FillEllipse(halo, dotX - dotR - 4, dotY - dotR - 4, (dotR + 4) * 2, (dotR + 4) * 2);
            }
            using var dot = new SolidBrush(color);
            g.FillEllipse(dot, dotX - dotR, dotY - dotR, dotR * 2, dotR * 2);

            using var text = new SolidBrush(color);
            using var font = new Font("Segoe UI", 11f, FontStyle.Bold);
            var size = g.MeasureString(_text, font);
            g.DrawString(_text, font, text, dotX + dotR + 10, dotY - size.Height / 2);
        }
    }
}
