using System.Diagnostics;
using System.Runtime.InteropServices;
using PhantomPadReceiver.Core;
using PhantomPadReceiver.Virtual;
using Xunit;

namespace PhantomPadReceiver.Tests;

public class RecoveryTests
{
    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, true)]
    [InlineData(3, true)]
    [InlineData(4, false)]
    [InlineData(255, false)]
    public void Only_windows_player_slots_are_valid(int index, bool valid) =>
        Assert.Equal(valid, XInput.IsPlayerIndex(index));

    [Fact]
    public void XInput_report_matches_native_layout() =>
        Assert.Equal(12, Marshal.SizeOf<XInputReport>());

    [Fact]
    public void Multiple_sequential_reports_identify_one_slot_among_existing_controllers()
    {
        var correlation = new XInputCorrelation();
        for (short t = 1; t <= 3; t++)
        {
            var report = new XInputReport(0, 32, 100, t, (short)-t, 13, -17);
            var other = t == 1 ? report : default;
            correlation.Observe(report, [new(0, other), new(1167, default), new(0, report), new(1167, default)]);
        }
        Assert.Equal(2, correlation.VerifiedIndex);
    }

    [Fact]
    public void Slot_can_be_verified_after_enumeration_finishes_during_test()
    {
        var correlation = new XInputCorrelation();
        var disconnected = new XInputSlot(1167, default);
        correlation.Observe(new(0, 32, 0, 10, 0, 0, 0),
            [disconnected, disconnected, disconnected, disconnected]);
        for (short t = 1; t <= 3; t++)
        {
            var report = new XInputReport(0, 32, 0, t, 0, 0, 0);
            correlation.Observe(report, [new(1167, default), new(0, report), new(1167, default), new(1167, default)]);
        }
        Assert.Equal(1, correlation.VerifiedIndex);
    }

    [Fact]
    public void Ambiguous_slots_are_not_claimed()
    {
        var correlation = new XInputCorrelation();
        for (short t = 1; t <= 3; t++)
        {
            var report = new XInputReport(0, 32, 0, t, 0, 0, 0);
            correlation.Observe(report, [new(0, report), new(0, report), new(1167, default), new(1167, default)]);
        }
        Assert.Equal(-1, correlation.VerifiedIndex);
    }

    [Fact]
    public void Failsafe_fires_once_until_valid_input_arrives()
    {
        long start = Stopwatch.Frequency;
        var monitor = new InputWatchdog(start);
        Assert.False(monitor.Check(start + Stopwatch.Frequency - 1));
        Assert.True(monitor.Check(start + Stopwatch.Frequency));
        Assert.False(monitor.Check(start + 2 * Stopwatch.Frequency));
        Assert.True(monitor.Accept(start + 2 * Stopwatch.Frequency));
        Assert.False(monitor.Check(start + 3 * Stopwatch.Frequency - 1));
        Assert.True(monitor.Check(start + 3 * Stopwatch.Frequency));
    }

    [Theory]
    [InlineData(60)]
    [InlineData(120)]
    public void Continuous_short_intervals_do_not_trigger_failsafe(int rate)
    {
        long start = Stopwatch.Frequency;
        var monitor = new InputWatchdog(start);
        for (int i = 1; i <= rate * 60; i++)
        {
            long arrival = start + i * Stopwatch.Frequency / rate;
            Assert.False(monitor.Check(arrival));
            Assert.False(monitor.Accept(arrival));
        }
    }
}
