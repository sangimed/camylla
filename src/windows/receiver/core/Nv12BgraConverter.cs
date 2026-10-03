namespace Camylla.Receiver;

/// <summary>
/// CPU preview conversion. The destination is caller-owned and can be reused.
/// HD uses limited-range BT.709; SD uses limited-range BT.601. The M4.1 decoder
/// callback does not yet expose MF color metadata, so this is a size-based default.
/// </summary>
public static class Nv12BgraConverter
{
    public static int RequiredBytes(OwnedNv12Frame frame) => checked(frame.Width * frame.Height * 4);

    public static void Convert(OwnedNv12Frame frame, Span<byte> destination)
    {
        if (destination.Length < RequiredBytes(frame))
            throw new ArgumentException("BGRA destination is too small", nameof(destination));
        var source = frame.Pixels;
        var uvBase = checked(frame.Stride * frame.Height);
        var hd = frame.Height >= 720 || frame.Width >= 1280;
        var blueU = hd ? 541 : 516;
        var greenU = hd ? 55 : 100;
        var greenV = hd ? 136 : 208;
        var redV = hd ? 459 : 409;
        for (var y = 0; y < frame.Height; y++)
        for (var x = 0; x < frame.Width; x++)
        {
            var luma = source[y * frame.Stride + x] - 16;
            var chroma = uvBase + (y / 2) * frame.Stride + (x & ~1);
            var u = source[chroma] - 128;
            var v = source[chroma + 1] - 128;
            var c = Math.Max(0, luma) * 298;
            var offset = (y * frame.Width + x) * 4;
            destination[offset] = Clamp((c + blueU * u + 128) >> 8);
            destination[offset + 1] = Clamp((c - greenU * u - greenV * v + 128) >> 8);
            destination[offset + 2] = Clamp((c + redV * v + 128) >> 8);
            destination[offset + 3] = 255;
        }
    }

    private static byte Clamp(int value) => (byte)Math.Clamp(value, 0, 255);
}
