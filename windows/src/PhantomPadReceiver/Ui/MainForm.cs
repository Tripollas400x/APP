using System.Diagnostics;
using PhantomPadReceiver.Bluetooth;
using PhantomPadReceiver.Core;
using PhantomPadReceiver.Virtual;

namespace PhantomPadReceiver.Ui;

/// <summary>
/// Ventana principal (interfaz oscura estilo Phantom): píldora de estado en
/// vivo, estado del driver ViGEmBus, estadísticas, panel de prueba que dibuja
/// todos los controles en tiempo real y registro con colores por severidad.
/// </summary>
public sealed class MainForm : Form
{
    private const string AppVersion = "v0.3.0";

    private readonly ReceiverEngine _engine = new();

    private readonly ComboBox _modeCombo = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList,
        Width = 142,
        FlatStyle = FlatStyle.Flat,
        BackColor = ReceiverTheme.Raised,
        ForeColor = ReceiverTheme.Text,
        Margin = new Padding(0, 7, 10, 0)
    };

    private readonly Button _startButton = new() { Text = "Iniciar", Width = 102 };
    private readonly Button _stopButton = new() { Text = "Detener", Width = 102 };
    private readonly Button _testButton = new() { Text = "Prueba local (joy.cpl)", Width = 164 };
    private readonly LinkLabel _helpLink = new()
    {
        Text = "ViGEmBus",
        AutoSize = true,
        LinkColor = ReceiverTheme.Text,
        ActiveLinkColor = ReceiverTheme.Text,
        VisitedLinkColor = ReceiverTheme.Muted,
        Margin = new Padding(6, 9, 2, 0)
    };
    private readonly TextBox _infoLabel = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        Multiline = true,
        ScrollBars = ScrollBars.Vertical,
        BackColor = ReceiverTheme.Card,
        ForeColor = ReceiverTheme.Muted,
        BorderStyle = BorderStyle.None,
        Font = new Font("Consolas", 9f)
    };
    private readonly ControllerMonitor _padPanel = new() { Dock = DockStyle.Fill };
    private readonly RichTextBox _logBox = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        BackColor = ReceiverTheme.Background,
        ForeColor = ReceiverTheme.Text,
        BorderStyle = BorderStyle.None,
        Font = new Font("Consolas", 9f),
        DetectUrls = false
    };
    private readonly Button _clearLogButton = new() { Text = "Limpiar", Width = 78 };
    private readonly Button _copyLogButton = new() { Text = "Copiar registro", Width = 113 };
    private readonly Button _saveLogButton = new() { Text = "Guardar registro…", Width = 127 };
    private readonly StatusPill _pill = new() { Dock = DockStyle.Fill, Margin = new Padding(8, 7, 0, 7) };
    private readonly Label _rtt = new();
    private readonly Label _rate = new();
    private readonly Label _failsafe = new();
    private readonly System.Windows.Forms.Timer _uiTimer = new() { Interval = 250 };
    private readonly System.Windows.Forms.Timer _previewTimer = new() { Interval = 33 };

    private ReceiverDiagnostics _lastStats;
    private long _lastFpsAt = Stopwatch.GetTimestamp();
    private bool _stopping;
    private bool _closeAllowed;
    private readonly bool _driverInstalled = VirtualController.DriverInstalled;
    private string? _lastShownServerError;
    private string _technicalDetails = "";

    public MainForm()
    {
        Text = "Phantom Pad Receiver " + AppVersion;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96, 96);
        ClientSize = new Size(1110, 734);
        MinimumSize = new Size(875, 602);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = ReceiverTheme.Background;
        ForeColor = ReceiverTheme.Text;
        Font = new Font("Segoe UI", 10f);
        DoubleBuffered = true;

        _modeCombo.DataSource = Enum.GetValues<PadMode>();
        foreach (var button in new[] { _stopButton, _testButton, _clearLogButton, _copyLogButton, _saveLogButton })
        {
            StyleButton(button, primary: false);
        }
        StyleButton(_startButton, primary: true);
        BuildLayout();

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

        _stopButton.Click += async (s, e) => await StopReceiverAsync();

        // Test A (sin teléfono): barrido por el mando virtual para joy.cpl.
        _testButton.Click += async (s, e) =>
        {
            _testButton.Enabled = false;
            try
            {
                await _engine.RunLocalTestAsync();
            }
            catch (Exception ex)
            {
                AppendLog("ERROR: " + RfcommServer.Detail(ex));
            }
            UpdateUi();
        };

        _helpLink.LinkClicked += (s, e) =>
        {
            try
            {
                Process.Start(new ProcessStartInfo("https://github.com/nefarius/ViGEmBus/releases") { UseShellExecute = true });
            }
            catch
            {
                AppendLog("No se pudo abrir el navegador. Descarga ViGEmBus desde https://github.com/nefarius/ViGEmBus/releases");
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
        _saveLogButton.Click += (s, e) => ExportLog();

        _uiTimer.Tick += (_, _) => UpdateStats();
        _previewTimer.Tick += (_, _) => _padPanel.UpdateFrame(_engine.LastFrame);
        _uiTimer.Start();
        _previewTimer.Start();

        _engine.Log += m => PostToUi(() => AppendLog(m));
        _engine.StateChanged += () => PostToUi(UpdateUi);

        FormClosing += async (s, e) =>
        {
            if (_closeAllowed) return;
            e.Cancel = true;
            _uiTimer.Stop();
            _previewTimer.Stop();
            await StopReceiverAsync();
            _closeAllowed = true;
            Close();
        };

        FormClosed += (_, _) =>
        {
            _uiTimer.Dispose();
            _previewTimer.Dispose();
        };

        AppendLog($"Phantom Pad Receiver {AppVersion}. Empareja el teléfono con el PC y pulsa Iniciar.");
        bool driver = _driverInstalled;
        AppendLog(
            driver
                ? "Driver ViGEmBus: detectado (OK)."
                : "Driver ViGEmBus: NO detectado. Instálalo desde https://github.com/nefarius/ViGEmBus/releases y reinicia el PC.",
            driver ? LogSeverity.Ok : LogSeverity.Error
        );
        if (!driver)
        {
            _helpLink.LinkBehavior = LinkBehavior.HoverUnderline;
        }
        UpdateUi();
        UpdateStats();
    }

    // ------------------------------------------------------------------- UI

    private void BuildLayout()
    {
        var app = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, Padding = new Padding(19, 11, 19, 14),
            ColumnCount = 1, RowCount = 4
        };
        app.RowStyles.Add(new RowStyle(SizeType.Absolute, 75));
        app.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));
        app.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        app.RowStyles.Add(new RowStyle(SizeType.Absolute, 162));

        var heading = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, Margin = Padding.Empty };
        heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 66));
        heading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 34));
        var title = new Panel { Dock = DockStyle.Fill, Margin = Padding.Empty };
        title.Controls.Add(Heading("Phantom Pad", 24f, new Point(0, 0)));
        title.Controls.Add(new Label
        {
            Text = "Receptor para Windows  ·  " + AppVersion,
            Location = new Point(3, 41), AutoSize = true, ForeColor = ReceiverTheme.Muted
        });
        heading.Controls.Add(title, 0, 0);
        heading.Controls.Add(_pill, 1, 0);
        app.Controls.Add(heading, 0, 0);

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, WrapContents = false, Margin = Padding.Empty, Padding = new Padding(0, 2, 0, 0)
        };
        toolbar.Controls.Add(new Label
        {
            Text = "Modo", AutoSize = true, ForeColor = ReceiverTheme.Muted, Margin = new Padding(0, 10, 8, 0)
        });
        toolbar.Controls.AddRange([_modeCombo, _startButton, _stopButton, _testButton, _helpLink]);
        app.Controls.Add(toolbar, 0, 1);

        var dashboard = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 1, Margin = Padding.Empty };
        dashboard.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 60));
        dashboard.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));
        var gamepad = Section(new Padding(0, 0, 6, 8), "Mando en vivo");
        gamepad.Controls.Add(_padPanel, 0, 1);
        dashboard.Controls.Add(gamepad, 0, 0);

        var diagnosis = Section(new Padding(6, 0, 0, 8), "Diagnóstico");
        var stats = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 2, Margin = Padding.Empty };
        stats.RowStyles.Add(new RowStyle(SizeType.Absolute, 81));
        stats.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var meters = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1, Margin = Padding.Empty };
        for (int i = 0; i < 3; i++) meters.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f / 3));
        meters.Controls.Add(Metric("RTT", _rtt), 0, 0);
        meters.Controls.Add(Metric("Entradas/s", _rate), 1, 0);
        meters.Controls.Add(Metric("Failsafe", _failsafe), 2, 0);
        stats.Controls.Add(meters, 0, 0);
        stats.Controls.Add(_infoLabel, 0, 1);
        diagnosis.Controls.Add(stats, 0, 1);
        dashboard.Controls.Add(diagnosis, 1, 0);
        app.Controls.Add(dashboard, 0, 2);

        var log = Section(Padding.Empty);
        var logBar = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Margin = Padding.Empty };
        logBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        logBar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var caption = Heading("Registro", 12f, Point.Empty);
        caption.Anchor = AnchorStyles.Left;
        logBar.Controls.Add(caption, 0, 0);
        var actions = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        actions.Controls.AddRange([_clearLogButton, _copyLogButton, _saveLogButton]);
        logBar.Controls.Add(actions, 1, 0);
        log.Controls.Add(logBar, 0, 0);
        var logBody = new Panel { Dock = DockStyle.Fill, Padding = new Padding(8, 5, 8, 5), BackColor = ReceiverTheme.Background };
        logBody.Controls.Add(_logBox);
        log.Controls.Add(logBody, 0, 1);
        app.Controls.Add(log, 0, 3);

        Controls.Add(app);
    }

    private static TableLayoutPanel Section(Padding margin, string? name = null)
    {
        var section = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, BackColor = ReceiverTheme.Card, ColumnCount = 1,
            RowCount = 2, Margin = margin, Padding = new Padding(12, 8, 12, 8)
        };
        section.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        section.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        if (name != null)
        {
            var heading = Heading(name, 12f, Point.Empty);
            heading.Anchor = AnchorStyles.Left;
            section.Controls.Add(heading, 0, 0);
        }
        return section;
    }

    private static Label Heading(string text, float points, Point location) => new()
    {
        Text = text, AutoSize = true, Font = new Font("Segoe UI Semibold", points),
        ForeColor = ReceiverTheme.Text, Location = location
    };

    private static Control Metric(string title, Label value)
    {
        var card = new Panel { Dock = DockStyle.Fill, Margin = new Padding(0, 0, 6, 9), BackColor = ReceiverTheme.Raised };
        var label = new Label { Text = title, ForeColor = ReceiverTheme.Muted, Location = new Point(9, 7), AutoSize = true };
        value.ForeColor = ReceiverTheme.Text;
        value.Font = new Font("Segoe UI Semibold", 15f);
        value.Location = new Point(8, 27);
        value.AutoSize = true;
        card.Controls.AddRange([label, value]);
        return card;
    }

    private static void StyleButton(Button button, bool primary)
    {
        button.Height = 33;
        button.Margin = new Padding(0, 2, 7, 2);
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderColor = ReceiverTheme.Border;
        button.FlatAppearance.BorderSize = 1;
        button.FlatAppearance.MouseOverBackColor = primary ? Color.White : ReceiverTheme.Border;
        button.BackColor = primary ? ReceiverTheme.Text : ReceiverTheme.Raised;
        button.ForeColor = primary ? ReceiverTheme.Background : ReceiverTheme.Text;
        button.UseVisualStyleBackColor = false;
        button.Cursor = Cursors.Hand;
        button.EnabledChanged += (_, _) => button.BackColor =
            button.Enabled && primary ? ReceiverTheme.Text : ReceiverTheme.Raised;
    }

    private void UpdateUi()
    {
        bool connected = _engine.PhoneConnected;
        bool advertising = _engine.Advertising;

        if (connected)
        {
            _pill.SetStatus(StatusPill.State.Connected, "Conectado a " + _engine.PhoneName);
        }
        else if (advertising && _engine.PadError != null)
        {
            _pill.SetStatus(StatusPill.State.Error, "Esperando teléfono… (mando virtual reintentando)");
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
            _pill.SetStatus(_engine.Running ? StatusPill.State.Waiting : StatusPill.State.Idle,
                _engine.Running ? "Publicando RFCOMM…" : "Detenido");
        }

        var busy = _engine.Running || _stopping;
        _startButton.Enabled = !busy;
        _stopButton.Enabled = _engine.Running && !_stopping;
        _modeCombo.Enabled = !busy;
        _testButton.Enabled = !_stopping && _engine.CanTest;
    }

    private async Task StopReceiverAsync()
    {
        _stopping = true;
        UpdateUi();
        try { await _engine.StopAsync(); }
        catch (Exception ex) { AppendLog("ERROR al detener: " + RfcommServer.Detail(ex)); }
        finally
        {
            _stopping = false;
            UpdateUi();
        }
    }

    private void PostToUi(Action action)
    {
        if (!IsHandleCreated || IsDisposed || _closeAllowed) return;
        try { BeginInvoke(action); }
        catch (InvalidOperationException) { }
    }

    /// <summary>Exporta el registro a un .log sin datos privados (solo eventos).</summary>
    private void ExportLog()
    {
        using var dialog = new SaveFileDialog
        {
            Filter = "Registro de texto (*.log)|*.log|Texto (*.txt)|*.txt",
            FileName = $"PhantomPadReceiver_{DateTime.Now:yyyyMMdd_HHmmss}.log"
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var header =
                "Phantom Pad Receiver " + AppVersion + "\n" +
                "Fecha: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "\n" +
                "Driver ViGEmBus: " + (VirtualController.DriverInstalled ? "instalado" : "NO instalado") + "\n" +
                "Modo: " + _engine.Mode + "\n" +
                new string('-', 60) + "\n";
            File.WriteAllText(dialog.FileName, header + _technicalDetails + Environment.NewLine + _logBox.Text);
            AppendLog("Registro guardado en " + dialog.FileName);
        }
        catch (Exception ex)
        {
            AppendLog("ERROR: no se pudo guardar el registro: " + ex.Message);
        }
    }

    private void UpdateStats()
    {
        var now = Stopwatch.GetTimestamp();
        var stats = _engine.Diagnostics;
        var dt = Stopwatch.GetElapsedTime(_lastFpsAt, now).TotalSeconds;
        bool sameSession = stats.SessionId != 0 && _lastStats.SessionId == stats.SessionId && dt > 0;
        double fps = sameSession ? (stats.Inputs - _lastStats.Inputs) / dt : 0;
        double bytesPerSecond = sameSession ? (stats.Bytes - _lastStats.Bytes) / dt : 0;
        _lastStats = stats;
        _lastFpsAt = now;

        string padState = stats.PadState switch
        {
            ControllerState.InputReady => "entrada comprobada",
            ControllerState.BusConnected => "registrado, pendiente de comprobar entrada",
            ControllerState.WindowsEnumerating => "índice pendiente / enumerando",
            ControllerState.Degraded => "degradado",
            ControllerState.Failed => "error, reintentando",
            _ => _engine.Running ? "iniciando" : "detenido"
        };
        string bt = _engine.ServerError != null ? "ERROR" : _engine.Advertising ? "publicado" : "detenido";
        string index = stats.RawIndex < 0 ? "pendiente" : stats.RawIndex == 255 ? "sin asignar (255)" : stats.RawIndex.ToString();
        string age = stats.SessionId != 0 && stats.InputAgeMs is double last ? $"{last:F0} ms" : "—";
        string rtt = stats.SessionId != 0 && stats.RttMs is double rttMs ? $"{rttMs:F1} ms" : "—";
        _rtt.Text = rtt;
        _rate.Text = sameSession ? $"{fps:F0}" : "—";
        _failsafe.Text = stats.Failsafes.ToString();
        _failsafe.ForeColor = stats.LinkDegraded ? ReceiverTheme.Warning : ReceiverTheme.Text;

        // Actualizar sin perder la selección ni el scroll del usuario.
        var details = string.Join(Environment.NewLine,
            $"Bluetooth      {bt} · sesión #{stats.SessionId} ({(stats.SessionId == 0 ? 0 : 1)} activa)",
            $"RTT RFCOMM     {rtt}",
            $"ViGEmBus       {(_driverInstalled ? "instalado" : "ausente al iniciar")}",
            $"Mando          {stats.PadIdentity}",
            $"Estado         {padState}",
            $"Índice bruto   {index}",
            $"XInput         {stats.XInput}",
            $"INPUT          {stats.Inputs} · {fps:F0}/s · {bytesPerSecond:F0} B/s",
            $"Último INPUT   {age} · failsafe {stats.Failsafes}",
            $"Tramas         {stats.Frames} · CRC {stats.CrcErrors} · desconocidas {stats.UnknownFrames}",
            $"Lectura        {stats.ReadBytes} B / {stats.ReadFrames} tramas",
            $"Pausa          {stats.ReadGapMs:F1} ms · proceso {stats.ProcessingMs:F1} ms",
            $"Entre INPUT    {stats.InputGapMs:F1} / máx {stats.MaximumInputGapMs:F1} ms",
            $"Parser         {stats.DroppedBytes} B descartados · {stats.PendingBytes} B pendientes",
            $"Saltos INPUT   {stats.MissingInputs} · tardíos {stats.LateInputs}");
        _technicalDetails = details;
        if (_infoLabel.Text != details && !_infoLabel.Focused) _infoLabel.Text = details;
        _infoLabel.ForeColor = stats.LinkDegraded || stats.Error != null ? ReceiverTheme.Warning : ReceiverTheme.Muted;
        // Muestra el error del servidor completo una sola vez (no en cada tick).
        if (_engine.ServerError != null && _engine.ServerError != _lastShownServerError)
        {
            _lastShownServerError = _engine.ServerError;
            AppendLog("BT: " + _engine.ServerError, LogSeverity.Error);
        }
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
            LogSeverity.Ok => ReceiverTheme.Success,
            LogSeverity.Warn => ReceiverTheme.Warning,
            LogSeverity.Error => ReceiverTheme.Error,
            _ => ReceiverTheme.Text
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

    private sealed class StatusPill : Control
    {
        public enum State { Idle, Waiting, Connected, Error }

        private State _state = State.Idle;
        private string _text = "Detenido";

        public StatusPill()
        {
            DoubleBuffered = true;
            ResizeRedraw = true;
            AccessibleName = _text;
        }

        public void SetStatus(State state, string text)
        {
            if (_state == state && _text == text) return;
            _state = state;
            _text = text;
            AccessibleName = text;
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var color = _state switch
            {
                State.Connected => ReceiverTheme.Success,
                State.Waiting => ReceiverTheme.Warning,
                State.Error => ReceiverTheme.Error,
                _ => ReceiverTheme.Muted
            };
            var rect = new Rectangle(0, 0, Width - 1, Height - 1);
            if (rect.Width < 10 || rect.Height < 10) return;
            var graphics = e.Graphics;
            graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var path = ReceiverTheme.Rounded(rect, 12);
            using var back = new SolidBrush(ReceiverTheme.Card);
            using var border = new Pen(ReceiverTheme.Border);
            using var dot = new SolidBrush(color);
            graphics.FillPath(back, path);
            graphics.DrawPath(border, path);
            graphics.FillEllipse(dot, 14, rect.Height / 2f - 4, 8, 8);
            TextRenderer.DrawText(graphics, _text, Font, new Rectangle(30, 0, Math.Max(1, Width - 40), Height),
                color, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        }
    }
}
