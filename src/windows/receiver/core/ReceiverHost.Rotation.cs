using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text.Json;

namespace Camylla.Receiver;

public sealed partial class ReceiverHost
{
    public RotationCandidate BeginDesktopRotation(string phoneId)
    {
        lock (Gate)
        {
            if (active != null) throw new AuthenticationException("session_busy");
            return Trust!.PrepareDesktopRotation(phoneId);
        }
    }
    private async Task HandleRotationControlAsync(SslStream tls, JsonElement first, string phone,
        Guid connection, CancellationToken ct)
    {
        var value = first;
        JsonDocument? next = null;
        try
        {
            while (true)
            {
                var type = Wire.RequiredString(value, "type");
                object response;
                lock (Gate)
                {
                    if (active != null || !Trust!.IsTrusted(phone)) throw new AuthenticationException("session_busy");
                    switch (type)
                    {
                        case "rotate_offer": response = Trust.OfferPhoneRotation(value, phone, connection); break;
                        case "rotate_request":
                            V3Record.Require(value, type);
                            response = Trust.DesktopRotationOffer(phone, connection); break;
                        case "rotation_status":
                            V3Record.Require(value, type, "rotation_id");
                            response = Trust.RotationStatus(V3Record.Hex(value, "rotation_id", 16), phone, connection); break;
                        case "rotate_commit":
                            V3Record.Require(value, type, "rotation_id");
                            response = Trust.CommitRotation(V3Record.Hex(value, "rotation_id", 16), phone, connection);
                            Report("v3 rotation durably committed; previous identity retired"); break;
                        default: throw new AuthenticationException("invalid_record");
                    }
                }
                await Wire.WriteControlAsync(tls, response, ct);
                if (type == "rotate_commit") return;
                next?.Dispose(); next = await ReadProductionRecordAsync(tls, ct); value = next.RootElement;
            }
        }
        finally { next?.Dispose(); }
    }
    private async Task HandleRotationProofAsync(TcpClient client, RotationCandidate candidate, CancellationToken ct)
    {
        using (client)
        using (var tls = new SslStream(client.GetStream(), false))
        {
            if (!productionConnections.Wait(0)) return;
            try
            {
                using var certificate = Trust!.CopyProofCertificate(candidate.RotationId);
                var phone = await AuthenticateProductionAsync(tls, certificate, false, ct, candidate.RotationId);
                using var proof = await ReadProductionRecordAsync(tls, ct);
                object response;
                lock (Gate)
                {
                    if (active != null) throw new AuthenticationException("session_busy");
                    response = Trust.ProveRotation(proof.RootElement, phone!, candidate.RotationId);
                }
                await Wire.WriteControlAsync(tls, response, ct);
                Report("v3 new identity proved through mutual TLS on video port; no media admitted");
            }
            catch (OperationCanceledException) { }
            catch (Exception error) when (error is IOException or AuthenticationException or CryptographicException or
                JsonException or InvalidOperationException or FormatException)
            { Report($"v3 rotation proof rejected exception={error.GetType().Name}"); }
            finally { productionConnections.Release(); }
        }
    }
}
