using System.Diagnostics;

namespace PhantomPadReceiver.Core;

internal sealed class InputWatchdog(long startedAt)
{
    public long LastInputAt { get; private set; }
    public bool Degraded { get; private set; }

    public bool Accept(long receivedAt)
    {
        LastInputAt = receivedAt;
        bool recovered = Degraded;
        Degraded = false;
        return recovered;
    }

    public bool Check(long now)
    {
        long since = LastInputAt == 0 ? startedAt : LastInputAt;
        if (Degraded || Stopwatch.GetElapsedTime(since, now) < TimeSpan.FromSeconds(1)) return false;
        Degraded = true;
        return true;
    }
}
