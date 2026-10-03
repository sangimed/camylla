using System.Buffers.Binary;
using System.Text.Json;
using System.Security.Authentication;
using System.Security.Cryptography;

namespace Camylla.Receiver;

internal static class Wire
{
    public const int Version = 2;
    public const int VideoHeaderSize = 64;
    public const int MaxVideoBytes = 4 * 1024 * 1024;
    public const int MaxControlBytes = 4096;
    public const int MaxPendingMessages = 1;
    public const int MaxRetryCount = 20;
    public const ushort Config = 2;
    public const ushort Key = 1;
    public const ushort Normal = 4;

    public static async Task<JsonDocument> ReadControlAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[4];
        await stream.ReadExactlyAsync(header, ct);
        var length = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (length is 0 or > MaxControlBytes) throw new InvalidDataException("Invalid control length");
        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, ct);
        return JsonDocument.Parse(payload);
    }

    public static async Task WriteControlAsync(Stream stream, object value, CancellationToken ct)
    {
        var payload = JsonSerializer.SerializeToUtf8Bytes(value);
        if (payload.Length > MaxControlBytes) throw new InvalidDataException("Control message too large");
        var header = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)payload.Length);
        await stream.WriteAsync(header, ct);
        await stream.WriteAsync(payload, ct);
        await stream.FlushAsync(ct);
    }

    public static async Task<VideoUnit> ReadVideoAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[VideoHeaderSize];
        await stream.ReadExactlyAsync(header, ct);
        if (!header.AsSpan(0, 4).SequenceEqual("CMYV"u8) || header[4] != Version || header[5] != 0 ||
            BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(6)) != VideoHeaderSize)
            throw new InvalidDataException("Unsupported video header or version");
        var length = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(8));
        if (length is 0 or > MaxVideoBytes) throw new InvalidDataException("Invalid video length");
        var flags = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(32));
        if (flags is not (Config or Key or Normal) || BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(34)) != 1 ||
            BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(60)) != 0)
            throw new InvalidDataException("Invalid video flags/type/reserved field");
        var payload = new byte[length];
        await stream.ReadExactlyAsync(payload, ct);
        return new VideoUnit(
            BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(12)),
            BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(20)),
            BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(24)), flags,
            Convert.ToHexString(header.AsSpan(36, 16)),
            BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(52)), payload);
    }

    public static string RequiredString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? throw new InvalidDataException($"Missing {name}")
            : throw new InvalidDataException($"Missing {name}");

    public static int RequiredInt(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.TryGetInt32(out var result)
            ? result : throw new InvalidDataException($"Missing {name}");

    public static bool FixedHexEquals(string a, string b)
    {
        if (a.Length != b.Length) return false;
        try { return CryptographicOperations.FixedTimeEquals(Convert.FromHexString(a), Convert.FromHexString(b)); }
        catch (FormatException) { return false; }
    }

    public static void RequireBoundSession(string presented, string active)
    {
        if (!FixedHexEquals(presented, active))
            throw new AuthenticationException("Control/video session mismatch");
    }
}

internal sealed record VideoUnit(ulong Sequence, uint Generation, ulong PtsUs, ushort Flags,
    string SessionId, ulong AndroidSendUs, byte[] Payload);
