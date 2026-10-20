using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Camylla.Receiver;

public sealed class MfDecoder : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void FrameCallback(IntPtr data, uint length, uint width, uint height, uint stride,
        long timestamp100ns, IntPtr context);
    private readonly FrameCallback frameCallback;
    private readonly Action<OwnedNv12Frame>? frameSink;
    private IntPtr handle;
    public long LastDecodedTicks { get; private set; }
    public long LastDecodedPts100ns { get; private set; }
    public uint LastDecodedWidth { get; private set; }
    public uint LastDecodedHeight { get; private set; }
    public uint LastDecodedBytes { get; private set; }
    public uint LastDecodedStride { get; private set; }

    public MfDecoder(string mode, Action<OwnedNv12Frame>? frameSink = null)
    {
        this.frameSink = frameSink;
        var parts = mode.Split('@');
        if (parts.Length != 2) throw new ArgumentException("Video mode must include resolution and frame rate", nameof(mode));
        var dimensions = parts[0].Split('x');
        if (dimensions.Length != 2) throw new ArgumentException("Video mode resolution is invalid", nameof(mode));
        var width = uint.Parse(dimensions[0]);
        var height = uint.Parse(dimensions[1]);
        var framesPerSecond = uint.Parse(parts[1]);
        frameCallback = OnFrame;
        Marshal.ThrowExceptionForHR(NativeCreate(width, height, framesPerSecond, frameCallback, IntPtr.Zero, out handle));
    }

    private void OnFrame(IntPtr data, uint length, uint width, uint height, uint stride,
        long timestamp100ns, IntPtr context)
    {
        LastDecodedTicks = Stopwatch.GetTimestamp();
        LastDecodedPts100ns = timestamp100ns;
        LastDecodedWidth = width;
        LastDecodedHeight = height;
        LastDecodedBytes = length;
        LastDecodedStride = stride;
        if (frameSink != null)
        {
            try
            {
                using var frame = OwnedNv12Frame.CopyFromBorrowed(data, length, width, height, stride,
                    timestamp100ns, LastDecodedTicks);
                frameSink(frame);
            }
            catch (Exception)
            {
                // A managed exception must not unwind through the native MF callback.
                Interlocked.Increment(ref rejectedFrames);
            }
        }
    }
    private long rejectedFrames;
    public long RejectedFrames => Interlocked.Read(ref rejectedFrames);

    public int Push(byte[] bytes, ulong ptsUs, int? payloadLength = null)
    {
        var length = payloadLength ?? bytes.Length;
        if (length <= 0 || length > bytes.Length) throw new ArgumentOutOfRangeException(nameof(payloadLength));
        Marshal.ThrowExceptionForHR(NativePush(handle, bytes, checked((uint)length), ptsUs, out var count));
        return checked((int)count);
    }

    public void Reset() => Marshal.ThrowExceptionForHR(NativeReset(handle));

    public void Dispose()
    {
        if (handle == IntPtr.Zero) return;
        NativeDestroy(handle);
        handle = IntPtr.Zero;
        GC.KeepAlive(frameCallback);
    }

    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderCreate", CallingConvention = CallingConvention.Cdecl)]
    private static extern int NativeCreate(uint width, uint height, uint framesPerSecond, FrameCallback callback, IntPtr context, out IntPtr handle);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderPush", CallingConvention = CallingConvention.Cdecl)]
    private static extern int NativePush(IntPtr handle, byte[] data, uint length, ulong ptsUs, out uint count);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderReset", CallingConvention = CallingConvention.Cdecl)]
    private static extern int NativeReset(IntPtr handle);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderDestroy", CallingConvention = CallingConvention.Cdecl)]
    private static extern void NativeDestroy(IntPtr handle);
}
