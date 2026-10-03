using System.Buffers.Binary;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Camylla.Receiver;

/** Synthetic TLS integration check. This is never physical Android evidence. */
internal static class LoopbackSelfTest
{
    public static async Task RunAsync(string path)
    {
        var secret = DevelopmentIdentity.LoadOrCreateSecret();
        var pin = SHA256.HashData(DevelopmentIdentity.LoadOrCreate().RawData);
        using var control = await ConnectAsync(58413, pin);
        await Wire.WriteControlAsync(control, new { type = "hello", version = Wire.Version,
            codec = "h264", modes = new[] { "1280x720@30" }, mode = "1280x720@30", lens = "rear", secret }, default);
        using var welcome = await Wire.ReadControlAsync(control, default);
        var session = Wire.RequiredString(welcome.RootElement, "session");
        if (Wire.RequiredString(welcome.RootElement, "type") != "welcome")
            throw new InvalidDataException("Loopback control was not welcomed");

        using (var wrongVideo = await ConnectAsync(58414, pin))
        {
            await Wire.WriteControlAsync(wrongVideo, new { type = "bind", version = Wire.Version,
                session = "00000000000000000000000000000000", secret }, default);
            using var rejected = await Wire.ReadControlAsync(wrongVideo, default);
            if (Wire.RequiredString(rejected.RootElement, "type") != "error")
                throw new InvalidDataException("Mismatched video channel was accepted");
        }

        using var video = await ConnectAsync(58414, pin);
        await Wire.WriteControlAsync(video, new { type = "bind", version = Wire.Version, session, secret }, default);
        using var ready = await Wire.ReadControlAsync(video, default);
        if (Wire.RequiredString(ready.RootElement, "type") != "ready")
            throw new InvalidDataException("Loopback video was not bound");
        var id = Convert.FromHexString(session);
        var units = SelfTest.ReadAccessUnits(path);
        var sequence = 1UL;
        await WriteUnitAsync(video, sequence++, 0, Wire.Config, id, SelfTest.ExtractConfig(units[0]));
        for (var index = 0; index < units.Count; index++)
        {
            var key = index == 0 || index % 30 == 0;
            await WriteUnitAsync(video, sequence++, (ulong)index * 33333,
                key ? Wire.Key : Wire.Normal, id, units[index]);
        }
        // Deliberate sequence gap forces a decoder reset; config + IDR restarts it.
        sequence++;
        await WriteUnitAsync(video, sequence++, (ulong)units.Count * 33333,
            Wire.Config, id, SelfTest.ExtractConfig(units[0]));
        await WriteUnitAsync(video, sequence++, (ulong)(units.Count + 1) * 33333,
            Wire.Key, id, units[0]);
        await video.FlushAsync();
        await Task.Delay(3000);
        Console.WriteLine($"Loopback TLS bound; mismatched channel rejected; sent {units.Count} synthetic access units plus a sequence-gap recovery point");
    }

    private static async Task<SslStream> ConnectAsync(int port, byte[] pin)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync("127.0.0.1", port);
        var stream = new SslStream(tcp.GetStream(), false, (_, certificate, _, _) =>
            certificate is X509Certificate2 leaf &&
            CryptographicOperations.FixedTimeEquals(SHA256.HashData(leaf.RawData), pin));
        try { await stream.AuthenticateAsClientAsync("Camylla M3 development receiver"); return stream; }
        catch { stream.Dispose(); tcp.Dispose(); throw; }
    }

    private static async Task WriteUnitAsync(Stream output, ulong sequence, ulong ptsUs,
        ushort flag, byte[] session, byte[] payload)
    {
        var bytes = new byte[Wire.VideoHeaderSize + payload.Length];
        "CMYV"u8.CopyTo(bytes);
        bytes[4] = Wire.Version;
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(6), Wire.VideoHeaderSize);
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(8), (uint)payload.Length);
        BinaryPrimitives.WriteUInt64BigEndian(bytes.AsSpan(12), sequence);
        BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(20), 1);
        BinaryPrimitives.WriteUInt64BigEndian(bytes.AsSpan(24), ptsUs);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(32), flag);
        BinaryPrimitives.WriteUInt16BigEndian(bytes.AsSpan(34), 1);
        session.CopyTo(bytes, 36);
        BinaryPrimitives.WriteUInt64BigEndian(bytes.AsSpan(52), (ulong)Environment.TickCount64 * 1000);
        payload.CopyTo(bytes, Wire.VideoHeaderSize);
        await output.WriteAsync(bytes);
    }
}
