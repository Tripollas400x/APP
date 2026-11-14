using PhantomPadReceiver.Protocol;
using Xunit;

namespace PhantomPadReceiver.Tests;

/// <summary>
/// Vectores dorados compartidos con Kotlin (ProtocolTest) y Python
/// (scripts/protocol_reference.py). Garantizan que app y receptor hablan
/// exactamente el mismo idioma binario.
/// </summary>
public class PhantomProtocolTests
{
    [Fact]
    public void Crc8_standard_check_value()
    {
        byte[] data = "123456789"u8.ToArray();
        Assert.Equal(0xF4, PhantomProtocol.Crc8(data));
    }

    [Fact]
    public void Golden_input_frame_matches_android_app()
    {
        // mismo frame que genera ProtocolTest en Kotlin:
        // seq=1, botones=0x0000010F, LX=-1, RX=1, L2=0.5, R2=0.25
        byte[] frame =
        {
            0x50, 0x50, 0x01, 0x01,
            0x01, 0x00,
            0x0F, 0x01, 0x00, 0x00,
            0x81, 0x00, 0x7F, 0x00,
            0x80, 0x40,
            0x00,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xB5
        };

        Assert.True(PhantomProtocol.TryParse(frame, out var type, out var input));
        Assert.Equal(FrameType.Input, type);
        Assert.Equal(1, input.Seq);
        Assert.Equal(PadButtons.Triangle | PadButtons.Circle | PadButtons.Cross |
                     PadButtons.Square | PadButtons.L1, input.Buttons);
        Assert.Equal(-1, input.LX);
        Assert.Equal(0, input.LY);
        Assert.Equal(1, input.RX);
        Assert.Equal(0, input.RY);
        Assert.Equal(128, input.L2);
        Assert.Equal(64, input.R2);
    }

    [Fact]
    public void Pong_frame_is_built_with_valid_crc()
    {
        var pong = PhantomProtocol.BuildPong(0x2A);
        Assert.Equal(PhantomProtocol.PingLen, pong.Length);
        Assert.True(PhantomProtocol.TryParse(pong, out var type, out _));
        Assert.Equal(FrameType.Pong, type);
        Assert.Equal(0x2A, PhantomProtocol.ReadSeq(pong));
    }

    [Fact]
    public void Rumble_and_release_frames_are_valid()
    {
        var rumble = PhantomProtocol.BuildRumble(0x40, 0x80);
        Assert.True(PhantomProtocol.TryParse(rumble, out var rumbleType, out _));
        Assert.Equal(FrameType.Rumble, rumbleType);
        Assert.Equal(0x40, rumble[4]);
        Assert.Equal(0x80, rumble[5]);

        var release = PhantomProtocol.BuildReleaseAll();
        Assert.True(PhantomProtocol.TryParse(release, out var releaseType, out _));
        Assert.Equal(FrameType.ReleaseAll, releaseType);
        Assert.Equal(PhantomProtocol.ReleaseAllLen, release.Length);
    }

    [Fact]
    public void Corrupted_frame_is_rejected()
    {
        var frame = PhantomProtocol.BuildPong(7);
        frame[4] ^= 0xFF;
        Assert.False(PhantomProtocol.TryParse(frame, out _, out _));
    }
}
