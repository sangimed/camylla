using Camylla.Receiver;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

internal static partial class ProductionSelfTest
{
    public static async Task RunAsync()
    {
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Camylla", "test-v3", Guid.NewGuid().ToString("N"));
        using var trust = new ProductionTrust(directory);
        using var phone = TestIdentity("Test phone");
        var phoneId = ProductionTrust.PeerId(phone);
        using var other = TestIdentity("Untrusted");
        using (var replacement = new ProductionTrust(Path.Combine(directory, "replacement")))
        {
            SeedPhone(replacement, phone);
            var invite = replacement.BeginInvitation("127.0.0.1");
            var fields = new Uri(invite.Uri).Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2))
                .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
            replacement.BeginChallenge(JsonSerializer.SerializeToElement(new { type = "pair_init", version = 3,
                pair_id = invite.PairId, cap = fields["cap"], phone_id = ProductionTrust.PeerId(other),
                client_cert = Convert.ToBase64String(other.RawData), name = "Rotation test phone" }), "127.0.0.1");
            var candidate = replacement.Candidate!;
            Check(candidate.ReplacesId == phoneId && !candidate.Approved, "changed identity requires PC approval");
            var commit = JsonSerializer.SerializeToElement(new { type = "pair_commit", version = 3,
                pair_id = invite.PairId, challenge_id = candidate.ChallengeId });
            try { replacement.Commit(commit, candidate.Id); throw new Exception("Unapproved replacement accepted"); }
            catch (AuthenticationException) { }
            Check(replacement.IsTrusted(phoneId) && !replacement.IsTrusted(candidate.Id), "unapproved replacement preserves trust");
            replacement.ApprovePairReplacement(candidate.ChallengeId, true);
            string? retired = null;
            replacement.PeerRevoked += id => retired = id;
            replacement.Commit(commit, candidate.Id);
            Check(retired == phoneId, "replacement notifies active-session retirement");
            Check(!replacement.IsTrusted(phoneId) && replacement.IsTrusted(candidate.Id), "approved replacement atomically revokes old identity");
            using var restored = new ProductionTrust(Path.Combine(directory, "replacement"));
            Check(!restored.IsTrusted(phoneId) && restored.IsTrusted(candidate.Id), "replacement persists across reload");
            var repeated = replacement.BeginInvitation("127.0.0.1");
            var repeatedFields = new Uri(repeated.Uri).Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2))
                .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
            replacement.BeginChallenge(JsonSerializer.SerializeToElement(new { type = "pair_init", version = 3,
                pair_id = repeated.PairId, cap = repeatedFields["cap"], phone_id = candidate.Id,
                client_cert = Convert.ToBase64String(other.RawData), name = "Rotation test phone" }), "127.0.0.1");
            Check(replacement.Candidate is { Approved: true, ReplacesId: null }, "known current identity re-pairs automatically despite retired same-name keys");
        }
        using (var timeTrust = new ProductionTrust(Path.Combine(directory, "expiry"), new TestClock()))
        {
            var clock = (TestClock)timeTrust.TestClock;
            timeTrust.BeginInvitation("127.0.0.1");
            clock.Advance(TimeSpan.FromSeconds(120));
            Check(!timeTrust.PairingOpen, "invitation expires at 120 seconds");
            timeTrust.BeginInvitation("127.0.0.1");
            clock.Advance(TimeSpan.FromSeconds(120)); clock.RollBackWall(TimeSpan.FromHours(1));
            Check(!timeTrust.PairingOpen, "wall rollback cannot extend invitation");
            var timed = timeTrust.BeginInvitation("127.0.0.1");
            var fields = new Uri(timed.Uri).Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2)).ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
            var init = JsonSerializer.SerializeToElement(new { type = "pair_init", version = 3, pair_id = timed.PairId,
                cap = fields["cap"], phone_id = phoneId, client_cert = Convert.ToBase64String(phone.RawData), name = "Test phone" });
            timeTrust.BeginChallenge(init, "127.0.0.1");
            try { timeTrust.BeginChallenge(init, "127.0.0.1"); throw new Exception("Consumed capability replay accepted"); }
            catch (AuthenticationException) { }
            clock.Advance(TimeSpan.FromSeconds(30));
            Check(timeTrust.Candidate == null, "challenge expires at 30 seconds");
            timeTrust.BeginInvitation("127.0.0.1"); timeTrust.CancelInvitation();
            Check(!timeTrust.PairingOpen, "dismissed invitation invalidated");
            using var restarted = new ProductionTrust(Path.Combine(directory, "expiry"));
            Check(!restarted.PairingOpen && restarted.Candidate == null, "invitations/challenges never survive restart");
        }
        var controlPort = FreePort(); var videoPort = FreePort();
        using var host = new ReceiverHost(controlPort, videoPort, trust);
        using var lifetime = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var run = host.RunAsync(lifetime.Token);
        try
        {
            await Task.Delay(100, lifetime.Token);
            var invite = trust.BeginInvitation("127.0.0.1", controlPort);
            var fields = new Uri(invite.Uri).Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2))
                .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));
            object PairInit() => new { type = "pair_init", version = 3, pair_id = invite.PairId,
                cap = fields["cap"], phone_id = phoneId, client_cert = Convert.ToBase64String(phone.RawData), name = "Test phone" };
            string challengeId;
            using (var initial = await Connect(controlPort, trust.DesktopId, null, lifetime.Token))
            {
                await Wire.WriteControlAsync(initial, PairInit(), lifetime.Token);
                using var challenge = await Wire.ReadControlAsync(initial, lifetime.Token);
                Check(Wire.RequiredString(challenge.RootElement, "type") == "pair_challenge", "pair_init returns challenge");
                challengeId = Wire.RequiredString(challenge.RootElement, "challenge_id");
            }
            Check(!trust.PairingOpen && trust.Phones.Count == 0, "invitation consumed before trust");
            using (var commit = await Connect(controlPort, trust.DesktopId, phone, lifetime.Token))
            {
                await Wire.WriteControlAsync(commit, new { type = "pair_commit", version = 3,
                    pair_id = invite.PairId, challenge_id = challengeId }, lifetime.Token);
                using var result = await Wire.ReadControlAsync(commit, lifetime.Token);
                Check(Wire.RequiredString(result.RootElement, "type") == "pair_ok", "mutual TLS pairing commits trust");
            }
            using (var restored = new ProductionTrust(directory))
                Check(restored.DesktopId == trust.DesktopId && restored.IsTrusted(phoneId), "DPAPI/CNG trust survives reload");
            using (var control = await Connect(controlPort, trust.DesktopId, phone, lifetime.Token))
            {
                var nonce = ProductionTrust.RandomHex(16);
                await Wire.WriteControlAsync(control, new { type = "start", version = 3, phone_id = phoneId,
                    client_nonce = nonce, codec = "h264", modes = new[] { "1280x720@30" }, mode = "1280x720@30", lens = "rear" }, lifetime.Token);
                using var started = await Wire.ReadControlAsync(control, lifetime.Token);
                Check(Wire.RequiredString(started.RootElement, "type") == "started", "trusted start accepted");
                var value = started.RootElement;
                object Bind(string? id = null, string? ticket = null) => new { type = "bind", version = 3,
                    session_id = id ?? Wire.RequiredString(value, "session_id"), phone_id = phoneId,
                    client_nonce = nonce, server_nonce = Wire.RequiredString(value, "server_nonce"),
                    video_ticket = ticket ?? Wire.RequiredString(value, "video_ticket") };
                using (var stale = await Connect(videoPort, trust.DesktopId, phone, lifetime.Token))
                {
                    await Wire.WriteControlAsync(stale, Bind(id: ProductionTrust.RandomHex(16)), lifetime.Token);
                    using var rejected = await Wire.ReadControlAsync(stale, lifetime.Token);
                    Check(Wire.RequiredString(rejected.RootElement, "type") == "error", "stale bind rejected");
                }
                using var video = await Connect(videoPort, trust.DesktopId, phone, lifetime.Token);
                await Wire.WriteControlAsync(video, Bind(), lifetime.Token);
                using var ready = await Wire.ReadControlAsync(video, lifetime.Token);
                Check(Wire.RequiredString(ready.RootElement, "type") == "ready", "same mutual TLS identity binds video");
                using var sync = await Wire.ReadControlAsync(control, lifetime.Token);
                Check(Wire.RequiredString(sync.RootElement, "type") == "sync", "video requests fresh config/keyframe");
                using (var duplicate = await Connect(videoPort, trust.DesktopId, phone, lifetime.Token))
                {
                    await Wire.WriteControlAsync(duplicate, Bind(), lifetime.Token);
                    using var rejected = await Wire.ReadControlAsync(duplicate, lifetime.Token);
                    Check(Wire.RequiredString(rejected.RootElement, "type") == "error", "duplicate ticket/bind rejected");
                }
                await Wire.WriteControlAsync(control, new { type = "stop", version = 3,
                    session_id = Wire.RequiredString(value, "session_id") }, lifetime.Token);
                using var stopped = await Wire.ReadControlAsync(control, lifetime.Token);
                Check(Wire.RequiredString(stopped.RootElement, "type") == "stopped", "Stop retires session");
            }
            await Task.Delay(100, lifetime.Token);
            Check(host.Snapshot().State == "Disconnected" && host.Frames.PendingDepth == 0, "Stop neutral state");
            trust.Revoke(phoneId);
            Check(!trust.IsTrusted(phoneId), "revocation persists");
            using (var restored = new ProductionTrust(directory)) Check(!restored.IsTrusted(phoneId), "revocation survives reload");
            await ExpectTlsRejected(controlPort, trust.DesktopId, phone, lifetime.Token);
            await ExpectTlsRejected(controlPort, trust.DesktopId, other, lifetime.Token);
            await ExpectTlsRejected(controlPort, new string('0', 64), phone, lifetime.Token);
            Console.WriteLine("v3 pairing: one-use invitation, mutual TLS control/video, protected repeat trust, stale/duplicate binds, Stop, revocation and wrong identities passed");
        }
        finally { lifetime.Cancel(); host.Stop(); await run; }
    }
    private static X509Certificate2 TestIdentity(string name)
    {
        using var key = CngKey.Create(CngAlgorithm.ECDsaP256, "Camylla-v3-test-" + Guid.NewGuid().ToString("N"),
            new CngKeyCreationParameters { ExportPolicy = CngExportPolicies.None });
        using var signer = new ECDsaCng(key);
        var request = new CertificateRequest("CN=" + name, signer, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.2") }, true));
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite); store.Add(created);
        return store.Certificates.Find(X509FindType.FindByThumbprint, created.Thumbprint, false)[0];
    }
    private sealed class TestClock : TimeProvider
    {
        private DateTimeOffset utc = DateTimeOffset.UtcNow;
        private long timestamp;
        public override DateTimeOffset GetUtcNow() => utc;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => timestamp;
        public void Advance(TimeSpan elapsed) { utc += elapsed; timestamp += (long)elapsed.TotalMilliseconds; }
        public void RollBackWall(TimeSpan elapsed) { utc -= elapsed; }
    }
    private static async Task ExpectTlsRejected(int port, string pin, X509Certificate2 certificate, CancellationToken ct)
    {
        try
        {
            using var tls = await Connect(port, pin, certificate, ct);
            await Wire.WriteControlAsync(tls, new { type = "ping", version = 3 }, ct);
            using var response = await Wire.ReadControlAsync(tls, ct);
            throw new Exception("Untrusted TLS peer accepted");
        }
        catch (Exception error) when (error is AuthenticationException or IOException) { }
    }
    private static async Task<SslStream> Connect(int port, string id, X509Certificate2? phone, CancellationToken ct)
    {
        var client = new TcpClient();
        try
        {
            await client.ConnectAsync(IPAddress.Loopback, port, ct);
            var tls = new SslStream(client.GetStream(), false);
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "localhost", EnabledSslProtocols = SslProtocols.Tls13, AllowTlsResume = false,
                ClientCertificates = phone == null ? null : new X509CertificateCollection { phone },
                LocalCertificateSelectionCallback = phone == null ? null : (_, _, _, _, _) => phone,
                RemoteCertificateValidationCallback = (_, remote, _, _) =>
                {
                    if (remote == null) return false;
                    using var certificate = X509CertificateLoader.LoadCertificate(remote.GetRawCertData());
                    ProductionTrust.ValidateCertificate(certificate, false);
                    return ProductionTrust.PeerId(certificate) == id;
                }
            }, ct);
            return tls;
        }
        catch { client.Dispose(); throw; }
    }
    private static int FreePort() { var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return port; }
    private static void Check(bool condition, string message) { if (!condition) throw new Exception("v3 self-test failed: " + message); }
}
