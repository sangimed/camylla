using System.Runtime.InteropServices;

namespace Camylla.Receiver;

/// <summary>One immutable, owned NV12 image. PTS is in 100 ns units from the phone's media clock.</summary>
public sealed class OwnedNv12Frame
{
    private readonly byte[] pixels;
    public ReadOnlySpan<byte> Pixels => pixels;
    public int Width { get; }
    public int Height { get; }
    public int Stride { get; }
    public long Pts100ns { get; }
    public long DecodedAtTicks { get; }
    public string PixelFormat => "NV12";

    private OwnedNv12Frame(byte[] pixels, int width, int height, int stride, long pts100ns, long decodedAtTicks)
    {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0 ||
            stride < width || (stride & 1) != 0 || pixels.Length != checked(stride * height * 3 / 2))
            throw new ArgumentException("Invalid contiguous NV12 dimensions or length");
        this.pixels = pixels;
        Width = width;
        Height = height;
        Stride = stride;
        Pts100ns = pts100ns;
        DecodedAtTicks = decodedAtTicks;
    }

    public static OwnedNv12Frame CopyFromManaged(ReadOnlySpan<byte> source, int width, int height,
        int stride, long pts100ns, long decodedAtTicks) =>
        new(source.ToArray(), width, height, stride, pts100ns, decodedAtTicks);

    public static OwnedNv12Frame CopyFromBorrowed(IntPtr source, uint length, uint width, uint height,
        uint stride, long pts100ns, long decodedAtTicks)
    {
        var bytes = checked((int)(checked((ulong)stride * height * 3UL) / 2UL));
        if (source == IntPtr.Zero || bytes <= 0 || bytes != length || width > 4096 || height > 2160)
            throw new ArgumentException("Invalid borrowed NV12 frame");
        var owned = new byte[bytes];
        Marshal.Copy(source, owned, 0, bytes);
        return new OwnedNv12Frame(owned, checked((int)width), checked((int)height),
            checked((int)stride), pts100ns, decodedAtTicks);
    }
}

/// <summary>Exactly one pending frame; a slow consumer replaces rather than accumulates frames.</summary>
public sealed class LatestFrameStore
{
    private OwnedNv12Frame? latest;
    private long published;
    private long superseded;

    public long Published => Interlocked.Read(ref published);
    public long Superseded => Interlocked.Read(ref superseded);
    public int PendingDepth => Volatile.Read(ref latest) is null ? 0 : 1;
    public int HighWater => Published == 0 ? 0 : 1;

    public void Publish(OwnedNv12Frame frame)
    {
        if (Interlocked.Exchange(ref latest, frame) is not null)
            Interlocked.Increment(ref superseded);
        Interlocked.Increment(ref published);
    }

    public OwnedNv12Frame? TakeLatest() => Interlocked.Exchange(ref latest, null);
    public void Clear() => Interlocked.Exchange(ref latest, null);
}

public sealed class FrameReader : IDisposable
{
    private readonly Action unsubscribe;
    private int disposed;
    internal FrameReader(LatestFrameStore store, Action unsubscribe)
    {
        Store = store;
        this.unsubscribe = unsubscribe;
    }
    public LatestFrameStore Store { get; }
    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) == 0) unsubscribe();
    }
}
