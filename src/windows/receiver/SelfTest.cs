using System.Buffers.Binary;
using System.Text;
using System.Runtime.InteropServices;
using System.Net;
using System.Net.Sockets;

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
        var parsed = await Wire.ReadVideoAsync(partial, default);
        Check(parsed.Sequence == 5 && parsed.PtsUs == 123456 && parsed.Flags == Wire.Key &&
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
            using var host = new ReceiverHost(control, video);
            host.Log += _ => { };
            using var stop = new CancellationTokenSource();
            var run = host.RunAsync(stop.Token);
            await Task.Delay(100);
            Check(!run.IsCompleted, "receiver listener started");
            stop.Cancel();
            await run.WaitAsync(TimeSpan.FromSeconds(5));
            Check(host.Snapshot().State == "Disconnected" &&
                host.Frames.PendingDepth == 0, "receiver shutdown clears state and frames");
        }
        Console.WriteLine("Receiver start, shutdown, and same-port restart self-test passed");
    }

    private static void TestOwnedLatestFrames()
    {
        var source = Enumerable.Repeat((byte)42, 12).ToArray();
        var first = OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 100, 200);
        source[0] = 99;
        Check(first.Pixels[0] == 42 && first.PixelFormat == "NV12" &&
            first.Width == 4 && first.Height == 2 && first.Stride == 4 &&
            first.Pts100ns == 100 && first.DecodedAtTicks == 200, "managed NV12 ownership and metadata");
        var native = Marshal.AllocHGlobal(source.Length);
        try
        {
            Marshal.Copy(source, 0, native, source.Length);
            var copy = OwnedNv12Frame.CopyFromBorrowed(native, 12, 4, 2, 4, 300, 400);
            Marshal.WriteByte(native, 0, 0);
            Check(copy.Pixels[0] == 99, "borrowed NV12 copied before callback returns");
        }
        finally { Marshal.FreeHGlobal(native); }
        var queue = new LatestFrameStore();
        queue.Publish(first);
        queue.Publish(OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 300, 400));
        Check(queue.PendingDepth == 1 && queue.HighWater == 1 && queue.Superseded == 1 &&
            queue.Published == 2 && queue.TakeLatest()?.Pts100ns == 300 && queue.PendingDepth == 0,
            "latest-frame replacement and bounded depth");
        using var host = new ReceiverHost(58423, 58424);
        using var preview = host.CreateFrameReader();
        using var publisher = host.CreateFrameReader();
        host.PublishFrame(first);
        host.PublishFrame(OwnedNv12Frame.CopyFromManaged(source, 4, 2, 4, 300, 400));
        Check(preview.Store.PendingDepth == 1 && publisher.Store.PendingDepth == 1 &&
            preview.Store.HighWater == 1 && publisher.Store.HighWater == 1 &&
            preview.Store.Superseded == 1 && publisher.Store.Superseded == 1 &&
            preview.Store.TakeLatest()?.Pts100ns == 300 &&
            publisher.Store.TakeLatest()?.Pts100ns == 300,
            "independent bounded frame readers");
        Console.WriteLine("Owned NV12 frame and latest-frame handoff self-test passed");
    }

    private static void Check(bool value, string name)
    {
        if (!value) throw new Exception($"Self-test failed: {name}");
    }
    private static async Task RejectsAsync(Func<Task<VideoUnit>> action, string name)
    {
        try { await action(); throw new Exception($"Self-test failed: {name}"); }
        catch (EndOfStreamException) { }
        catch (InvalidDataException) { }
    }

    private sealed class OneByteStream(byte[] data) : MemoryStream(data)
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            base.ReadAsync(buffer[..Math.Min(buffer.Length, 1)], cancellationToken);
    }
}
