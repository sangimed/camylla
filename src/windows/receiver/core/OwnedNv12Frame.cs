using System.Buffers;
using System.Runtime.InteropServices;

namespace Camylla.Receiver;

/// <summary>
/// A read-only lease on owned NV12 bytes. Dispose after use; Retain creates a separate lease.
/// PTS is in 100 ns units from the phone's media clock.
/// </summary>
public sealed class OwnedNv12Frame : IDisposable
{
    private static long activeBuffers, highWaterBuffers, pooledRentals, pooledReturns;
    public static long ActiveBufferCount => Interlocked.Read(ref activeBuffers);
    public static long BufferHighWater => Interlocked.Read(ref highWaterBuffers);
    public static long PooledRentals => Interlocked.Read(ref pooledRentals);
    public static long PooledReturns => Interlocked.Read(ref pooledReturns);

    private sealed class BufferOwner
    {
        private int references = 1;
        internal readonly byte[] Array;
        internal readonly int Length;
        internal BufferOwner(byte[] array, int length)
        {
            Array = array; Length = length;
            Interlocked.Increment(ref pooledRentals);
            var now = Interlocked.Increment(ref activeBuffers);
            long prior;
            while (now > (prior = Interlocked.Read(ref highWaterBuffers)) &&
                   Interlocked.CompareExchange(ref highWaterBuffers, now, prior) != prior) { }
        }
        internal void Retain()
        {
            while (true)
            {
                var count = Volatile.Read(ref references);
                if (count == 0) throw new ObjectDisposedException(nameof(OwnedNv12Frame));
                if (Interlocked.CompareExchange(ref references, count + 1, count) == count) return;
            }
        }
        internal void Release()
        {
            if (Interlocked.Decrement(ref references) == 0)
            {
                ArrayPool<byte>.Shared.Return(Array);
                Interlocked.Increment(ref pooledReturns);
                Interlocked.Decrement(ref activeBuffers);
            }
        }
    }

    private readonly BufferOwner owner;
    private int disposed;
    public ReadOnlySpan<byte> Pixels
    {
        get
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref disposed) != 0, this);
            return owner.Array.AsSpan(0, owner.Length);
        }
    }
    public int Width { get; }
    public int Height { get; }
    public int Stride { get; }
    public long Pts100ns { get; }
    public long DecodedAtTicks { get; }
    public string PixelFormat => "NV12";

    private OwnedNv12Frame(BufferOwner owner, int width, int height, int stride, long pts100ns, long decodedAtTicks)
    {
        this.owner = owner;
        Width = width;
        Height = height;
        Stride = stride;
        Pts100ns = pts100ns;
        DecodedAtTicks = decodedAtTicks;
    }

    private static int RequiredLength(int width, int height, int stride)
    {
        if (width <= 0 || width > 4096 || height <= 0 || height > 2160 ||
            (width & 1) != 0 || (height & 1) != 0 || stride < width ||
            stride > 8192 || (stride & 1) != 0)
            throw new ArgumentException("Invalid NV12 dimensions or stride");
        var length = checked(stride * height * 3 / 2);
        if (length > 64 * 1024 * 1024) throw new ArgumentException("NV12 frame too large");
        return length;
    }

    public static OwnedNv12Frame CopyFromManaged(ReadOnlySpan<byte> source, int width, int height,
        int stride, long pts100ns, long decodedAtTicks)
    {
        var bytes = RequiredLength(width, height, stride);
        if (source.Length != bytes) throw new ArgumentException("Invalid NV12 byte length", nameof(source));
        var array = ArrayPool<byte>.Shared.Rent(bytes);
        try
        {
            source.CopyTo(array);
            return new OwnedNv12Frame(new BufferOwner(array, bytes), width, height, stride,
                pts100ns, decodedAtTicks);
        }
        catch { ArrayPool<byte>.Shared.Return(array); throw; }
    }

    public static OwnedNv12Frame CopyFromBorrowed(IntPtr source, uint length, uint width, uint height,
        uint stride, long pts100ns, long decodedAtTicks)
    {
        var w = checked((int)width);
        var h = checked((int)height);
        var s = checked((int)stride);
        var bytes = RequiredLength(w, h, s);
        // Media Foundation may align the stored luma height (1080 -> 1088).
        // NV12 chroma begins after those padded luma rows, not after the visible height.
        var rowBytes = (long)s;
        var storedHeightNumerator = (long)length * 2;
        var storedHeightDenominator = rowBytes * 3;
        if (source == IntPtr.Zero || storedHeightNumerator % storedHeightDenominator != 0)
            throw new ArgumentException("Invalid borrowed NV12 frame");
        var storedHeight = storedHeightNumerator / storedHeightDenominator;
        if (storedHeight < h || storedHeight > h + 128 || (storedHeight & 1) != 0)
            throw new ArgumentException("Invalid borrowed NV12 frame storage height");
        var array = ArrayPool<byte>.Shared.Rent(bytes);
        try
        {
            var lumaBytes = checked(s * h);
            var chromaBytes = bytes - lumaBytes;
            Marshal.Copy(source, array, 0, lumaBytes);
            Marshal.Copy(IntPtr.Add(source, checked((int)(rowBytes * storedHeight))),
                array, lumaBytes, chromaBytes);
            return new OwnedNv12Frame(new BufferOwner(array, bytes), w, h, s,
                pts100ns, decodedAtTicks);
        }
        catch { ArrayPool<byte>.Shared.Return(array); throw; }
    }

    public OwnedNv12Frame Retain()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposed) != 0, this);
        owner.Retain();
        if (Volatile.Read(ref disposed) != 0)
        {
            owner.Release();
            throw new ObjectDisposedException(nameof(OwnedNv12Frame));
        }
        try { return new OwnedNv12Frame(owner, Width, Height, Stride, Pts100ns, DecodedAtTicks); }
        catch { owner.Release(); throw; }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) == 0) owner.Release();
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
        var previous = Interlocked.Exchange(ref latest, frame.Retain());
        if (previous is not null)
        {
            Interlocked.Increment(ref superseded);
            previous.Dispose();
        }
        Interlocked.Increment(ref published);
    }

    public OwnedNv12Frame? TakeLatest() => Interlocked.Exchange(ref latest, null);
    public void Clear() => Interlocked.Exchange(ref latest, null)?.Dispose();
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
