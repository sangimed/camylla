using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Camylla.Receiver;

public sealed record RotationCandidate(string RotationId, string Role, string OldId, string NewId,
    string NewCertificate, string PhoneId, DateTimeOffset Expires, bool Approved, bool Ready);

public sealed partial class ProductionTrust
{
    private sealed record RotationResult(string RotationId, string Role, string OldId, string NewId,
        string PhoneId, DateTimeOffset Committed);
    private sealed record PendingRotation(RotationCandidate Candidate, long Created, Guid? Control);
    private PendingRotation? rotation;
    private X509Certificate2? rotationCertificate;

    public RotationCandidate? Rotation { get { lock (gate) return ValidRotation()?.Candidate; } }
    internal X509Certificate2 CopyControlCertificate() { lock (gate) return new(certificate); }
    internal X509Certificate2 CopyProofCertificate(string rotationId)
    {
        lock (gate)
        {
            var pending = RequireRotation(rotationId);
            return new(pending.Candidate.Role == "desktop" ? rotationCertificate! : certificate);
        }
    }
    internal RotationCandidate PrepareDesktopRotation(string phoneId)
    {
        lock (gate)
        {
            if (!IsTrusted(phoneId) || ValidRotation() != null) throw new AuthenticationException("session_busy");
            EnsureRotationCapacity();
            var next = CreateIdentity();
            CancelInvitation();
            rotationCertificate = next;
            var candidate = new RotationCandidate(RandomHex(16), "desktop", DesktopId, PeerId(next),
                Convert.ToBase64String(next.RawData), phoneId, clock.GetUtcNow().AddSeconds(60), false, false);
            rotation = new(candidate, clock.GetTimestamp(), null);
            return candidate;
        }
    }
    internal object OfferPhoneRotation(JsonElement value, string phoneId, Guid connection)
    {
        V3Record.Require(value, "rotate_offer", "role", "old_id", "new_id", "new_cert", "rotation_id");
        var id = V3Record.Hex(value, "rotation_id", 16);
        var oldId = V3Record.Hex(value, "old_id", 32);
        var newId = V3Record.Hex(value, "new_id", 32);
        var encoded = V3Record.Certificate(value, "new_cert");
        using var next = X509CertificateLoader.LoadCertificate(Convert.FromBase64String(encoded));
        ValidateCertificate(next, true);
        if (Wire.RequiredString(value, "role") != "phone" || oldId != phoneId || newId == oldId || PeerId(next) != newId)
            throw new AuthenticationException("identity_mismatch");
        lock (gate)
        {
            if (!IsTrusted(phoneId)) throw new AuthenticationException("untrusted_peer");
            if ((database.Rotations ?? []).Any(r => r.RotationId == id)) throw new AuthenticationException("stale_session");
            if (ValidRotation() is { } existing)
            {
                if (existing.Candidate.RotationId != id || existing.Candidate.Role != "phone" ||
                    existing.Candidate.PhoneId != phoneId || existing.Candidate.NewId != newId ||
                    existing.Candidate.NewCertificate != encoded) throw new AuthenticationException("session_busy");
                rotation = existing with { Control = connection };
            }
            else
            {
                EnsureRotationCapacity();
                if (database.Phones.Count >= 32 || database.Phones.Any(p => p.Id == newId))
                    throw new AuthenticationException("session_busy");
                CancelInvitation();
                rotation = new(new(id, "phone", oldId, newId, encoded, phoneId,
                    clock.GetUtcNow().AddSeconds(60), false, false), clock.GetTimestamp(), connection);
            }
            return new { type = "rotate_pending", version = 3, rotation_id = id };
        }
    }
    internal object DesktopRotationOffer(string phoneId, Guid connection)
    {
        lock (gate)
        {
            var pending = ValidRotation() ?? throw new AuthenticationException("stale_session");
            var candidate = pending.Candidate;
            if (candidate.Role != "desktop" || candidate.PhoneId != phoneId || !IsTrusted(phoneId))
                throw new AuthenticationException("untrusted_peer");
            rotation = pending with { Control = connection };
            return new { type = "rotate_offer", version = 3, role = candidate.Role, old_id = candidate.OldId,
                new_id = candidate.NewId, new_cert = candidate.NewCertificate, rotation_id = candidate.RotationId };
        }
    }
    public void ApproveRotation(string id, bool approve)
    {
        lock (gate)
        {
            var pending = RequireRotation(id);
            if (!IsTrusted(pending.Candidate.PhoneId)) throw new AuthenticationException("untrusted_peer");
            if (approve) rotation = pending with { Candidate = pending.Candidate with { Approved = true } };
            else CancelRotationLocked();
        }
    }
    internal bool AcceptProofCertificate(X509Certificate2? remote, string id)
    {
        if (remote == null) return false;
        try { ValidateCertificate(remote, true); }
        catch (Exception error) when (error is CryptographicException or AuthenticationException) { return false; }
        lock (gate)
        {
            var pending = ValidRotation();
            if (pending == null || pending.Candidate.RotationId != id || !pending.Candidate.Approved ||
                pending.Candidate.Ready || pending.Control == null || !IsTrusted(pending.Candidate.PhoneId)) return false;
            return PeerId(remote) == (pending.Candidate.Role == "phone" ? pending.Candidate.NewId : pending.Candidate.PhoneId);
        }
    }
    internal object ProveRotation(JsonElement value, string phoneId, string expectedId)
    {
        V3Record.Require(value, "rotate_prove", "role", "old_id", "new_id", "rotation_id");
        lock (gate)
        {
            var pending = RequireRotation(expectedId);
            var candidate = pending.Candidate;
            if (!candidate.Approved || candidate.Ready || pending.Control == null || !IsTrusted(candidate.PhoneId) ||
                V3Record.Hex(value, "rotation_id", 16) != candidate.RotationId || Wire.RequiredString(value, "role") != candidate.Role ||
                V3Record.Hex(value, "old_id", 32) != candidate.OldId || V3Record.Hex(value, "new_id", 32) != candidate.NewId ||
                phoneId != (candidate.Role == "phone" ? candidate.NewId : candidate.PhoneId))
                throw new AuthenticationException("identity_mismatch");
            rotation = pending with { Candidate = candidate with { Ready = true } };
            return new { type = "rotate_ready", version = 3, rotation_id = expectedId };
        }
    }
    internal object RotationStatus(string id, string phoneId, Guid connection)
    {
        lock (gate)
        {
            if (ValidRotation() is { } pending && pending.Candidate.RotationId == id && pending.Candidate.PhoneId == phoneId && IsTrusted(phoneId))
            {
                rotation = pending with { Control = connection };
                return State(id, pending.Candidate.Ready ? "ready" : pending.Candidate.Approved ? "approved" : "awaiting_approval");
            }
            var committed = (database.Rotations ?? []).FirstOrDefault(r => r.RotationId == id &&
                clock.GetUtcNow() - r.Committed < TimeSpan.FromHours(24) &&
                phoneId == (r.Role == "phone" ? r.NewId : r.PhoneId) && IsTrusted(phoneId));
            return State(id, committed == null ? "expired" : "committed");
        }
    }
    internal object CommitRotation(string id, string phoneId, Guid connection)
    {
        lock (gate)
        {
            var pending = RequireRotation(id);
            var candidate = pending.Candidate;
            if (!candidate.Ready || !candidate.Approved || pending.Control != connection || candidate.PhoneId != phoneId || !IsTrusted(phoneId))
                throw new AuthenticationException("untrusted_peer");
            var now = clock.GetUtcNow();
            var results = (database.Rotations ?? []).Where(r => now - r.Committed < TimeSpan.FromHours(24)).ToList();
            if (results.Any(r => r.RotationId == id) || results.Count >= 32) throw new AuthenticationException("stale_session");
            results.Add(new(id, candidate.Role, candidate.OldId, candidate.NewId, phoneId, now));
            Database next;
            if (candidate.Role == "phone")
            {
                var old = database.Phones.Single(p => p.Id == phoneId && !p.Revoked);
                var phones = database.Phones.Select(p => p.Id == phoneId ? p with { Revoked = true, Updated = now } : p).ToList();
                phones.Add(new(candidate.NewId, old.Name, candidate.NewCertificate, false, now));
                next = database with { Phones = phones, Rotations = results };
            }
            else
            {
                // Other phones must explicitly pair with the changed desktop identity.
                var phones = database.Phones.Select(p => p.Id != phoneId ? p with { Revoked = true, Updated = now } : p).ToList();
                next = database with { Thumbprint = rotationCertificate!.Thumbprint, Phones = phones, Rotations = results,
                    RetiredKeys = (database.RetiredKeys ?? []).Append(certificate.Thumbprint).Distinct().ToList() };
            }
            Save(next); database = next; // This is the irreversible commit point.
            if (candidate.Role == "desktop")
            {
                certificate.Dispose(); certificate = rotationCertificate!; rotationCertificate = null;
                CleanupRetiredKeys();
            }
            rotation = null;
            return new { type = "rotate_ok", version = 3, rotation_id = id };
        }
    }
    internal void DetachRotationControl(Guid connection)
    {
        lock (gate) if (rotation?.Control == connection) rotation = rotation with { Control = null };
    }
    private static object State(string id, string state) => new { type = "rotation_state", version = 3, rotation_id = id, state };
    private PendingRotation RequireRotation(string id)
    {
        var pending = ValidRotation();
        return pending?.Candidate.RotationId == id ? pending : throw new AuthenticationException("stale_session");
    }
    private PendingRotation? ValidRotation()
    {
        if (rotation != null && (clock.GetUtcNow() >= rotation.Candidate.Expires || clock.GetElapsedTime(rotation.Created).TotalSeconds >= 60))
            CancelRotationLocked();
        return rotation;
    }
    private void EnsureRotationCapacity()
    {
        if ((database.Rotations ?? []).Count(r => clock.GetUtcNow() - r.Committed < TimeSpan.FromHours(24)) >= 32)
            throw new AuthenticationException("session_busy");
        if ((database.RetiredKeys?.Count ?? 0) >= 32) throw new AuthenticationException("session_busy");
    }
    private void CancelRotationLocked()
    {
        rotation = null;
        if (rotationCertificate != null)
        {
            try { RetireCertificate(rotationCertificate); } catch (CryptographicException) { }
            finally { rotationCertificate.Dispose(); rotationCertificate = null; }
        }
    }
    private void CleanupRetiredKeys()
    {
        var remaining = new List<string>();
        foreach (var thumbprint in database.RetiredKeys ?? [])
        {
            try
            {
                using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser); store.Open(OpenFlags.ReadWrite);
                foreach (var cert in store.Certificates.Find(X509FindType.FindByThumbprint, thumbprint, false).Cast<X509Certificate2>())
                    using (cert) RetireCertificate(cert);
            }
            catch (CryptographicException) { remaining.Add(thumbprint); }
        }
        if (remaining.Count != (database.RetiredKeys?.Count ?? 0))
        {
            var next = database with { RetiredKeys = remaining };
            try { Save(next); database = next; } catch (IOException) { /* Keep durable retirement metadata for retry. */ }
        }
    }
    private static void RetireCertificate(X509Certificate2 cert)
    {
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser); store.Open(OpenFlags.ReadWrite);
        using var signing = cert.GetECDsaPrivateKey() as ECDsaCng;
        signing?.Key.Delete(); store.Remove(cert);
    }
}
