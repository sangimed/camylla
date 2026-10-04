using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Security.AccessControl;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace Camylla.Receiver;

public sealed record TrustedPhone(string Id, string Name, string Certificate, bool Revoked, DateTimeOffset Updated);
public sealed record PairInvitation(string Uri, string PairId, DateTimeOffset Expires, string DesktopId);
public sealed record PairCandidate(string Id, string Name, string ChallengeId, DateTimeOffset Expires,
    string? ReplacesId, bool Approved);

/// <summary>User-scoped v3 identities and trust. Invitations, challenges and session secrets stay in memory.</summary>
public sealed partial class ProductionTrust : IDisposable
{
    private sealed record Database(string Thumbprint, string Name, List<TrustedPhone> Phones,
        List<RotationResult>? Rotations = null, List<string>? RetiredKeys = null);
    private readonly object gate = new();
    private readonly string path;
    private readonly TimeProvider clock;
    private Database database;
    private X509Certificate2 certificate;
    private Invitation? invitation;
    private Challenge? challenge;
    private string? completedPairId;
    private readonly Dictionary<string, Queue<long>> attempts = new();
    private readonly Queue<long> globalAttempts = new();
    private sealed record Invitation(string Id, byte[] Hash, DateTimeOffset Expires, long Created);
    private sealed record Challenge(string PairId, string Id, string PhoneId, string Name,
        string Certificate, long Created, string? ReplacesId, bool Approved);

    public ProductionTrust() : this(null) { }
    internal ProductionTrust(string? testDirectory, TimeProvider? clock = null)
    {
        this.clock = clock ?? TimeProvider.System;
        var directory = testDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Camylla", "product-v3");
        Directory.CreateDirectory(directory);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User!,
            FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(directory).SetAccessControl(security);
        path = Path.Combine(directory, "trust.dpapi");
        if (File.Exists(path))
        {
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
            try { database = JsonSerializer.Deserialize<Database>(plain) ?? throw new InvalidDataException("Invalid trust store"); }
            finally { CryptographicOperations.ZeroMemory(plain); }
            using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
            store.Open(OpenFlags.ReadOnly);
            certificate = store.Certificates.Find(X509FindType.FindByThumbprint, database.Thumbprint, false)
                .Cast<X509Certificate2>().SingleOrDefault() ?? throw new AuthenticationException("Desktop identity missing; re-pair required");
            if (!certificate.HasPrivateKey) throw new AuthenticationException("Desktop private key missing");
            ValidateCertificate(certificate, false);
        }
        else
        {
            certificate = CreateIdentity();
            database = new Database(certificate.Thumbprint, Environment.MachineName, []);
            Save(database);
        }
        CleanupRetiredKeys();
    }

    internal X509Certificate2 Certificate => certificate;
    internal TimeProvider TestClock => clock;
    public string DesktopId { get { lock (gate) return PeerId(certificate); } }
    public string DesktopName { get { lock (gate) return database.Name; } }
    public IReadOnlyList<TrustedPhone> Phones { get { lock (gate) return database.Phones.ToArray(); } }
    public PairCandidate? Candidate
    {
        get { lock (gate) return ValidChallenge() is { } c
            ? new(c.PhoneId, c.Name, c.Id, clock.GetUtcNow().AddSeconds(Math.Max(0, 30 - clock.GetElapsedTime(c.Created).TotalSeconds)), c.ReplacesId, c.Approved) : null; }
    }
    internal bool PairingOpen { get { lock (gate) return ValidInvitation() != null; } }
    public event Action<string>? PeerRevoked;
    public string? CompletedPairId { get { lock (gate) return completedPairId; } }

    public PairInvitation BeginInvitation(string host, int port = 58413)
    {
        ValidateHost(host);
        if (port is < 1 or > 65535) throw new ArgumentException("Invalid control port");
        var cap = RandomHex(32);
        var id = RandomHex(16);
        var expires = clock.GetUtcNow().AddSeconds(120);
        lock (gate)
        {
            if (ValidRotation() != null) throw new AuthenticationException("session_busy");
            invitation = new(id, SHA256.HashData(Convert.FromHexString(cap)), expires, clock.GetTimestamp());
            challenge = null;
        }
        var uri = $"camylla-pair://v3?host={Uri.EscapeDataString(host)}&control_port={port}&desktop_id={DesktopId}&pair_id={id}&cap={cap}&expires_at={expires.ToUnixTimeSeconds()}";
        if (Encoding.UTF8.GetByteCount(uri) > 512) { CancelInvitation(); throw new ArgumentException("Invitation too large"); }
        return new(uri, id, expires, DesktopId);
    }
    public void CancelInvitation() { lock (gate) { invitation = null; challenge = null; } }
    internal object BeginChallenge(JsonElement value, string address)
    {
        lock (gate) LimitAttempts(address);
        V3Record.Require(value, "pair_init", "pair_id", "cap", "phone_id", "client_cert", "name");
        var pairId = V3Record.Hex(value, "pair_id", 16);
        var cap = V3Record.Hex(value, "cap", 32);
        var phoneId = V3Record.Hex(value, "phone_id", 32);
        var name = V3Record.Name(value, "name");
        var certText = V3Record.Certificate(value, "client_cert");
        using var phone = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(certText));
        ValidateCertificate(phone, true);
        if (PeerId(phone) != phoneId) throw new AuthenticationException("pair_rejected");
        lock (gate)
        {
            if (ValidRotation() != null) throw new AuthenticationException("session_busy");
            var invite = ValidInvitation();
            if (invite == null || invite.Id != pairId ||
                !CryptographicOperations.FixedTimeEquals(invite.Hash, SHA256.HashData(Convert.FromHexString(cap))))
                throw new AuthenticationException("pair_rejected");
            invitation = null; // Consume before any response, including an interrupted response.
            var previous = database.Phones.Where(p => p.Id != phoneId && p.Name == name)
                .OrderBy(p => p.Revoked).ThenByDescending(p => p.Updated).FirstOrDefault();
            challenge = new(pairId, RandomHex(16), phoneId, name, certText, clock.GetTimestamp(), previous?.Id, previous == null);
            return new { type = "pair_challenge", version = 3, pair_id = pairId,
                challenge_id = challenge.Id, server_cert = Convert.ToBase64String(certificate.RawData), name = database.Name,
                requires_approval = previous != null, previous_phone_id = previous?.Id };
        }
    }

    internal bool AcceptCertificate(X509Certificate2? remote, bool pairingControl)
    {
        lock (gate)
        {
            if (remote == null) return pairingControl && ValidInvitation() != null;
            try { ValidateCertificate(remote, true); } catch (Exception error) when (error is CryptographicException or AuthenticationException) { return false; }
            var id = PeerId(remote);
            return database.Phones.Any(p => p.Id == id && !p.Revoked) ||
                (pairingControl && ValidChallenge()?.PhoneId == id);
        }
    }
    internal bool IsTrusted(string id) { lock (gate) return database.Phones.Any(p => p.Id == id && !p.Revoked); }
    internal object Commit(JsonElement value, string authenticatedPhone)
    {
        V3Record.Require(value, "pair_commit", "pair_id", "challenge_id");
        lock (gate)
        {
            var c = ValidChallenge();
            if (c == null || !c.Approved || c.PhoneId != authenticatedPhone ||
                V3Record.Hex(value, "pair_id", 16) != c.PairId || V3Record.Hex(value, "challenge_id", 16) != c.Id)
                throw new AuthenticationException("pair_rejected");
            var phones = database.Phones.Where(p => p.Id != c.PhoneId).Select(p =>
                p.Id == c.ReplacesId ? p with { Revoked = true, Updated = clock.GetUtcNow() } : p).ToList();
            if (phones.Count >= 32) throw new AuthenticationException("pair_rejected");
            phones.Add(new(c.PhoneId, c.Name, c.Certificate, false, DateTimeOffset.UtcNow));
            var next = database with { Phones = phones };
            Save(next); database = next; completedPairId = c.PairId; challenge = null;
            return new { type = "pair_ok", version = 3, desktop_id = DesktopId, phone_id = authenticatedPhone };
        }
    }
    public void ApprovePairReplacement(string challengeId, bool approved)
    {
        lock (gate)
        {
            var candidate = ValidChallenge();
            if (candidate == null || candidate.Id != challengeId || candidate.ReplacesId == null)
                throw new AuthenticationException("pair_rejected");
            challenge = approved ? candidate with { Approved = true } : null;
        }
    }
    internal object PairStatus(JsonElement value, string authenticatedPhone)
    {
        V3Record.Require(value, "pair_status", "pair_id", "challenge_id");
        lock (gate)
        {
            var candidate = ValidChallenge();
            if (candidate == null || candidate.PhoneId != authenticatedPhone ||
                V3Record.Hex(value, "pair_id", 16) != candidate.PairId || V3Record.Hex(value, "challenge_id", 16) != candidate.Id)
                throw new AuthenticationException("pair_rejected");
            return new { type = "pair_state", version = 3, challenge_id = candidate.Id,
                state = candidate.Approved ? "approved" : "awaiting_approval" };
        }
    }
    public void Revoke(string id)
    {
        lock (gate)
        {
            var phones = database.Phones.Select(p => p.Id == id ? p with { Revoked = true, Updated = DateTimeOffset.UtcNow } : p).ToList();
            var next = database with { Phones = phones };
            Save(next); database = next;
            if (challenge?.PhoneId == id) challenge = null;
            if (rotation?.Candidate.PhoneId == id) CancelRotationLocked();
        }
        PeerRevoked?.Invoke(id);
    }

    private Invitation? ValidInvitation()
    {
        if (invitation != null && (clock.GetUtcNow() >= invitation.Expires || clock.GetElapsedTime(invitation.Created).TotalSeconds >= 120)) invitation = null;
        return invitation;
    }
    private Challenge? ValidChallenge()
    {
        if (challenge != null && clock.GetElapsedTime(challenge.Created).TotalSeconds >= 30) challenge = null;
        return challenge;
    }
    private void LimitAttempts(string address)
    {
        var now = clock.GetTimestamp();
        while (globalAttempts.TryPeek(out var stamp) && clock.GetElapsedTime(stamp, now).TotalSeconds >= 60) globalAttempts.Dequeue();
        foreach (var key in attempts.Where(p => !p.Value.Any(t => clock.GetElapsedTime(t, now).TotalMinutes < 10)).Select(p => p.Key).ToArray()) attempts.Remove(key);
        if (!attempts.TryGetValue(address, out var queue))
        {
            if (attempts.Count >= 128) throw new AuthenticationException("pair_rejected");
            attempts[address] = queue = new();
        }
        while (queue.TryPeek(out var stamp) && clock.GetElapsedTime(stamp, now).TotalMinutes >= 10) queue.Dequeue();
        if (globalAttempts.Count >= 5 || queue.Count >= 5) throw new AuthenticationException("pair_rejected");
        queue.Enqueue(now); globalAttempts.Enqueue(now);
    }
    private void Save(Database value)
    {
        var plain = JsonSerializer.SerializeToUtf8Bytes(value);
        try
        {
            var protectedBytes = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
            var temp = path + ".new";
            using (var output = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None,
                4096, FileOptions.WriteThrough))
            { output.Write(protectedBytes); output.Flush(flushToDisk: true); }
            File.Move(temp, path, true);
        }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }
    private static X509Certificate2 CreateIdentity()
    {
        using var key = CngKey.Create(CngAlgorithm.ECDsaP256, "Camylla-v3-" + Guid.NewGuid().ToString("N"),
            new CngKeyCreationParameters { ExportPolicy = CngExportPolicies.None, KeyCreationOptions = CngKeyCreationOptions.None });
        using var signing = new ECDsaCng(key);
        var request = new CertificateRequest("CN=Camylla desktop", signing, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
        var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(5));
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite); store.Add(cert);
        return cert;
    }
    public static string PeerId(X509Certificate2 cert)
    {
        using var key = cert.GetECDsaPublicKey() ?? throw new AuthenticationException("P-256 identity required");
        return Convert.ToHexStringLower(SHA256.HashData(key.ExportSubjectPublicKeyInfo()));
    }
    internal static void ValidateCertificate(X509Certificate2 cert, bool client)
    {
        using var key = cert.GetECDsaPublicKey();
        if (key?.KeySize != 256 || key.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7" || cert.SignatureAlgorithm.Value != "1.2.840.10045.4.3.2" ||
            DateTime.UtcNow < cert.NotBefore.ToUniversalTime() || DateTime.UtcNow > cert.NotAfter.ToUniversalTime())
            throw new AuthenticationException("Invalid identity certificate");
        foreach (var extension in cert.Extensions)
        {
            if (extension is X509KeyUsageExtension usage && !usage.KeyUsages.HasFlag(X509KeyUsageFlags.DigitalSignature))
                throw new AuthenticationException("Invalid identity key usage");
            if (extension is X509EnhancedKeyUsageExtension eku && !eku.EnhancedKeyUsages.Cast<Oid>().Any(o => o.Value == (client ? "1.3.6.1.5.5.7.3.2" : "1.3.6.1.5.5.7.3.1")))
                throw new AuthenticationException("Invalid identity extended usage");
        }
        using var chain = new X509Chain();
        chain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        chain.ChainPolicy.CustomTrustStore.Add(cert);
        chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        if (!chain.Build(cert)) throw new AuthenticationException("Invalid identity chain");
    }
    internal static string RandomHex(int bytes) => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(bytes));
    public static void ValidateHost(string host)
    {
        if (host.Length is 0 or > 253 || host.Any(c => c > 127 || char.IsWhiteSpace(c)) ||
            Uri.CheckHostName(host.Trim('[', ']')) == UriHostNameType.Unknown) throw new ArgumentException("Invalid LAN address");
    }
    public static string LanAddress() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
            n.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)))
        .SelectMany(n => n.GetIPProperties().UnicastAddresses)
        .FirstOrDefault(a => a.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)?.Address.ToString() ?? "";
    public void Dispose() { lock (gate) { CancelInvitation(); CancelRotationLocked(); certificate.Dispose(); } }
}

internal static class V3Record
{
    public static void Require(JsonElement value, string type, params string[] fields)
    {
        if (value.ValueKind != JsonValueKind.Object || Wire.RequiredString(value, "type") != type || Wire.RequiredInt(value, "version") != 3)
            throw new InvalidDataException("invalid_record");
        var allowed = fields.Concat(new[] { "type", "version" }).ToHashSet(StringComparer.Ordinal);
        if (value.EnumerateObject().Any(p => !allowed.Remove(p.Name)) || allowed.Count != 0)
            throw new InvalidDataException("invalid_record");
    }
    public static string Hex(JsonElement value, string field, int bytes)
    {
        var text = Wire.RequiredString(value, field);
        if (text.Length != bytes * 2 || text.Any(c => c is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new InvalidDataException("invalid_record");
        return text;
    }
    public static string Name(JsonElement value, string field)
    {
        var text = Wire.RequiredString(value, field);
        if (Encoding.UTF8.GetByteCount(text) is < 1 or > 64 || text != text.Normalize(NormalizationForm.FormC) || text.Any(char.IsControl))
            throw new InvalidDataException("invalid_record");
        return text;
    }
    public static string Certificate(JsonElement value, string field)
    {
        var text = Wire.RequiredString(value, field);
        if (text.Length is < 1 or > 2048) throw new InvalidDataException("invalid_record");
        return text;
    }
}
