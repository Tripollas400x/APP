using System.Drawing.Drawing2D;
using System.Drawing.Text;
using PhantomPadReceiver.Protocol;

namespace PhantomPadReceiver.Ui;

/// <summary>Vista de los controles recibidos, incluyendo el barrido de prueba local.</summary>
internal sealed class ControllerMonitor : Control
{
    private const float CanvasWidth = 720;
    private const float CanvasHeight = 328;
    private const byte TouchContactFlag = 0x02;
    private static readonly Color Direction = Color.FromArgb(145, 191, 255);
    private static readonly (PadButtons Id, PointF At, Color Color)[] Symbols =
    [
        (PadButtons.Triangle, new(559, 115), Color.FromArgb(105, 224, 174)),
        (PadButtons.Circle, new(597, 153), Color.FromArgb(255, 131, 137)),
        (PadButtons.Cross, new(559, 191), Color.FromArgb(133, 183, 255)),
        (PadButtons.Square, new(521, 153), Color.FromArgb(242, 160, 212))
    ];
    private static readonly PadButtons[] Directions =
    [
        PadButtons.DpadUp, PadButtons.DpadRight, PadButtons.DpadDown, PadButtons.DpadLeft
    ];
    private InputFrame _input;

    public ControllerMonitor()
    {
        DoubleBuffered = true;
        ResizeRedraw = true;
        BackColor = ReceiverTheme.Card;
        AccessibleName = "Vista del mando";
        TabStop = false;
    }

    public void UpdateFrame(InputFrame next)
    {
        if (_input.Buttons == next.Buttons && _input.Flags == next.Flags &&
            _input.LX == next.LX && _input.LY == next.LY &&
            _input.RX == next.RX && _input.RY == next.RY &&
            _input.L2 == next.L2 && _input.R2 == next.R2)
        {
            return;
        }

        _input = next;
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (ClientSize.Width < 20 || ClientSize.Height < 20) return;

        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        float scale = Math.Min(ClientSize.Width / CanvasWidth, ClientSize.Height / CanvasHeight);
        var graphicsState = g.Save();
        g.TranslateTransform((ClientSize.Width - CanvasWidth * scale) / 2f,
            (ClientSize.Height - CanvasHeight * scale) / 2f);
        g.ScaleTransform(scale, scale);

        DrawShell(g);
        Trigger(g, new RectangleF(87, 14, 127, 31), _input.L2, "L2");
        Trigger(g, new RectangleF(506, 14, 127, 31), _input.R2, "R2");
        Key(g, new RectangleF(91, 50, 119, 26), PadButtons.L1, "L1");
        Key(g, new RectangleF(510, 50, 119, 26), PadButtons.R1, "R1");
        Key(g, new RectangleF(218, 80, 55, 22), PadButtons.Share, "SHARE");
        Key(g, new RectangleF(447, 80, 55, 22), PadButtons.Options, "OPTIONS");
        DrawTouchpad(g);
        DrawDpad(g);
        foreach (var symbol in Symbols) DrawSymbol(g, symbol.Id, symbol.At, symbol.Color);
        Stick(g, new PointF(280, 211), _input.LX, _input.LY, PadButtons.L3);
        Stick(g, new PointF(440, 211), _input.RX, _input.RY, PadButtons.R3);
        Key(g, new RectangleF(341, 224, 38, 30), PadButtons.Ps, "PS");

        g.Restore(graphicsState);
    }

    private static void DrawShell(Graphics g)
    {
        using var shell = new GraphicsPath();
        shell.StartFigure();
        shell.AddBezier(133, 66, 195, 57, 235, 85, 287, 88);
        shell.AddLine(287, 88, 433, 88);
        shell.AddBezier(433, 88, 485, 85, 525, 57, 587, 66);
        shell.AddBezier(587, 66, 649, 73, 675, 134, 686, 239);
        shell.AddBezier(686, 239, 690, 301, 657, 320, 614, 297);
        shell.AddBezier(614, 297, 569, 270, 553, 265, 503, 264);
        shell.AddLine(503, 264, 217, 264);
        shell.AddBezier(217, 264, 167, 265, 151, 270, 106, 297);
        shell.AddBezier(106, 297, 63, 320, 30, 301, 34, 239);
        shell.AddBezier(34, 239, 45, 134, 71, 73, 133, 66);
        shell.CloseFigure();
        using var body = new LinearGradientBrush(shell.GetBounds(), Color.FromArgb(250, 250, 251),
            Color.FromArgb(143, 143, 151), LinearGradientMode.Vertical);
        g.FillPath(body, shell);
        using var line = new Pen(Color.FromArgb(206, 206, 211), 1.8f);
        g.DrawPath(line, shell);

        using var centre = new GraphicsPath();
        centre.AddBezier(258, 82, 313, 66, 405, 66, 462, 82);
        centre.AddBezier(462, 82, 496, 113, 515, 227, 476, 261);
        centre.AddBezier(476, 261, 444, 280, 276, 280, 244, 261);
        centre.AddBezier(244, 261, 205, 227, 224, 113, 258, 82);
        centre.CloseFigure();
        using var core = new LinearGradientBrush(centre.GetBounds(), Color.FromArgb(51, 51, 54),
            Color.FromArgb(16, 16, 17), LinearGradientMode.Vertical);
        g.FillPath(core, centre);
    }

    private bool IsDown(PadButtons button) => (_input.Buttons & button) != 0;

    private void DrawTouchpad(Graphics g)
    {
        var bounds = new RectangleF(284, 101, 152, 53);
        bool on = IsDown(PadButtons.Touchpad) || (_input.Flags & TouchContactFlag) != 0;
        Capsule(g, bounds, 12, Color.FromArgb(35, 35, 37), on ? Direction : ReceiverTheme.Border);
        using var texture = new SolidBrush(on ? Direction : Color.FromArgb(87, 87, 92));
        for (int row = 0; row < 4; row++)
        {
            for (int column = 0; column < 18; column++)
            {
                g.FillEllipse(texture, bounds.X + 10 + column * 7.55f, bounds.Y + 10 + row * 10, 1.6f, 1.6f);
            }
        }
        var indicator = new RectangleF(337, 164, 46, 4);
        Capsule(g, indicator, 2, on ? Direction : Color.FromArgb(126, 136, 151),
            on ? Direction : Color.FromArgb(126, 136, 151));
    }

    private void DrawDpad(Graphics g)
    {
        var center = new PointF(161, 153);
        Round(g, center, 57, Color.FromArgb(42, 42, 45), ReceiverTheme.Border);
        for (int i = 0; i < Directions.Length; i++)
        {
            var state = g.Save();
            g.TranslateTransform(center.X, center.Y);
            g.RotateTransform(i * 90f);
            bool on = IsDown(Directions[i]);
            Capsule(g, new RectangleF(-16, -49, 32, 31), 7,
                on ? Color.FromArgb(49, 61, 78) : ReceiverTheme.Raised, on ? Direction : ReceiverTheme.Border);
            using var arrow = new SolidBrush(on ? Direction : Color.FromArgb(160, 184, 218));
            g.FillPolygon(arrow, [new PointF(0, -42), new PointF(7, -31), new PointF(-7, -31)]);
            g.Restore(state);
        }
    }

    private void DrawSymbol(Graphics g, PadButtons id, PointF at, Color mark)
    {
        bool on = IsDown(id);
        var dark = Color.FromArgb(29, 29, 31);
        Round(g, new PointF(at.X, at.Y + 3), 21, Color.FromArgb(99, 99, 105), Color.FromArgb(99, 99, 105));
        Round(g, at, 20, on ? Color.FromArgb(64, 64, 69) : dark, on ? mark : ReceiverTheme.Border);
        using var pen = new Pen(mark, on ? 3.6f : 2.5f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        float x = at.X;
        float y = at.Y;
        switch (id)
        {
            case PadButtons.Triangle:
                g.DrawPolygon(pen, [new PointF(x, y - 8), new PointF(x + 8, y + 6), new PointF(x - 8, y + 6)]);
                break;
            case PadButtons.Circle:
                g.DrawEllipse(pen, x - 7, y - 7, 14, 14);
                break;
            case PadButtons.Cross:
                g.DrawLine(pen, x - 6, y - 6, x + 6, y + 6);
                g.DrawLine(pen, x - 6, y + 6, x + 6, y - 6);
                break;
            case PadButtons.Square:
                g.DrawRectangle(pen, x - 6.5f, y - 6.5f, 13, 13);
                break;
        }
    }

    private void Stick(Graphics g, PointF at, sbyte x, sbyte y, PadButtons click)
    {
        bool on = IsDown(click);
        Round(g, at, 43, Color.FromArgb(17, 17, 18), ReceiverTheme.Border);
        Round(g, at, 35, Color.FromArgb(39, 39, 41), Color.FromArgb(96, 96, 103));
        var knob = new PointF(at.X + Math.Clamp(x / 127f, -1f, 1f) * 17,
            at.Y + Math.Clamp(y / 127f, -1f, 1f) * 17);
        Round(g, knob, 23, Color.FromArgb(64, 64, 68), on ? Direction : ReceiverTheme.Text);
        Round(g, knob, 17.5f, Color.FromArgb(48, 48, 52), Color.FromArgb(88, 88, 92));
        Caption(g, click.ToString(), new RectangleF(at.X - 20, at.Y - 59, 40, 18),
            on ? Direction : ReceiverTheme.Muted, 9);
    }

    private void Key(Graphics g, RectangleF bounds, PadButtons button, string text)
    {
        bool on = IsDown(button);
        Capsule(g, bounds, 8, on ? ReceiverTheme.Text : ReceiverTheme.Raised,
            on ? ReceiverTheme.Text : ReceiverTheme.Border);
        Caption(g, text, bounds, on ? ReceiverTheme.Background : ReceiverTheme.Text, 9);
    }

    private static void Trigger(Graphics g, RectangleF bounds, byte value, string text)
    {
        Capsule(g, bounds, 9, ReceiverTheme.Raised, value > 0 ? Direction : ReceiverTheme.Border);
        var bar = new RectangleF(bounds.X + 12, bounds.Bottom - 7, bounds.Width - 24, 3);
        Capsule(g, bar, 1.5f, ReceiverTheme.Border, ReceiverTheme.Border);
        bar.Width *= value / 255f;
        if (bar.Width > 1) Capsule(g, bar, 1.5f, Direction, Direction);
        Caption(g, text, new RectangleF(bounds.X, bounds.Y, bounds.Width, bounds.Height - 4), ReceiverTheme.Text, 9);
    }

    private static void Caption(Graphics g, string text, RectangleF bounds, Color color, float size)
    {
        using var font = new Font("Segoe UI Semibold", size);
        using var brush = new SolidBrush(color);
        using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        g.DrawString(text, font, brush, bounds, format);
    }

    private static void Capsule(Graphics g, RectangleF area, float corner, Color color, Color outline)
    {
        using var shape = ReceiverTheme.Rounded(area, corner);
        using var fill = new SolidBrush(color);
        using var line = new Pen(outline, 1.4f);
        g.FillPath(fill, shape);
        g.DrawPath(line, shape);
    }

    private static void Round(Graphics g, PointF center, float radius, Color color, Color outline)
    {
        var bounds = new RectangleF(center.X - radius, center.Y - radius, radius * 2, radius * 2);
        using var fill = new SolidBrush(color);
        using var line = new Pen(outline, 1.6f);
        g.FillEllipse(fill, bounds);
        g.DrawEllipse(line, bounds);
    }
}
