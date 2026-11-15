namespace PhantomPadReceiver;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        using var instance = new Mutex(true, @"Local\PhantomPadReceiver", out bool owner);
        if (!owner)
        {
            MessageBox.Show("Ya hay un receptor Phantom Pad abierto en esta sesión de Windows.");
            return;
        }
        Application.Run(new Ui.MainForm());
    }
}
