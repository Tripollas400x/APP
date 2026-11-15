using PhantomPadReceiver.Protocol;
using Xunit;

namespace PhantomPadReceiver.Tests;

/// <summary>
/// Tests del parser incremental FramePipe: frames fragmentados en varias
/// lecturas, varios frames concatenados, bytes corruptos, CRC inválido, tipos
/// desconocidos y recuperación de sincronización. Espejo de FrameParserTest
/// (Kotlin) para garantizar que ambos extremos resincronizan igual.
/// </summary>
public class FramePipeTests
{
    private static byte[] SampleInput(ushort seq)
    {
        // Frame INPUT de 30 bytes válido: seq + ejes y botones simples.
        var f = new byte[PhantomProtocol.InputLen];
        f[0] = PhantomProtocol.Magic0;
        f[1] = PhantomProtocol.Magic1;
        f[2] = PhantomProtocol.Version;
        f[3] = (byte)FrameType.Input;
        f[4] = (byte)(seq & 0xFF);
        f[5] = (byte)(seq >> 8);
        f[6] = 0x01; // Cross
        f[PhantomProtocol.InputLen - 1] = PhantomProtocol.Crc8(f.AsSpan(0, PhantomProtocol.InputLen - 1));
        return f;
    }

    [Fact]
    public void Single_frame_in_one_read()
    {
        var pipe = new FramePipe();
        pipe.Append(PhantomProtocol.BuildPong(0x55));
        Assert.True(pipe.TryReadFrame(out var frame, out var type));
        Assert.Equal(FrameType.Pong, type);
        Assert.Equal(0x55, PhantomProtocol.ReadSeq(frame));
        Assert.False(pipe.TryReadFrame(out _, out _));
    }

    [Fact]
    public void Fragmented_frame_arriving_byte_by_byte()
    {
        var pipe = new FramePipe();
        var ping = PhantomProtocol.BuildPong(0x0102);
        foreach (var b in ping)
        {
            pipe.Append(new[] { b });
            // Ningún frame completo hasta el último byte...
            if (b != ping[^1])
            {
                Assert.False(pipe.TryReadFrame(out _, out _));
            }
        }
        Assert.True(pipe.TryReadFrame(out var frame, out var type));
        Assert.Equal(FrameType.Pong, type);
        Assert.Equal(0x0102, PhantomProtocol.ReadSeq(frame));
    }

    [Fact]
    public void Two_frames_concatenated_in_one_read()
    {
        var pipe = new FramePipe();
        var a = SampleInput(1);
        var b = SampleInput(2);
        pipe.Append(a.Concat(b).ToArray());
        Assert.True(pipe.TryReadFrame(out var first, out _));
        Assert.Equal(1, PhantomProtocol.ReadSeq(first));
        Assert.True(pipe.TryReadFrame(out var second, out _));
        Assert.Equal(2, PhantomProtocol.ReadSeq(second));
        Assert.False(pipe.TryReadFrame(out _, out _));
    }

    [Fact]
    public void Garbage_before_frame_resynchronizes()
    {
        var pipe = new FramePipe();
        var garbage = new byte[] { 0x00, 0xFF, 0x13, 0x37, 0x42 };
        pipe.Append(garbage);
        Assert.False(pipe.TryReadFrame(out _, out _));
        pipe.Append(PhantomProtocol.BuildPong(0x77));
        Assert.True(pipe.TryReadFrame(out var frame, out var type));
        Assert.Equal(FrameType.Pong, type);
        Assert.Equal(0x77, PhantomProtocol.ReadSeq(frame));
        Assert.True(pipe.DroppedBytes >= garbage.Length);
    }

    [Fact]
    public void Corrupt_frame_is_dropped_and_stream_recovers()
    {
        var pipe = new FramePipe();
        var corrupt = SampleInput(9);
        corrupt[^1] ^= 0xFF; // CRC inválido
        var good = SampleInput(10);
        pipe.Append(corrupt.Concat(good).ToArray());

        // El corrupto se descarta entero y el siguiente se lee bien.
        Assert.True(pipe.TryReadFrame(out var frame, out _));
        Assert.Equal(10, PhantomProtocol.ReadSeq(frame));
        Assert.Equal(1, pipe.RejectedFrames);
    }

    [Fact]
    public void Unknown_type_resynchronizes_without_losing_next_frame()
    {
        var pipe = new FramePipe();
        // Cabecera válida pero tipo 0xAB (desconocido): no se sabe su longitud,
        // se descarta byte a byte hasta recuperar sincronía.
        var unknown = new byte[] { PhantomProtocol.Magic0, PhantomProtocol.Magic1, PhantomProtocol.Version, 0xAB, 1, 2, 3, 4, 5 };
        pipe.Append(unknown);
        Assert.False(pipe.TryReadFrame(out _, out _));
        pipe.Append(PhantomProtocol.BuildPong(0x21));
        Assert.True(pipe.TryReadFrame(out var frame, out var type));
        Assert.Equal(FrameType.Pong, type);
        Assert.Equal(0x21, PhantomProtocol.ReadSeq(frame));
    }

    [Fact]
    public void ReleaseAll_frame_parses_with_correct_length()
    {
        var pipe = new FramePipe();
        var release = PhantomProtocol.BuildReleaseAll();
        Assert.Equal(PhantomProtocol.ReleaseAllLen, release.Length);
        pipe.Append(release);
        Assert.True(pipe.TryReadFrame(out var frame, out var type));
        Assert.Equal(FrameType.ReleaseAll, type);
        Assert.Equal(PhantomProtocol.ReleaseAllLen, frame.Length);
    }

    [Fact]
    public void High_volume_stream_survives_mixed_content()
    {
        var pipe = new FramePipe();
        var expectedSeqs = new List<int>();
        var stream = new List<byte>();
        var rnd = new Random(42);
        for (int i = 0; i < 200; i++)
        {
            switch (i % 5)
            {
                case 0:
                case 1:
                    stream.AddRange(SampleInput((ushort)i));
                    expectedSeqs.Add(i);
                    break;
                case 2:
                    stream.AddRange(PhantomProtocol.BuildPong((ushort)i));
                    expectedSeqs.Add(i);
                    break;
                case 3:
                    var bad = SampleInput((ushort)i);
                    bad[^1] ^= 0xAA; // CRC roto
                    stream.AddRange(bad);
                    break;
                default:
                    // Basura determinista que no puede formar cabecera (nunca 0x50).
                    stream.Add((byte)(0x10 + (i & 0x0F)));
                    break;
            }
        }

        // Alimentamos el pipe en lecturas de tamaño irregular.
        var data = stream.ToArray();
        int offset = 0;
        while (offset < data.Length)
        {
            int take = Math.Min(rnd.Next(1, 50), data.Length - offset);
            pipe.Append(data.AsSpan(offset, take));
            offset += take;
        }

        var read = new List<int>();
        while (pipe.TryReadFrame(out var frame, out _))
        {
            read.Add(PhantomProtocol.ReadSeq(frame));
        }
        Assert.Equal(expectedSeqs, read);
    }
}
