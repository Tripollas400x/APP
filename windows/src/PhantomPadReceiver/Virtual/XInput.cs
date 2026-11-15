using System.Runtime.InteropServices;

namespace PhantomPadReceiver.Virtual;

[StructLayout(LayoutKind.Sequential)]
internal readonly record struct XInputReport(
    ushort Buttons, byte L2, byte R2, short LX, short LY, short RX, short RY)
{
    // XInputGetState does not expose the Guide button.
    public XInputReport WithoutGuide() => this with { Buttons = (ushort)(Buttons & ~0x0400) };
}

internal readonly record struct XInputSlot(uint Result, XInputReport Report)
{
    public bool Connected => Result == 0;
}

internal static class XInput
{
    public const int SlotCount = 4;
    public static bool IsPlayerIndex(int index) => index is >= 0 and < SlotCount;

    [StructLayout(LayoutKind.Sequential)]
    private struct State
    {
        public uint PacketNumber;
        public XInputReport Gamepad;
    }

    [DllImport("xinput1_4.dll", ExactSpelling = true)]
    private static extern uint XInputGetState(uint index, out State state);

    public static XInputSlot Read(int index)
    {
        uint result = XInputGetState((uint)index, out var state);
        return new(result, state.Gamepad);
    }

    public static XInputSlot[] Scan()
    {
        var slots = new XInputSlot[SlotCount];
        for (int i = 0; i < slots.Length; i++) slots[i] = Read(i);
        return slots;
    }
}

/// <summary>Correlates distinct, intentional local reports with one Windows slot.</summary>
internal sealed class XInputCorrelation
{
    private readonly HashSet<XInputReport> _reports = new();
    private int _candidates = (1 << XInput.SlotCount) - 1;

    public void Observe(XInputReport report, XInputSlot[] slots)
    {
        if (report == default || !_reports.Add(report)) return;
        for (int i = 0; i < slots.Length; i++)
        {
            if (!slots[i].Connected || slots[i].Report.WithoutGuide() != report)
                _candidates &= ~(1 << i);
        }
    }

    public int VerifiedIndex
    {
        get
        {
            if (_reports.Count < 3) return -1;
            for (int i = 0; i < XInput.SlotCount; i++)
                if (_candidates == 1 << i) return i;
            return -1;
        }
    }
}
