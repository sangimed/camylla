using Camylla.Receiver;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

internal static partial class ProductionSelfTest
{
    public static async Task RunRotationAsync()
    {
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Camylla", "test-v3", Guid.NewGuid().ToString("N"));
        using var trust = new ProductionTrust(directory);
        using var phone = TestIdentity("Rotation original phone");
        using var nextPhone = TestIdentity("Rotation new phone");
        using var other = TestIdentity("Untrusted rotation phone");
        var phoneId = ProductionTrust.PeerId(phone);
        var nextPhoneId = ProductionTrust.PeerId(nextPhone);
        SeedPhone(trust, phone);
        var desktopId = trust.DesktopId;
        var controlPort = FreePort(); var videoPort = FreePort();
        while (videoPort == controlPort) videoPort = FreePort();
        using var host = new ReceiverHost(controlPort, videoPort, trust);
        using var lifetime = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        var ct = lifetime.Token;
        var run = host.RunAsync(ct);
        try
        {
            await Task.Delay(100, ct);
            var id = ProductionTrust.RandomHex(16);
            object Offer() => new { type = "rotate_offer", version = 3, role = "phone", old_id = phoneId,
                new_id = nextPhoneId, new_cert = Convert.ToBase64String(nextPhone.RawData), rotation_id = id };
            using (var control = await Connect(controlPort, desktopId, phone, ct))
            {
                await ExchangeType(control, Offer(), "rotate_pending", ct);
                Check(trust.Rotation is { Approved: false, Ready: false }, "phone replacement requires desktop approval");
                try { trust.BeginInvitation("127.0.0.1"); throw new Exception("Pairing admitted during rotation"); }
                catch (AuthenticationException) { }
                using (var start = await Connect(controlPort, desktopId, phone, ct))
                    await ExchangeType(start, Start(phoneId), "error", ct);
                await ExpectTlsRejected(videoPort, desktopId, nextPhone, ct);
                trust.ApproveRotation(id, true);
                await ExpectTlsRejected(videoPort, desktopId, phone, ct);
                await ExpectTlsRejected(videoPort, desktopId, other, ct);
                using (var wrong = await Connect(videoPort, desktopId, nextPhone, ct))
                {
                    await Wire.WriteControlAsync(wrong, new { type = "rotate_prove", version = 3, role = "desktop",
                        old_id = phoneId, new_id = nextPhoneId, rotation_id = id }, ct);
                    try { using var result = await Wire.ReadControlAsync(wrong, ct); throw new Exception("Wrong proof accepted"); }
                    catch (IOException) { }
                }
                Check(trust.Rotation is { Ready: false }, "wrong proof does not change state");
                using (var proof = await Connect(videoPort, desktopId, nextPhone, ct))
                    await ExchangeType(proof, Proof(id, "phone", phoneId, nextPhoneId), "rotate_ready", ct);
                Check(!trust.IsTrusted(nextPhoneId) && trust.IsTrusted(phoneId), "ready is not a trust commit");
            }
            // Ready must survive loss of the original control socket, without another proof.
            using (var resumed = await Connect(controlPort, desktopId, phone, ct))
            {
                Check(await Status(resumed, id, ct) == "ready", "ready survives authenticated control reconnect");
                await ExchangeType(resumed, new { type = "rotate_commit", version = 3, rotation_id = id }, "rotate_ok", ct);
            }
            Check(!trust.IsTrusted(phoneId) && trust.IsTrusted(nextPhoneId), "old phone retired atomically");
            await ExpectTlsRejected(controlPort, desktopId, phone, ct);
            await ExpectTlsRejected(videoPort, desktopId, phone, ct);
            using (var current = await Connect(controlPort, desktopId, nextPhone, ct))
                Check(await Status(current, id, ct) == "committed", "phone committed recovery status");
            using (var restored = new ProductionTrust(directory))
                Check(restored.IsTrusted(nextPhoneId) && !restored.IsTrusted(phoneId), "phone rotation survives protected-store reload");

            var candidate = host.BeginDesktopRotation(nextPhoneId);
            trust.ApproveRotation(candidate.RotationId, true);
            using (var control = await Connect(controlPort, desktopId, nextPhone, ct))
            {
                await ExchangeType(control, new { type = "rotate_request", version = 3 }, "rotate_offer", ct);
                await ExpectTlsRejected(videoPort, desktopId, nextPhone, ct); // Proof endpoint must present only the new pin.
                using (var wrong = await Connect(videoPort, candidate.NewId, nextPhone, ct))
                {
                    await Wire.WriteControlAsync(wrong, new { type = "bind", version = 3 }, ct);
                    try { using var result = await Wire.ReadControlAsync(wrong, ct); throw new Exception("Media admitted during rotation"); }
                    catch (IOException) { }
                }
                using (var proof = await Connect(videoPort, candidate.NewId, nextPhone, ct))
                    await ExchangeType(proof, Proof(candidate.RotationId, "desktop", desktopId, candidate.NewId), "rotate_ready", ct);
                await ExpectTlsRejected(videoPort, candidate.NewId, nextPhone, ct); // Proof is single-use.
                await Wire.WriteControlAsync(control, new { type = "rotate_commit", version = 3, rotation_id = candidate.RotationId }, ct);
                // Drop the connection without reading rotate_ok, exercising recovery after lost acknowledgement.
            }
            for (var index = 0; index < 100 && trust.DesktopId != candidate.NewId; index++) await Task.Delay(20, ct);
            Check(trust.DesktopId == candidate.NewId, "desktop commit despite lost rotate_ok");
            using (var probe = await Connect(controlPort, candidate.NewId, nextPhone, ct))
                Check(await Status(probe, candidate.RotationId, ct) == "committed", "new desktop probe recovers lost rotate_ok");
            await ExpectTlsRejected(controlPort, desktopId, nextPhone, ct);
            using (var restored = new ProductionTrust(directory))
                Check(restored.DesktopId == candidate.NewId, "new CNG identity survives reload");

            using (var active = await Connect(controlPort, candidate.NewId, nextPhone, ct))
            {
                await Wire.WriteControlAsync(active, Start(nextPhoneId), ct);
                using var started = await Wire.ReadControlAsync(active, ct);
                Check(Wire.RequiredString(started.RootElement, "type") == "started", "new pair can start");
                try { host.BeginDesktopRotation(nextPhoneId); throw new Exception("Rotation admitted during capture"); }
                catch (AuthenticationException) { }
                var value = started.RootElement;
                using var video = await Connect(videoPort, candidate.NewId, nextPhone, ct);
                await ExchangeType(video, new { type = "bind", version = 3, session_id = Wire.RequiredString(value, "session_id"),
                    phone_id = nextPhoneId, client_nonce = Wire.RequiredString(value, "client_nonce"), server_nonce = Wire.RequiredString(value, "server_nonce"),
                    video_ticket = Wire.RequiredString(value, "video_ticket") }, "ready", ct);
                await Wire.WriteControlAsync(active, new { type = "stop", version = 3, session_id = Wire.RequiredString(value, "session_id") }, ct);
                using var reply = await Wire.ReadControlAsync(active, ct);
                if (Wire.RequiredString(reply.RootElement, "type") == "sync")
                { using var stopped = await Wire.ReadControlAsync(active, ct); Check(Wire.RequiredString(stopped.RootElement, "type") == "stopped", "Stop after rotation"); }
                else Check(Wire.RequiredString(reply.RootElement, "type") == "stopped", "Stop after rotation");
            }
        }
        finally { lifetime.Cancel(); await run; }

        var clock = new TestClock();
        using var timed = new ProductionTrust(Path.Combine(directory, "expiry"), clock);
        SeedPhone(timed, nextPhone);
        var original = timed.DesktopId;
        var pending = timed.PrepareDesktopRotation(nextPhoneId);
        timed.ApproveRotation(pending.RotationId, true);
        clock.Advance(TimeSpan.FromSeconds(60)); clock.RollBackWall(TimeSpan.FromHours(1));
        Check(timed.Rotation == null && timed.DesktopId == original, "rotation expires monotonically without changing old identity");
        pending = timed.PrepareDesktopRotation(nextPhoneId); timed.ApproveRotation(pending.RotationId, false);
        Check(timed.Rotation == null && timed.DesktopId == original, "rejection retains old identity");
        pending = timed.PrepareDesktopRotation(nextPhoneId); timed.Revoke(nextPhoneId);
        Check(timed.Rotation == null, "revocation cancels pending rotation");
        Console.WriteLine("v3 rotation: phone/desktop mutual-TLS proof, approval, media exclusion, single-use proof, control reconnect, lost rotate_ok, persistence, old-key rejection, idle-only and expiry passed");
    }
    private static void SeedPhone(ProductionTrust trust, X509Certificate2 phone)
    {
        var invite = trust.BeginInvitation("127.0.0.1");
        var cap = new Uri(invite.Uri).Query.TrimStart('?').Split('&').Select(p => p.Split('=', 2)).Single(p => p[0] == "cap")[1];
        var phoneId = ProductionTrust.PeerId(phone);
        trust.BeginChallenge(JsonSerializer.SerializeToElement(new { type = "pair_init", version = 3, pair_id = invite.PairId,
            cap, phone_id = phoneId, client_cert = Convert.ToBase64String(phone.RawData), name = "Rotation test phone" }), "127.0.0.1");
        trust.Commit(JsonSerializer.SerializeToElement(new { type = "pair_commit", version = 3, pair_id = invite.PairId,
            challenge_id = trust.Candidate!.ChallengeId }), phoneId);
    }
    private static object Start(string phoneId) => new { type = "start", version = 3, phone_id = phoneId,
        client_nonce = ProductionTrust.RandomHex(16), codec = "h264", modes = new[] { "1280x720@30" }, mode = "1280x720@30", lens = "rear" };
    private static object Proof(string id, string role, string oldId, string newId) =>
        new { type = "rotate_prove", version = 3, rotation_id = id, role, old_id = oldId, new_id = newId };
    private static async Task ExchangeType(SslStream stream, object record, string expected, CancellationToken ct)
    {
        await Wire.WriteControlAsync(stream, record, ct);
        using var result = await Wire.ReadControlAsync(stream, ct);
        Check(Wire.RequiredString(result.RootElement, "type") == expected, "expected " + expected);
    }
    private static async Task<string> Status(SslStream stream, string id, CancellationToken ct)
    {
        await Wire.WriteControlAsync(stream, new { type = "rotation_status", version = 3, rotation_id = id }, ct);
        using var result = await Wire.ReadControlAsync(stream, ct);
        Check(Wire.RequiredString(result.RootElement, "type") == "rotation_state", "rotation status response");
        return Wire.RequiredString(result.RootElement, "state");
    }
}
