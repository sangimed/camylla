using System.Buffers;
using System.Buffers.Binary;
using System.Diagnostics;
using System.Text;
using System.Runtime.InteropServices;
using System.Net;
using System.Net.Sockets;
using System.IO.Pipes;

namespace Camylla.Receiver;

internal static class SelfTest
{
    public static void DecodeAnnexBFile(string path)
    {
        var units = ReadAccessUnits(path);
        using var decoder = new MfDecoder("1280x720@30");
        var decoded = 0;
        for (var index = 0; index < units.Count; index++)
            decoded += decoder.Push(units[index], (ulong)index * 33333);
        Console.WriteLine($"decoder test: AU={units.Count} decoded={decoded} width={decoder.LastDecodedWidth} height={decoder.LastDecodedHeight}");
        Check(decoded > 0 && decoder.LastDecodedWidth == 1280 && decoder.LastDecodedHeight == 720,
            "Media Foundation decoded NV12 frames");
        Console.WriteLine($"Media Foundation decoded {decoded} NV12 frames from {units.Count} H.264 access units");
    }

    public static IReadOnlyList<byte[]> ReadAccessUnits(string path)
    {
        var bytes = File.ReadAllBytes(path);
        var boundaries = new List<int>();
        for (var i = 0; i < bytes.Length - 5; i++)
        {
            var prefix = StartCodeLength(bytes, i);
            if (prefix == 0) continue;
            if ((bytes[i + prefix] & 0x1f) == 9) boundaries.Add(i);
            i += prefix;
        }
        if (boundaries.Count < 2) throw new InvalidDataException("Decoder test stream needs AUD NALs");
        var units = new List<byte[]>(boundaries.Count);
        for (var index = 0; index < boundaries.Count; index++)
        {
            var start = index == 0 ? 0 : boundaries[index];
            var end = index + 1 < boundaries.Count ? boundaries[index + 1] : bytes.Length;
            units.Add(bytes[start..end]);
        }
        return units;
    }

    public static byte[] ExtractConfig(byte[] firstUnit)
    {
        using var output = new MemoryStream();
        for (var i = 0; i < firstUnit.Length - 5; i++)
        {
            var prefix = StartCodeLength(firstUnit, i);
            if (prefix == 0) continue;
            var kind = firstUnit[i + prefix] & 0x1f;
            if (kind is 7 or 8)
            {
                var end = i + prefix + 1;
                while (end < firstUnit.Length - 5 && StartCodeLength(firstUnit, end) == 0) end++;
                output.Write(firstUnit, i, end - i);
            }
            i += prefix;
        }
        if (output.Length == 0) throw new InvalidDataException("No SPS/PPS in test stream");
        return output.ToArray();
    }

    private static int StartCodeLength(byte[] bytes, int i) =>
        bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 ? 3 :
        bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 1 ? 4 : 0;

    public static async Task RunAsync()
    {
        TestOwnedLatestFrames();
        TestNv12Conversion();
        await TestCameraPipeAsync();
        await TestReceiverLifecycleAsync();
        var frame = new byte[Wire.VideoHeaderSize + 4];
        "CMYV"u8.CopyTo(frame);
        frame[4] = Wire.Version;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(6), Wire.VideoHeaderSize);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(8), 4);
        BinaryPrimitives.WriteUInt64BigEndian(frame.AsSpan(12), 5);
        BinaryPrimitives.WriteUInt64BigEndian(frame.AsSpan(24), 123456);
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(32), Wire.Key);
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(34), 1);
        frame[36] = 0xAB;
        frame[64] = 0; frame[65] = 0; frame[66] = 1; frame[67] = 0x65;
        using var partial = new OneByteStream(frame);
        using var parsed = await Wire.ReadVideoAsync(partial, default);
        Check(parsed.Sequence == 5 && parsed.PtsUs == 123456 && parsed.Flags == Wire.Key &&
            parsed.PayloadLength == 4 && parsed.Payload.AsSpan(0, parsed.PayloadLength)
                .SequenceEqual(frame.AsSpan(64, 4)) &&
            parsed.SessionId.StartsWith("AB", StringComparison.Ordinal), "fragmented video read");
        var oversized = (byte[])frame.Clone();
        BinaryPrimitives.WriteUInt32BigEndian(oversized.AsSpan(8), Wire.MaxVideoBytes + 1);
        await RejectsAsync(() => Wire.ReadVideoAsync(new OneByteStream(oversized), default), "oversized video");
        await RejectsAsync(() => Wire.ReadVideoAsync(new OneByteStream(frame[..^1]), default), "truncated video");
        var version = (byte[])frame.Clone(); version[4] = 99;
        await RejectsAsync(() => Wire.ReadVideoAsync(new OneByteStream(version), default), "unsupported version");
        var type = (byte[])frame.Clone(); type[35] = 7;
        await RejectsAsync(() => Wire.ReadVideoAsync(new OneByteStream(type), default), "invalid type");
        using var control = new MemoryStream();
        await Wire.WriteControlAsync(control, new { type = "ping" }, default);
        control.Position = 0;
        using var parsedControl = await Wire.ReadControlAsync(new OneByteStream(control.ToArray()), default);
        Check(Wire.RequiredString(parsedControl.RootElement, "type") == "ping", "fragmented control read/write");
        Check(Wire.FixedHexEquals("AABB", "aabb"), "hex secret comparison");
        try { Wire.RequireBoundSession("AA", "BB"); throw new Exception("Self-test failed: mismatched session"); }
        catch (System.Security.Authentication.AuthenticationException) { }
        Console.WriteLine("Receiver wire self-test passed");
    }

    private static async Task TestCameraPipeAsync()
    {
        using var host = new ReceiverHost();
        var streaming = 1;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var header = new byte[CameraPipePublisher.HeaderBytes];
        await using (var publisher = new CameraPipePublisher(host, () =>
            (Volatile.Read(ref streaming) == 1, "synthetic-session")))
        {
        publisher.Start();
        using var client = new NamedPipeClientStream(".", CameraPipePublisher.PipeName,
            PipeDirection.InOut, PipeOptions.Asynchronous);
        await client.ConnectAsync(timeout.Token);
        await client.WriteAsync("CMY1"u8.ToArray(), timeout.Token);
        await client.ReadExactlyAsync(header, timeout.Token);
        Check(header[6] == 0, "camera pipe starts neutral");
        var pixels = new byte[1280 * 720 * 3 / 2];
        using (var frame = OwnedNv12Frame.CopyFromManaged(pixels, 1280, 720, 1280, 123,
                   Stopwatch.GetTimestamp())) host.PublishFrame(frame);
        await Task.Delay(50, timeout.Token);
        await client.WriteAsync("CMY1"u8.ToArray(), timeout.Token);
        await client.ReadExactlyAsync(header, timeout.Token);
        Check(header[6] == 1 && BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(20)) == pixels.Length,
            "camera pipe publishes complete live NV12");
        var received = new byte[pixels.Length];
        await client.ReadExactlyAsync(received, timeout.Token);
        Check(received.AsSpan().SequenceEqual(pixels), "camera pipe preserves NV12 bytes");
        Volatile.Write(ref streaming, 0);
        await Task.Delay(50, timeout.Token);
        await client.WriteAsync("CMY1"u8.ToArray(), timeout.Token);
        await client.ReadExactlyAsync(header, timeout.Token);
        Check(header[6] == 0, "camera pipe turns neutral on Stop");
        client.Dispose();
        using var reopened = new NamedPipeClientStream(".", CameraPipePublisher.PipeName,
            PipeDirection.InOut, PipeOptions.Asynchronous);
        await reopened.ConnectAsync(timeout.Token);
        await reopened.WriteAsync("CMY1"u8.ToArray(), timeout.Token);
        await reopened.ReadExactlyAsync(header, timeout.Token);
        Check(header[6] == 0, "reopened consumer remains neutral without capture");
        reopened.Dispose();
        }
        await using (var restarted = new CameraPipePublisher(host, () => (false, "none")))
        {
            restarted.Start();
            using var afterRestart = new NamedPipeClientStream(".", CameraPipePublisher.PipeName,
                PipeDirection.InOut, PipeOptions.Asynchronous);
            await afterRestart.ConnectAsync(timeout.Token);
            await afterRestart.WriteAsync("CMY1"u8.ToArray(), timeout.Token);
            await afterRestart.ReadExactlyAsync(header, timeout.Token);
            Check(header[6] == 0, "publisher restart returns neutral and accepts new consumer");
        }
    }

    private static void TestNv12Conversion()
    {
        var source = new byte[18]; // 4x2 NV12 with stride 6 and non-image padding.
        source[0] = 16; source[1] = 235; source[2] = 82; source[3] = 82;
        source[6] = 16; source[7] = 235; source[8] = 82; source[9] = 82;
        source[4] = source[5] = source[10] = source[11] = 7;
        source[12] = 128; source[13] = 128;
        source[14] = 90; source[15] = 240;
        source[16] = source[17] = 7;
        using var frame = OwnedNv12Frame.CopyFromManaged(source, 4, 2, 6, 0, 0);
        var pixels = new byte[Nv12BgraConverter.RequiredBytes(frame)];
        Nv12BgraConverter.Convert(frame, pixels);
        Check(pixels.AsSpan(0, 4).SequenceEqual(new byte[] { 0, 0, 0, 255 }) &&
            pixels.AsSpan(4, 4).SequenceEqual(new byte[] { 255, 255, 255, 255 }) &&
            pixels[8] <= 2 && pixels[9] <= 3 && pixels[10] >= 253 &&
            pixels.AsSpan(16, 4).SequenceEqual(new byte[] { 0, 0, 0, 255 }),
            "stride-aware NV12 to BGRA black, white, and red conversion");
        const int hdWidth = 1280, hdHeight = 720;
        var hdSource = new byte[hdWidth * hdHeight * 3 / 2];
        hdSource.AsSpan(0, hdWidth * hdHeight).Fill(63);
        for (var i = hdWidth * hdHeight; i < hdSource.Length; i += 2)
        {
            hdSource[i] = 102; hdSource[i + 1] = 240;
        }
        using var hdFrame = OwnedNv12Frame.CopyFromManaged(hdSource, hdWidth, hdHeight, hdWidth, 0, 0);
        var hdPixels = new byte[Nv12BgraConverter.RequiredBytes(hdFrame)];
        Nv12BgraConverter.Convert(hdFrame, hdPixels);
        Check(hdPixels[0] <= 2 && hdPixels[1] <= 3 && hdPixels[2] >= 253 && hdPixels[3] == 255,
            "BT.709 limited-range HD red conversion");
        Console.WriteLine("NV12 stride and color conversion self-test passed");
    }

    public static void FrameMemorySmoke()
    {
        const int width = 1280, height = 720, stride = 1280, frames = 300;
        var bytes = stride * height * 3 / 2;
        var source = new byte[bytes];
        Array.Fill(source, (byte)128);
        var native = Marshal.AllocHGlobal(bytes);
        var beforeBuffers = OwnedNv12Frame.ActiveBufferCount;
        var beforeRentals = OwnedNv12Frame.PooledRentals;
        var beforeReturns = OwnedNv12Frame.PooledReturns;
        try
        {
            Marshal.Copy(source, 0, native, bytes);
            using var host = new ReceiverHost(58423, 58424);
            using var preview = host.CreateFrameReader();
            using var publisher = host.CreateFrameReader();
            var bgra = ArrayPool<byte>.Shared.Rent(width * height * 4);
            try
            {
                var allocatedBefore = GC.GetAllocatedBytesForCurrentThread();
                var clock = Stopwatch.StartNew();
                for (var index = 0; index < frames; index++)
                {
                    using (var produced = OwnedNv12Frame.CopyFromBorrowed(native, (uint)bytes,
                        width, height, stride, index * 333333L, Stopwatch.GetTimestamp()))
                        host.PublishFrame(produced);
                    using var rendered = preview.Store.TakeLatest();
                    using var published = publisher.Store.TakeLatest();
                    using var diagnostic = host.Frames.TakeLatest();
                    Check(rendered != null && published != null && diagnostic != null &&
                        rendered.Pts100ns == published.Pts100ns &&
                        preview.Store.PendingDepth == 0 && publisher.Store.PendingDepth == 0,
                        "synthetic 720p30 independent consumer handoff");
                    Nv12BgraConverter.Convert(rendered!, bgra.AsSpan(0, width * height * 4));
                    var remaining = TimeSpan.FromSeconds((index + 1) / 30.0) - clock.Elapsed;
                    if (remaining > TimeSpan.Zero) Thread.Sleep(remaining);
                }
                var elapsed = clock.Elapsed;
                var allocated = GC.GetAllocatedBytesForCurrentThread() - allocatedBefore;
                Check(allocated < 8_000_000 && OwnedNv12Frame.ActiveBufferCount == beforeBuffers &&
                    preview.Store.HighWater <= 1 && publisher.Store.HighWater <= 1,
                    "pooled 720p30 frame workload allocation and bounds");
                using var process = Process.GetCurrentProcess();
                process.Refresh();
                Console.WriteLine($"720p30 pooled smoke: frames={frames} seconds={elapsed.TotalSeconds:F2} " +
                    $"managedAllocatedBytes={allocated} activeBuffers={OwnedNv12Frame.ActiveBufferCount} " +
                    $"bufferHighWater={OwnedNv12Frame.BufferHighWater} queueHighWater={preview.Store.HighWater} " +
                    $"privateBytes={process.PrivateMemorySize64}");
            }
            finally { ArrayPool<byte>.Shared.Return(bgra); }
        }
        finally { Marshal.FreeHGlobal(native); }
        Check(OwnedNv12Frame.ActiveBufferCount == beforeBuffers &&
            OwnedNv12Frame.PooledRentals - beforeRentals ==
            OwnedNv12Frame.PooledReturns - beforeReturns,
            "all workload NV12 buffers returned on shutdown");
    }

    private static async Task TestReceiverLifecycleAsync()
    {
        static int FreePort()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            listener.Stop();
            return port;
        }
        var control = FreePort();
        var video = FreePort();
        while (video == control) video = FreePort();
        for (var cycle = 0; cycle < 2; cycle++)
        {
            var activeBefore = OwnedNv12Frame.ActiveBufferCount;
            using var host = new ReceiverHost(control, video);
            using var reader = host.CreateFrameReader();
            host.Log += _ => { };
            using var stop = new CancellationTokenSource();
            var run = host.RunAsync(stop.Token);
            await Task.Delay(100);
            Check(!run.IsCompleted, "receiver listener started");
            using (var pending = OwnedNv12Frame.CopyFromManaged(new byte[12], 4, 2, 4, 0, 0))
                host.PublishFrame(pending);
            Check(host.Frames.PendingDepth == 1 && reader.Store.PendingDepth == 1,
                "receiver has pending frame before stop");
            stop.Cancel();
            await run.WaitAsync(TimeSpan.FromSeconds(5));
            Check(host.Snapshot().State == "Disconnected" &&
                host.Frames.PendingDepth == 0 && reader.Store.PendingDepth == 0 &&
                OwnedNv12Frame.ActiveBufferCount == activeBefore,
                "receiver shutdown clears state and releases all pending frame buffers");
        }
        Console.WriteLine("Receiver start, shutdown, and same-port restart self-test passed");
    }

    private static void TestOwnedLatestFrames()
    {
        static void RejectFrame(Action create, string name)
        {
            try { create(); throw new Exception($"Self-test failed: {name}"); }
            catch (ArgumentException) { }
        }
        RejectFrame(() => OwnedNv12Frame.CopyFromManaged(new byte[12], 3, 2, 4, 0, 0), "odd width");
        RejectFrame(() => OwnedNv12Frame.CopyFromManaged(new byte[12], 4, 3, 4, 0, 0), "odd height");
        RejectFrame(() => OwnedNv12Frame.CopyFromManaged(new byte[12], 4, 2, 2, 0, 0), "short stride");
        RejectFrame(() => OwnedNv12Frame.CopyFromManaged(new byte[12], 4, 2, 5, 0, 0), "odd stride");
        RejectFrame(() => OwnedNv12Frame.CopyFromManaged(new byte[11], 4, 2, 4, 0, 0), "short payload");
        RejectFrame(() => OwnedNv12Frame.CopyFromBorrowed(IntPtr.Zero, 12, 4, 2, 4, 0, 0), "null borrowed frame");
        var before = OwnedNv12Frame.ActiveBufferCount;
        var rentalsBefore = OwnedNv12Frame.PooledRentals;
        var returnsBefore = OwnedNv12Frame.PooledReturns;
        var source = Enumerable.Repeat((byte)42, 12).ToArray();
        using var first = OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 100, 200);
        source[0] = 99;
        Check(first.Pixels[0] == 42 && first.PixelFormat == "NV12" &&
            first.Width == 4 && first.Height == 2 && first.Stride == 4 &&
            first.Pts100ns == 100 && first.DecodedAtTicks == 200, "managed NV12 ownership and metadata");
        var native = Marshal.AllocHGlobal(source.Length);
        try
        {
            Marshal.Copy(source, 0, native, source.Length);
            using var copy = OwnedNv12Frame.CopyFromBorrowed(native, 12, 4, 2, 4, 300, 400);
            Marshal.WriteByte(native, 0, 0);
            Check(copy.Pixels[0] == 99, "borrowed NV12 copied before callback returns");
        }
        finally { Marshal.FreeHGlobal(native); }
        var queue = new LatestFrameStore();
        queue.Publish(first);
        using (var replacement = OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 300, 400))
            queue.Publish(replacement);
        Check(queue.PendingDepth == 1 && queue.HighWater == 1 && queue.Superseded == 1 &&
            queue.Published == 2,
            "latest-frame replacement and bounded depth");
        using var newest = queue.TakeLatest();
        Check(newest?.Pts100ns == 300 && queue.PendingDepth == 0, "take transfers latest frame");
        using var host = new ReceiverHost(58423, 58424);
        using var preview = host.CreateFrameReader();
        using var publisher = host.CreateFrameReader();
        host.PublishFrame(first);
        using (var replacement = OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 300, 400))
            host.PublishFrame(replacement);
        Check(preview.Store.PendingDepth == 1 && publisher.Store.PendingDepth == 1 &&
            preview.Store.HighWater == 1 && publisher.Store.HighWater == 1 &&
            preview.Store.Superseded == 1 && publisher.Store.Superseded == 1 &&
            host.Frames.PendingDepth == 1,
            "independent bounded frame readers");
        using var previewFrame = preview.Store.TakeLatest();
        using var publisherFrame = publisher.Store.TakeLatest();
        Check(previewFrame?.Pts100ns == 300 && publisherFrame?.Pts100ns == 300,
            "independent readers receive latest frame");
        host.Frames.Clear();
        first.Dispose();
        try { _ = first.Pixels[0]; throw new Exception("Disposed frame was readable"); }
        catch (ObjectDisposedException) { }
        try { _ = first.Retain(); throw new Exception("Disposed frame was retained"); }
        catch (ObjectDisposedException) { }
        Check(previewFrame!.Pixels[0] == source[0] && publisherFrame!.Pixels[0] == source[0],
            "consumer leases survive producer and other slot release");
        previewFrame.Dispose();
        publisherFrame!.Dispose();
        newest?.Dispose();
        Check(OwnedNv12Frame.ActiveBufferCount == before &&
            OwnedNv12Frame.PooledRentals - rentalsBefore ==
            OwnedNv12Frame.PooledReturns - returnsBefore,
            "all pooled frame buffers returned after independent consumers release");
        Console.WriteLine("Owned NV12 frame and latest-frame handoff self-test passed");
    }

    private static void Check(bool value, string name)
    {
        if (!value) throw new Exception($"Self-test failed: {name}");
    }
    private static async Task RejectsAsync(Func<Task<VideoUnit>> action, string name)
    {
        try { using var unexpected = await action(); throw new Exception($"Self-test failed: {name}"); }
        catch (EndOfStreamException) { }
        catch (InvalidDataException) { }
    }

    private sealed class OneByteStream(byte[] data) : MemoryStream(data)
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            base.ReadAsync(buffer[..Math.Min(buffer.Length, 1)], cancellationToken);
    }
}
