using System.Diagnostics;
using System.Threading.Channels;
using PhantomPadReceiver.Protocol;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace PhantomPadReceiver.Core;

internal sealed class ReceiverSession(int id, StreamSocket socket, CancellationToken lifetime)
{
    private static readonly byte[] RumbleNotice = [];
    private byte[]? _rumble;
    private readonly Channel<byte[]> _outbox = Channel.CreateBounded<byte[]>(32);
    private readonly CancellationTokenSource _cts = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
    private int _closed;
    private ushort _nextPing;
    private ushort _pendingPing;
    private long _pingSentAt;
    private long _lastReadAt;
    private ushort? _lastInputSeq;

    public int Id { get; } = id;
    public InputWatchdog Watchdog { get; } = new(Stopwatch.GetTimestamp());
    public FramePipe Pipe { get; } = new();
    public long Bytes { get; private set; }
    public long Inputs { get; private set; }
    public long ValidFrames { get; private set; }
    public long MissingInputs { get; private set; }
    public long LateInputs { get; private set; }
    public int LastReadBytes { get; private set; }
    public int LastReadFrames { get; private set; }
    public double ReadGapMs { get; private set; }
    public double InputGapMs { get; private set; }
    public double MaximumInputGapMs { get; private set; }
    public double ProcessingMs { get; private set; }
    public double? RttMs { get; private set; }

    public bool Send(byte[] data) => _outbox.Writer.TryWrite(data);

    public void SendRumble(byte small, byte large)
    {
        var report = PhantomProtocol.BuildRumble(small, large);
        if (Interlocked.Exchange(ref _rumble, report) == null && !_outbox.Writer.TryWrite(RumbleNotice))
            Interlocked.Exchange(ref _rumble, null);
    }

    public void Cancel()
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        _outbox.Writer.TryComplete();
        socket.Dispose();
    }

    public async Task RunAsync(Action<ReceiverSession, byte[], long> onRead)
    {
        using (_cts)
        using (var reader = new DataReader(socket.InputStream) { InputStreamOptions = InputStreamOptions.Partial })
        using (var writer = new DataWriter(socket.OutputStream))
        {
            var output = WriteAsync(writer);
            try
            {
                // Partial makes 512 a maximum. A single packet can complete the read.
                while (true)
                {
                    uint count = await reader.LoadAsync(512).AsTask(_cts.Token).ConfigureAwait(false);
                    long arrival = Stopwatch.GetTimestamp();
                    if (count == 0) break;
                    byte[] bytes = new byte[count];
                    reader.ReadBytes(bytes);
                    onRead(this, bytes, arrival);
                }
            }
            finally
            {
                _outbox.Writer.TryComplete();
                await _cts.CancelAsync().ConfigureAwait(false);
                await output.ConfigureAwait(false);
            }
        }
    }

    private async Task WriteAsync(DataWriter writer)
    {
        try
        {
            await foreach (var data in _outbox.Reader.ReadAllAsync(_cts.Token).ConfigureAwait(false))
            {
                var report = ReferenceEquals(data, RumbleNotice) ? Interlocked.Exchange(ref _rumble, null) : data;
                if (report == null) continue;
                writer.WriteBytes(report);
                await writer.StoreAsync().AsTask(_cts.Token).ConfigureAwait(false);
            }
        }
        finally
        {
            // Aborts the read if the writer fails or the owner closes this session.
            await _cts.CancelAsync().ConfigureAwait(false);
        }
    }

    public void BeginRead(int bytes, long arrival)
    {
        ReadGapMs = _lastReadAt == 0 ? 0 : Stopwatch.GetElapsedTime(_lastReadAt, arrival).TotalMilliseconds;
        _lastReadAt = arrival;
        LastReadBytes = bytes;
        Bytes += bytes;
        LastReadFrames = 0;
    }

    public void CountFrame()
    {
        ValidFrames++;
        LastReadFrames++;
    }

    public bool RecordInput(InputFrame input, long arrival)
    {
        ProcessingMs = Stopwatch.GetElapsedTime(arrival).TotalMilliseconds;
        if (ProcessingMs >= InputWatchdog.TimeoutMs)
        {
            LateInputs++;
            return false;
        }
        Inputs++;
        if (Watchdog.LastInputAt != 0)
        {
            InputGapMs = Stopwatch.GetElapsedTime(Watchdog.LastInputAt, arrival).TotalMilliseconds;
            MaximumInputGapMs = Math.Max(MaximumInputGapMs, InputGapMs);
        }
        if (_lastInputSeq is ushort last)
        {
            int delta = (ushort)(input.Seq - last);
            if (delta is > 1 and < 32768) MissingInputs += delta - 1;
        }
        _lastInputSeq = input.Seq;
        return true;
    }

    public void Ping(long now)
    {
        if (_pingSentAt != 0 && Stopwatch.GetElapsedTime(_pingSentAt, now) < TimeSpan.FromSeconds(3)) return;
        if (_pingSentAt != 0) RttMs = null;
        _pendingPing = _nextPing++;
        if (Send(PhantomProtocol.BuildPing(_pendingPing))) _pingSentAt = now;
    }

    public void ReceivePong(ushort sequence, long arrival)
    {
        if (_pingSentAt == 0 || sequence != _pendingPing) return;
        double elapsedMs = Stopwatch.GetElapsedTime(_pingSentAt, arrival).TotalMilliseconds;
        RttMs = elapsedMs is >= 0 and < 3000 ? elapsedMs : null;
        _pingSentAt = 0;
    }
}
