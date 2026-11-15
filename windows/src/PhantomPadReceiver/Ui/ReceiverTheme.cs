using System.Drawing.Drawing2D;

namespace PhantomPadReceiver.Ui;

internal static class ReceiverTheme
{
    public static readonly Color Background = Color.FromArgb(13, 13, 14);
    public static readonly Color Card = Color.FromArgb(23, 23, 25);
    public static readonly Color Raised = Color.FromArgb(33, 33, 36);
    public static readonly Color Border = Color.FromArgb(62, 62, 66);
    public static readonly Color Text = Color.FromArgb(245, 245, 245);
    public static readonly Color Muted = Color.FromArgb(181, 181, 187);
    public static readonly Color Success = Color.FromArgb(117, 215, 172);
    public static readonly Color Warning = Color.FromArgb(241, 194, 125);
    public static readonly Color Error = Color.FromArgb(255, 141, 148);

    public static GraphicsPath Rounded(RectangleF bounds, float radius)
    {
        var path = new GraphicsPath();
        float arc = Math.Min(radius * 2f, Math.Min(bounds.Width, bounds.Height));
        path.AddArc(bounds.X, bounds.Y, arc, arc, 180, 90);
        path.AddArc(bounds.Right - arc, bounds.Y, arc, arc, 270, 90);
        path.AddArc(bounds.Right - arc, bounds.Bottom - arc, arc, arc, 0, 90);
        path.AddArc(bounds.X, bounds.Bottom - arc, arc, arc, 90, 90);
        path.CloseFigure();
        return path;
    }
}
