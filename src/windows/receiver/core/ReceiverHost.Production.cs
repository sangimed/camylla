using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Diagnostics;

namespace Camylla.Receiver;

public sealed partial class ReceiverHost
{
    private sealed class ProductionSession(Session session, string phoneId, string clientNonce)
    {
        public Session Session { get; } = session;
        public string PhoneId { get; } = phoneId;
        public string ClientNonce { get; } = clientNonce;
        public string ServerNonce { get; } = ProductionTrust.RandomHex(16);
        public byte[] TicketHash { get; set; } = [];
        public long TicketCreated { get; set; }
        public bool Consumed { get; set; }
        public TcpClient? Video { get; set; }
        public TaskCompletionSource? VideoEnded { get; set; }
        public HashSet<string> RebindNonces { get; } = [];
        public int LastCameraReconfiguration { get; set; }
        public string NewTicket()
        {
            var ticket = ProductionTrust.RandomHex(32);
            TicketHash = SHA256.HashData(Convert.FromHexString(ticket));
            TicketCreated = Stopwatch.GetTimestamp(); Consumed = false;
            return ticket;
        }
    }
    private ProductionSession? productionSession;
    private readonly SemaphoreSlim productionConnections = new(8);

    private void RevokeSession(string id)
    {
        lock (Gate)
        {
            if (productionSession?.PhoneId != id) return;
            active?.Stop(); productionSession.Video?.Dispose(); ClearFrames();
        }
    }
    private async Task<string?> AuthenticateProductionAsync(SslStream tls, X509Certificate2 certificate,
        bool control, CancellationToken ct, string? proofId = null)
    {
        string? phoneId = null;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(TimeSpan.FromMilliseconds(1500));
        await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
        {
            ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls13,
            ClientCertificateRequired = true, AllowTlsResume = false,
            RemoteCertificateValidationCallback = (_, remote, _, _) =>
            {
                if (remote == null)
                {
                    var allowed = proofId == null && Trust!.AcceptCertificate(null, control);
                    Report($"v3 certificate callback missing_client_certificate allowed={allowed}");
                    return allowed;
                }
                using var cert = X509CertificateLoader.LoadCertificate(remote.GetRawCertData());
                var accepted = proofId == null ? Trust!.AcceptCertificate(cert, control) : Trust!.AcceptProofCertificate(cert, proofId);
                Report($"v3 certificate callback presented_client_certificate accepted={accepted}");
                if (accepted) phoneId = ProductionTrust.PeerId(cert);
                return accepted;
            }
        }, deadline.Token);
        if (tls.SslProtocol != SslProtocols.Tls13 || (!control && phoneId == null))
            throw new AuthenticationException("untrusted_peer");
        Report($"v3 TLS { (control ? "control" : "video") } protocol={tls.SslProtocol} client_identity={phoneId ?? "pairing invitation only"}");
        return phoneId;
    }
    private static async Task<JsonDocument> ReadProductionRecordAsync(SslStream tls, CancellationToken ct)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(TimeSpan.FromSeconds(5));
        return await Wire.ReadControlAsync(tls, deadline.Token);
    }
    private async Task HandleProductionControlAsync(TcpClient client, X509Certificate2 certificate, CancellationToken ct)
    {
        using (client)
        using (var tls = new SslStream(client.GetStream(), false))
        {
            if (!productionConnections.Wait(0)) return;
            Session? session = null;
            string? phoneId = null;
            var connection = Guid.NewGuid();
            try
            {
                using var currentCertificate = Trust!.CopyControlCertificate();
                var serverId = ProductionTrust.PeerId(currentCertificate);
                phoneId = await AuthenticateProductionAsync(tls, currentCertificate, true, ct);
                if (serverId != Trust.DesktopId) throw new AuthenticationException("untrusted_peer");
                using var first = await ReadProductionRecordAsync(tls, ct);
                var value = first.RootElement;
                var type = Wire.RequiredString(value, "type");
                if (phoneId == null)
                {
                    if (type != "pair_init") throw new AuthenticationException("pair_rejected");
                    var address = ((System.Net.IPEndPoint)client.Client.RemoteEndPoint!).Address.ToString();
                    var challenge = Trust!.BeginChallenge(value, address);
                    Report("v3 pairing challenge issued; automatic certificate proof pending");
                    await Wire.WriteControlAsync(tls, challenge, ct);
                    return;
                }
                if (type == "pair_commit")
                {
                    await Wire.WriteControlAsync(tls, Trust!.Commit(value, phoneId), ct);
                    Report($"v3 pairing trust committed phone={phoneId}");
                    return;
                }
                if (type == "pair_status")
                {
                    await Wire.WriteControlAsync(tls, Trust!.PairStatus(value, phoneId), ct);
                    return;
                }
                if (!Trust!.IsTrusted(phoneId)) throw new AuthenticationException("untrusted_peer");
                if (type is "rotate_offer" or "rotate_request" or "rotation_status" or "rotate_commit")
                {
                    await HandleRotationControlAsync(tls, value, phoneId, connection, ct);
                    return;
                }
                V3Record.Require(value, "start", "phone_id", "client_nonce", "codec", "modes", "mode", "lens");
                if (V3Record.Hex(value, "phone_id", 32) != phoneId) throw new AuthenticationException("identity_mismatch");
                var clientNonce = V3Record.Hex(value, "client_nonce", 16);
                var mode = Wire.RequiredString(value, "mode");
                var lens = Wire.RequiredString(value, "lens");
                if (Wire.RequiredString(value, "codec") != "h264" || mode is not ("1280x720@30" or "1920x1080@30") || lens is not ("rear" or "front"))
                    throw new InvalidDataException("mode_unsupported");
                var modes = value.GetProperty("modes");
                if (modes.ValueKind != JsonValueKind.Array || modes.GetArrayLength() is < 1 or > 16 ||
                    modes.EnumerateArray().Any(m => m.ValueKind != JsonValueKind.String || (m.GetString()?.Length ?? 0) > 32) ||
                    !modes.EnumerateArray().Any(m => m.GetString() == mode)) throw new InvalidDataException("mode_unsupported");
                session = new(ProductionTrust.RandomHex(16), mode, lens, client.Client.RemoteEndPoint!.ToString()!, tls);
                ProductionSession security;
                string ticket;
                lock (Gate)
                {
                    if (active != null) throw new AuthenticationException("session_busy");
                    if (Trust.Rotation != null || serverId != Trust.DesktopId) throw new AuthenticationException("session_busy");
                    if (!Trust.IsTrusted(phoneId)) throw new AuthenticationException("untrusted_peer");
                    security = new(session, phoneId, clientNonce); ticket = security.NewTicket();
                    active = session; productionSession = security;
                    phoneCaptureMayBeActive = true;
                    connectionIssue = null; connectionIssueTicks = 0;
                }
                await session.SendAsync(new { type = "started", version = 3, session_id = session.Id,
                    client_nonce = clientNonce, server_nonce = security.ServerNonce, video_ticket = ticket,
                    codec = "h264", mode, fps = 30 }, ct);
                Report($"Authenticated v3 control phone={phoneId} mode={mode} TLS={tls.SslProtocol}");
                using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ct, session.Token);
                while (!lifetime.IsCancellationRequested)
                {
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
                    idle.CancelAfter(ControlIdleTimeout);
                    using var message = await Wire.ReadControlAsync(tls, idle.Token);
                    var kind = Wire.RequiredString(message.RootElement, "type");
                    if (kind == "stop")
                    {
                        V3Record.Require(message.RootElement, "stop", "session_id");
                        if (V3Record.Hex(message.RootElement, "session_id", 16) != session.Id) throw new AuthenticationException("stale_session");
                        lock (Gate) phoneCaptureMayBeActive = false;
                        await session.StopAndSendAsync(new { type = "stopped", version = 3, session_id = session.Id }, ct);
                        security.Video?.Dispose(); ClearFrames();
                        break;
                    }
                    if (kind == "stopped")
                    {
                        V3Record.Require(message.RootElement, "stopped", "session_id");
                        if (V3Record.Hex(message.RootElement, "session_id", 16) != session.Id)
                            throw new AuthenticationException("stale_session");
                        session.AcknowledgeDesktopStop();
                        lock (Gate) phoneCaptureMayBeActive = false;
                        session.Stop(); security.Video?.Dispose(); ClearFrames();
                        break;
                    }
                    if (kind == "camera_changing")
                    {
                        V3Record.Require(message.RootElement, kind, "session_id", "generation");
                        if (V3Record.Hex(message.RootElement, "session_id", 16) != session.Id || Wire.RequiredInt(message.RootElement, "generation") < 1)
                            throw new InvalidDataException("stale_session");
                        lock (Gate) { session.BeginCameraChange(); ClearFrames(); }
                        BeginCameraControlsChange(session, Wire.RequiredInt(message.RootElement, "generation"));
                        continue;
                    }
                    if (kind is "camera_capability" or "camera_state" or "camera_result" or "quality_state")
                    {
                        ReceiveCameraRecord(session, message.RootElement);
                        continue;
                    }
                    if (kind is "video_rebind" or "camera_reconfigure")
                    {
                        if (kind == "camera_reconfigure")
                            V3Record.Require(message.RootElement, kind, "session_id", "request_nonce", "mode", "lens", "generation");
                        else V3Record.Require(message.RootElement, kind, "session_id", "request_nonce");
                        if (V3Record.Hex(message.RootElement, "session_id", 16) != session.Id) throw new AuthenticationException("stale_session");
                        var nonce = V3Record.Hex(message.RootElement, "request_nonce", 16);
                        Task? drained;
                        lock (Gate)
                        {
                            if (security.RebindNonces.Count >= 128 || !security.RebindNonces.Add(nonce)) throw new AuthenticationException("bind_rejected");
                            security.Consumed = true; security.Video?.Dispose(); drained = security.VideoEnded?.Task;
                        }
                        if (drained != null) await drained.WaitAsync(TimeSpan.FromSeconds(3), lifetime.Token);
                        lock (Gate)
                        {
                            if (kind == "camera_reconfigure")
                            {
                                var nextMode = Wire.RequiredString(message.RootElement, "mode");
                                var nextLens = Wire.RequiredString(message.RootElement, "lens");
                                if (nextMode is not ("1280x720@30" or "1920x1080@30") || nextLens is not ("front" or "rear") ||
                                    Wire.RequiredInt(message.RootElement, "generation") <= security.LastCameraReconfiguration) throw new InvalidDataException("mode_unsupported");
                                security.LastCameraReconfiguration = Wire.RequiredInt(message.RootElement, "generation");
                                session.Mode = nextMode; session.Lens = nextLens;
                                ClearFrames();
                            }
                            ticket = security.NewTicket();
                        }
                        await session.SendAsync(new { type = "video_ticket", version = 3, session_id = session.Id,
                            request_nonce = nonce, video_ticket = ticket }, lifetime.Token);
                        continue;
                    }
                    V3Record.Require(message.RootElement, "ping");
                    await session.SendAsync(new { type = "pong", version = 3 }, lifetime.Token);
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception error) when (error is IOException or AuthenticationException or JsonException or
                InvalidDataException or SocketException or CryptographicException or InvalidOperationException or TimeoutException or FormatException)
            {
                if (session == null) SetConnectionIssue(error is AuthenticationException ? "trust_rejected" :
                    error.Message is "session_busy" ? "session_busy" : "connection_rejected");
                Report($"v3 control rejected or disconnected exception={error.GetType().Name} hresult=0x{error.HResult:X8}");
                for (var cause = error.InnerException; cause != null; cause = cause.InnerException)
                    Report($"v3 transport cause={cause.GetType().Name} hresult=0x{cause.HResult:X8}" +
                        (cause is System.ComponentModel.Win32Exception native ? $" native=0x{native.NativeErrorCode:X8}" : ""));
                if (tls.IsAuthenticated)
                    try { await Wire.WriteControlAsync(tls, new { type = "error", version = 3,
                        code = phoneId == null ? "pair_rejected" : ProductionError(error) }, ct); }
                    catch (Exception) { }
            }
            finally
            {
                Trust!.DetachRotationControl(connection);
                if (session != null)
                {
                    var unexpectedLoss = session.State != ReceiverState.Stopping && session.EverStreamed;
                    ClearCameraSession(session);
                    session.Stop();
                    lock (Gate) if (active == session)
                    {
                        productionSession?.Video?.Dispose(); active = null; productionSession = null; ClearFrames();
                        if (unexpectedLoss) { connectionIssue = "connection_lost"; connectionIssueTicks = System.Diagnostics.Stopwatch.GetTimestamp(); }
                    }
                }
                productionConnections.Release();
            }
        }
    }
    private async Task<Session> AuthenticateProductionVideoAsync(SslStream tls, TcpClient client,
        X509Certificate2 certificate, CancellationToken ct)
    {
        using var currentCertificate = Trust!.CopyControlCertificate();
        var serverId = ProductionTrust.PeerId(currentCertificate);
        var phone = await AuthenticateProductionAsync(tls, currentCertificate, false, ct);
        using var bind = await ReadProductionRecordAsync(tls, ct);
        var value = bind.RootElement;
        V3Record.Require(value, "bind", "session_id", "phone_id", "client_nonce", "server_nonce", "video_ticket");
        var id = V3Record.Hex(value, "session_id", 16);
        var clientNonce = V3Record.Hex(value, "client_nonce", 16);
        var serverNonce = V3Record.Hex(value, "server_nonce", 16);
        var ticket = V3Record.Hex(value, "video_ticket", 32);
        var phoneId = V3Record.Hex(value, "phone_id", 32);
        lock (Gate)
        {
            if (Trust.Rotation != null || serverId != Trust.DesktopId) throw new AuthenticationException("bind_rejected");
            var security = productionSession;
            if (security == null || active != security.Session || security.Session.Token.IsCancellationRequested ||
                phone != security.PhoneId || phoneId != phone || id != security.Session.Id ||
                clientNonce != security.ClientNonce || serverNonce != security.ServerNonce || security.Consumed ||
                Stopwatch.GetElapsedTime(security.TicketCreated).TotalSeconds >= 15 || !Trust!.IsTrusted(phoneId) ||
                !CryptographicOperations.FixedTimeEquals(SHA256.HashData(Convert.FromHexString(ticket)), security.TicketHash) ||
                !security.Session.TryBindVideo()) throw new AuthenticationException("bind_rejected");
            security.Consumed = true; security.Video = client;
            security.VideoEnded = new(TaskCreationOptions.RunContinuationsAsynchronously);
            return security.Session;
        }
    }
    private void EndProductionVideo(Session? session, TcpClient client)
    {
        lock (Gate)
        {
            if (productionSession is not { } security || security.Session != session || security.Video != client) return;
            session!.UnbindVideo(); security.Video = null; security.VideoEnded?.TrySetResult(); ClearFrames();
        }
    }
    private static string ProductionError(Exception error) => error.Message is
        "unsupported_version" or "invalid_record" or "identity_mismatch" or "pair_expired" or
        "pair_consumed" or "pair_rejected" or "rate_limited" or "untrusted_peer" or "peer_revoked" or
        "session_busy" or "stale_session" or "bind_rejected" or "mode_unsupported" or "tls_unavailable"
        ? error.Message : "invalid_record";
}
