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
        var dimensions = mode.Split('@')[0].Split('x');
        var width = uint.Parse(dimensions[0]);
        var height = uint.Parse(dimensions[1]);
        frameCallback = OnFrame;
        Marshal.ThrowExceptionForHR(NativeCreate(width, height, frameCallback, IntPtr.Zero, out handle));
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
                frameSink(OwnedNv12Frame.CopyFromBorrowed(data, length, width, height, stride,
                    timestamp100ns, LastDecodedTicks));
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

    public int Push(byte[] bytes, ulong ptsUs)
    {
        Marshal.ThrowExceptionForHR(NativePush(handle, bytes, (uint)bytes.Length, ptsUs, out var count));
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
    private static extern int NativeCreate(uint width, uint height, FrameCallback callback, IntPtr context, out IntPtr handle);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderPush", CallingConvention = CallingConvention.Cdecl)]
    private static extern int NativePush(IntPtr handle, byte[] data, uint length, ulong ptsUs, out uint count);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderReset", CallingConvention = CallingConvention.Cdecl)]
    private static extern int NativeReset(IntPtr handle);
    [DllImport("camylla_mf_decoder", EntryPoint = "CamyllaDecoderDestroy", CallingConvention = CallingConvention.Cdecl)]
    private static extern void NativeDestroy(IntPtr handle);
}
