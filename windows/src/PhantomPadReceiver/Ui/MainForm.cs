using System.Diagnostics;
using PhantomPadReceiver.Core;
using PhantomPadReceiver.Protocol;
using PhantomPadReceiver.Virtual;

namespace PhantomPadReceiver.Ui;

/// <summary>
/// Ventana principal: estado de conexión, modo del mando, estadísticas y un
/// panel de prueba que dibuja en vivo todos los botones, sticks y gatillos.
/// </summary>
public sealed class MainForm : Form
{
    private readonly ReceiverEngine _engine = new();

    private readonly ComboBox _modeCombo = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList,
        Width = 150
    };

    private readonly Button _startButton = new() { Text = "Iniciar", Width = 90 };
    private readonly Button _stopButton = new() { Text = "Detener", Width = 90, Enabled = false };
    private readonly LinkLabel _helpLink = new() { Text = "Guía de instalación (driver ViGEmBus)", AutoSize = true };
    private readonly Label _statusLabel = new()
    {
        AutoSize = true,
        Font = new Font("Segoe UI", 11f, FontStyle.Bold)
    };
    private readonly Label _statsLabel = new() { AutoSize = true };
    private readonly Panel _padPanel = new() { Dock = DockStyle.Fill };
    private readonly TextBox _logBox = new()
    {
        Dock = DockStyle.Bottom,
        Height = 150,
        Multiline = true,
        ReadOnly = true,
        ScrollBars = ScrollBars.Vertical,
        BackColor = Color.FromArgb(16, 16, 22),
        ForeColor = Color.Gainsboro,
        Font = new Font("Consolas", 9f)
    };
    private readonly System.Windows.Forms.Timer _uiTimer = new() { Interval = 250 };

    private long _lastFrames;
    private DateTime _lastFpsAt = DateTime.UtcNow;

    private static readonly Color AccentColor = Color.FromArgb(158, 123, 255);
    private static readonly Color PanelDark = Color.FromArgb(10, 10, 14);
    private static readonly Color ControlOff = Color.FromArgb(40, 40, 56);
    private static readonly Color ControlOn = Color.FromArgb(76, 175, 80);

    private static readonly (PadButtons Button, string Label, float Fx, float Fy, Color Color)[] FaceButtons =
    {
        (PadButtons.Triangle, "Y", 0.78f, 0.42f, Color.FromArgb(0, 168, 107)),
        (PadButtons.Circle,   "B", 0.86f, 0.55f, Color.FromArgb(244, 67, 54)),
        (PadButtons.Cross,    "A", 0.78f, 0.68f, Color.FromArgb(33, 150, 243)),
        (PadButtons.Square,   "X", 0.70f, 0.55f, Color.FromArgb(232, 121, 249))
    };

    public MainForm()
    {
        Text = "Phantom Pad Receiver";
        Size = new Size(960, 700);
        MinimumSize = new Size(840, 580);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = PanelDark;
        Font = new Font("Segoe UI", 9.5f);
        DoubleBuffered = true;

        _modeCombo.DataSource = Enum.GetValues<PadMode>();

        // ---- cabecera
        var top = new Panel { Dock = DockStyle.Top, Height = 150, BackColor = Color.FromArgb(14, 14, 20) };
        var title = new Label
        {
            Text = "PHANTOM PAD RECEIVER",
            AutoSize = true,
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
            ForeColor = AccentColor,
            Location = new Point(16, 10)
        };
        var modeLabel = new Label { Text = "Modo del mando virtual:", AutoSize = true, Location = new Point(16, 52) };
        _modeCombo.Location = new Point(170, 48);
        _startButton.Location = new Point(340, 46);
        _stopButton.Location = new Point(438, 46);
        _helpLink.Location = new Point(545, 51);
        _statusLabel.Location = new Point(16, 86);
        _statsLabel.Location = new Point(16, 116);
        _statsLabel.ForeColor = Color.Gainsboro;

        top.Controls.Add(title);
        top.Controls.Add(modeLabel);
        top.Controls.Add(_modeCombo);
        top.Controls.Add(_startButton);
        top.Controls.Add(_stopButton);
        top.Controls.Add(_helpLink);
        top.Controls.Add(_statusLabel);
        top.Controls.Add(_statsLabel);

        _padPanel.BackColor = PanelDark;
        _padPanel.Paint += PadPanel_Paint;

        Controls.Add(top);
        Controls.Add(_logBox);
        Controls.Add(_padPanel);

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

        AppendLog("Phantom Pad Receiver v0.1. Empareja el teléfono con el PC y pulsa Iniciar.");
        UpdateUi();
    }

    private void UpdateUi()
    {
        _statusLabel.Text = _engine.PhoneConnected
            ? "Conectado"
            : _engine.Advertising ? "Esperando teléfono..." : "Detenido";
        _statusLabel.ForeColor = _engine.PhoneConnected
            ? ControlOn
            : _engine.Advertising ? Color.FromArgb(255, 183, 77) : Color.Silver;

        var busy = _engine.Advertising;
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

        string padState = !_engine.VigemReady ? "NO (instala ViGEmBus)"
            : _engine.VirtualPadIndex >= 0 ? $"#{_engine.VirtualPadIndex}"
            : "DS4"; // la librería ViGEm no expone el índice en modo DualShock 4
        _statsLabel.Text =
            $"Dispositivo: {_engine.PhoneName}    Modo: {_engine.Mode}    Mando virtual: {padState}    " +
            $"Frames: {_engine.TotalFrames}    {fps:F0} fps    Failsafe: {_engine.FailsafeEvents}";
    }

    private void AppendLog(string message)
    {
        _logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
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
        DrawRounded(g, w * 0.50f - bw / 2, h * 0.19f, bw, bh, f.Buttons.HasFlag(PadButtons.Ps), "PS");
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
}
