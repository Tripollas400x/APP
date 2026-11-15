using System.Buffers.Binary;

namespace PhantomPadReceiver.Protocol;

public enum FrameType : byte
{
    Input = 0x01,
    Ping = 0x02,
    Pong = 0x03,
    ReleaseAll = 0x04,
    Rumble = 0x10
}

/// <summary>
/// Bits de botones compartidos con la app Android y docs/PROTOCOL.md.
/// NO reordenar sin subir la versión del protocolo.
/// </summary>
[Flags]
public enum PadButtons : uint
{
    Triangle = 1u << 0,
    Circle = 1u << 1,
    Cross = 1u << 2,
    Square = 1u << 3,
    DpadUp = 1u << 4,
    DpadDown = 1u << 5,
    DpadLeft = 1u << 6,
    DpadRight = 1u << 7,
    L1 = 1u << 8,
    R1 = 1u << 9,
    L3 = 1u << 10,
    R3 = 1u << 11,
    Options = 1u << 12,
    Share = 1u << 13,
    Ps = 1u << 14,
    Touchpad = 1u << 15
}

/// <summary>Estado de entrada decodificado de un frame INPUT.</summary>
public readonly record struct InputFrame(
    ushort Seq,
    PadButtons Buttons,
    sbyte LX, sbyte LY, sbyte RX, sbyte RY,
    byte L2, byte R2,
    byte Flags,
    short Pitch, short Roll, short Yaw,
    short AccX, short AccY, short AccZ);

/// <summary>
/// Implementación C# del protocolo Phantom Pad v1. Debe coincidir byte a byte
/// con PhantomProtocol.kt y scripts/protocol_reference.py (tests compartidos).
/// </summary>
public static class PhantomProtocol
{
    public const byte Magic0 = 0x50;
    public const byte Magic1 = 0x50;
    public const byte Version = 0x01;

    public const int HeaderLen = 4;
    public const int InputLen = 30;
    public const int PingLen = 7;
    public const int RumbleLen = 7;
    public const int ReleaseAllLen = 5;

    public static byte Crc8(ReadOnlySpan<byte> data)
    {
        byte crc = 0;
        foreach (var b in data)
        {
            crc ^= b;
            for (int i = 0; i < 8; i++)
            {
                crc = (crc & 0x80) != 0 ? (byte)((crc << 1) ^ 0x07) : (byte)(crc << 1);
            }
        }
        return crc;
    }

    public static bool TryParse(ReadOnlySpan<byte> frame, out FrameType type, out InputFrame input)
    {
        type = default;
        input = default;
        if (frame.Length < HeaderLen) return false;
        if (frame[0] != Magic0 || frame[1] != Magic1 || frame[2] != Version) return false;
        type = (FrameType)frame[3];

        int expected = type switch
        {
            FrameType.Input => InputLen,
            FrameType.Ping or FrameType.Pong => PingLen,
            FrameType.Rumble => RumbleLen,
            FrameType.ReleaseAll => ReleaseAllLen,
            _ => 0
        };
        if (expected == 0 || frame.Length < expected) return false;
        if (Crc8(frame[..(expected - 1)]) != frame[expected - 1]) return false;

        if (type == FrameType.Input)
        {
            var p = frame.Slice(HeaderLen, expected - HeaderLen - 1);
            ushort seq = (ushort)(p[0] | (p[1] << 8));
            uint buttons = (uint)(p[2] | (p[3] << 8) | (p[4] << 16) | (p[5] << 24));
            sbyte lx = (sbyte)p[6];
            sbyte ly = (sbyte)p[7];
            sbyte rx = (sbyte)p[8];
            sbyte ry = (sbyte)p[9];
            byte l2 = p[10];
            byte r2 = p[11];
            byte flags = p[12];
            short pitch = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(13, 2));
            short roll = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(15, 2));
            short yaw = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(17, 2));
            short ax = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(19, 2));
            short ay = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(21, 2));
            short az = BinaryPrimitives.ReadInt16LittleEndian(p.Slice(23, 2));
            input = new InputFrame(seq, (PadButtons)buttons, lx, ly, rx, ry, l2, r2, flags,
                pitch, roll, yaw, ax, ay, az);
        }
        return true;
    }

    public static ushort ReadSeq(ReadOnlySpan<byte> frame) =>
        (ushort)(frame[4] | (frame[5] << 8));

    public static byte[] BuildPong(ushort seq) => ShortFrame(FrameType.Pong, seq);

    public static byte[] BuildRumble(byte small, byte large)
    {
        var f = new byte[RumbleLen];
        FillHeader(f, FrameType.Rumble);
        f[4] = small;
        f[5] = large;
        f[RumbleLen - 1] = Crc8(f.AsSpan(0, RumbleLen - 1));
        return f;
    }

    public static byte[] BuildReleaseAll()
    {
        var f = new byte[ReleaseAllLen];
        FillHeader(f, FrameType.ReleaseAll);
        f[ReleaseAllLen - 1] = Crc8(f.AsSpan(0, ReleaseAllLen - 1));
        return f;
    }

    private static byte[] ShortFrame(FrameType type, ushort seq)
    {
        var f = new byte[PingLen];
        FillHeader(f, type);
        f[4] = (byte)(seq & 0xFF);
        f[5] = (byte)(seq >> 8);
        f[PingLen - 1] = Crc8(f.AsSpan(0, PingLen - 1));
        return f;
    }

    private static void FillHeader(byte[] f, FrameType type)
    {
        f[0] = Magic0;
        f[1] = Magic1;
        f[2] = Version;
        f[3] = (byte)type;
    }
}
