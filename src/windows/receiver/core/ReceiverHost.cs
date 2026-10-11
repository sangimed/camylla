using System.Buffers;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Camylla.Receiver;

public sealed partial class ReceiverHost : IDisposable
{
    private readonly int ControlPort;
    private readonly int VideoPort;
    private readonly int DiscoveryPort;
    private readonly TimeSpan ControlIdleTimeout = TimeSpan.FromSeconds(8);
    private Session? active;
    private readonly object Gate = new();
    private long received, keyframes, decoded, decoderErrors, networkErrors, decoderResets, reconnects;
    private long controlDisconnects;
    private ulong lastSequence, lastPts, lastAndroidSendUs;
    private long lastReceiveTicks, lastDecodeTicks;
    private long lastPushReceiveToDecodeTicks;
    private long frameEpoch;
    private string? connectionIssue;
    private long connectionIssueTicks;
    private bool phoneCaptureMayBeActive;
    private uint lastDecodedWidth, lastDecodedHeight, lastDecodedStride;

    private readonly LatestFrameStore frames = new();
    private readonly object readerGate = new();
    private readonly HashSet<LatestFrameStore> readers = new();
    private readonly object handlerGate = new();
    private readonly HashSet<Task> handlers = new();
    private TcpListener? controlListener;
    private TcpListener? videoListener;
    private CancellationTokenSource? running;
    public LatestFrameStore Frames => frames;
    public FrameReader CreateFrameReader()
    {
        var store = new LatestFrameStore();
        lock (readerGate) readers.Add(store);
        return new FrameReader(store, () => { lock (readerGate) readers.Remove(store); store.Clear(); });
    }
    internal void PublishFrame(OwnedNv12Frame frame)
    {
        frames.Publish(frame);
        lock (readerGate)
            foreach (var reader in readers) reader.Publish(frame);
    }
    private void PublishSessionFrame(Session session, OwnedNv12Frame frame)
    {
        lock (Gate)
            if (active == session && session.State == ReceiverState.Streaming)
                PublishFrame(frame);
    }
    private void ClearFrames()
    {
        Interlocked.Increment(ref frameEpoch);
        frames.Clear();
        lock (readerGate) foreach (var reader in readers) reader.Clear();
    }
    public string CertificatePin { get; private set; } = "";
    public string DevelopmentSecret { get; private set; } = "";
    public event Action<string>? Log;

    public ProductionTrust? Trust { get; }
    public ReceiverHost(int controlPort = 58413, int videoPort = 58414, bool production = false, int discoveryPort = 41415)
    {
        if (controlPort is < 1 or > 65535 || videoPort is < 1 or > 65535 || controlPort == videoPort || discoveryPort is < 0 or > 65535)
            throw new ArgumentException("Invalid listener ports");
        ControlPort = controlPort;
        VideoPort = videoPort;
        DiscoveryPort = discoveryPort;
        if (production) { Trust = new ProductionTrust(); Trust.PeerRevoked += RevokeSession; }
    }
    internal ReceiverHost(int controlPort, int videoPort, ProductionTrust trust, int discoveryPort = 41415) : this(controlPort, videoPort, false, discoveryPort)
    {
        Trust = trust; Trust.PeerRevoked += RevokeSession;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        if (running != null) throw new InvalidOperationException("Receiver already running");
        using var certificate = Trust != null ? new X509Certificate2(Trust.Certificate) : DevelopmentIdentity.LoadOrCreate();
        if (Trust == null)
        {
            CertificatePin = Convert.ToHexString(SHA256.HashData(certificate.RawData));
            DevelopmentSecret = DevelopmentIdentity.LoadOrCreateSecret();
            Report($"Development certificate SHA-256 pin: {CertificatePin}");
            Report($"Development session secret: {DevelopmentSecret}");
        }
        Report($"Control TLS/TCP port {ControlPort}; video TLS/TCP port {VideoPort}");
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        running = linked;
        var control = new TcpListener(IPAddress.Any, ControlPort);
        var video = new TcpListener(IPAddress.Any, VideoPort);
        controlListener = control;
        videoListener = video;
        try
        {
            control.Start(4);
            video.Start(4);
            await Task.WhenAll(AcceptControlAsync(control, certificate, DevelopmentSecret, linked.Token),
                AcceptVideoAsync(video, certificate, DevelopmentSecret, linked.Token),
                RespondToPairDiscoveryAsync(linked.Token));
        }
        finally
        {
            linked.Cancel();
            lock (Gate) { active?.Stop(); active = null; }
            Task[] draining;
            lock (handlerGate) draining = handlers.ToArray();
            await Task.WhenAll(draining);
            ClearFrames();
            control.Stop();
            video.Stop();
            controlListener = null;
            videoListener = null;
            running = null;
        }
    }

    public ReceiverSnapshot Snapshot()
    {
        Session? session;
        string? issue;
        bool captureMayBeActive;
        lock (Gate)
        {
            session = active;
            captureMayBeActive = phoneCaptureMayBeActive;
            issue = session?.CurrentIssue ?? (connectionIssueTicks != 0 &&
                System.Diagnostics.Stopwatch.GetElapsedTime(connectionIssueTicks) < TimeSpan.FromSeconds(15)
                ? connectionIssue : null) ?? (captureMayBeActive && session == null ? "connection_lost" : null);
        }
        return new ReceiverSnapshot(session?.State.ToString() ?? "Disconnected", session?.Peer ?? "none",
            session?.Id ?? "none", session?.Mode ?? "none", Interlocked.Read(ref received),
            Interlocked.Read(ref keyframes), Interlocked.Read(ref decoded), Interlocked.Read(ref decoderErrors),
            Interlocked.Read(ref networkErrors), Interlocked.Read(ref decoderResets),
            Interlocked.Read(ref reconnects), lastSequence, lastPts, lastAndroidSendUs,
            lastReceiveTicks, lastDecodeTicks, Interlocked.Read(ref lastPushReceiveToDecodeTicks),
            lastDecodedWidth, lastDecodedHeight, lastDecodedStride,
            frames.PendingDepth, frames.HighWater, frames.Superseded, Interlocked.Read(ref frameEpoch), issue,
            captureMayBeActive, Interlocked.Read(ref controlDisconnects));
    }
    private void SetConnectionIssue(string? issue)
    {
        lock (Gate)
        {
            if (issue != null && active != null) return;
            connectionIssue = issue;
            connectionIssueTicks = issue == null ? 0 : System.Diagnostics.Stopwatch.GetTimestamp();
        }
    }

    internal void Report(string message) => (Log ?? Console.WriteLine)(message);
    public void Stop()
    {
        try { running?.Cancel(); } catch (ObjectDisposedException) { }
    }
    /// <summary>Ask the phone to end its explicit capture before retiring this desktop session.</summary>
    public async Task RequestActivePhoneStopAsync(CancellationToken ct = default)
    {
        Session? session;
        lock (Gate) session = active;
        if (session == null) return;
        try
        {
            await session.SendAsync(new { type = "stop_request", version = 3, session_id = session.Id }, ct);
            await session.DesktopStopAcknowledged.WaitAsync(ct);
        }
        finally
        {
            lock (Gate)
            {
                if (active == session)
                {
                    session.Stop();
                    productionSession?.Video?.Dispose();
                    ClearFrames();
                }
            }
        }
    }
    public void Dispose() => Stop();

    private void Track(Task task)
    {
        lock (handlerGate) handlers.Add(task);
        _ = task.ContinueWith(completed =>
        {
            lock (handlerGate) handlers.Remove(completed);
        }, TaskScheduler.Default);
    }

    private async Task AcceptControlAsync(TcpListener listener, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(ct);
                Track(Task.Run(() => HandleControlAsync(client, certificate, token, ct)));
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task HandleControlAsync(TcpClient client, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        if (Trust != null) { await HandleProductionControlAsync(client, certificate, ct); return; }
        using (client)
        using (var tls = new SslStream(client.GetStream(), false))
        {
            Session? session = null;
            try
            {
                client.ReceiveTimeout = 5000;
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions {
                    ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, ct);
                using var hello = await Wire.ReadControlAsync(tls, ct);
                var value = hello.RootElement;
                if (Wire.RequiredString(value, "type") != "hello") throw new InvalidDataException("Expected hello");
                if (Wire.RequiredInt(value, "version") != Wire.Version) throw new InvalidDataException("Unsupported protocol version");
                if (Wire.RequiredString(value, "codec") != "h264") throw new InvalidDataException("Unsupported codec");
                var mode = Wire.RequiredString(value, "mode");
                if (mode is not ("1280x720@30" or "1920x1080@30")) throw new InvalidDataException("Invalid mode");
                var lens = Wire.RequiredString(value, "lens");
                if (lens is not ("rear" or "front")) throw new InvalidDataException("Invalid lens");
                if (!value.TryGetProperty("modes", out var modes) || modes.ValueKind != JsonValueKind.Array ||
                    modes.GetArrayLength() is < 1 or > 2 ||
                    modes.EnumerateArray().Any(m => m.ValueKind != JsonValueKind.String ||
                        m.GetString() is not ("1280x720@30" or "1920x1080@30")) ||
                    !modes.EnumerateArray().Any(m => m.GetString() == mode))
                    throw new InvalidDataException("Selected mode not advertised");
                if (!Wire.FixedHexEquals(Wire.RequiredString(value, "secret"), token))
                    throw new AuthenticationException("Invalid session secret");
                var id = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
                session = new Session(id, mode, lens, client.Client.RemoteEndPoint?.ToString() ?? "unknown", tls);
                lock (Gate)
                {
                    if (active != null) throw new InvalidOperationException("Another control session is active");
                    active = session;
                }
                Report($"Authenticated control peer={session.Peer} session={id} mode={mode} lens={lens}");
                await session.SendAsync(new { type = "welcome", version = Wire.Version, session = id,
                    codec = "h264", mode, fps = 30 }, ct);
                while (!ct.IsCancellationRequested)
                {
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    idle.CancelAfter(ControlIdleTimeout);
                    using var message = await Wire.ReadControlAsync(tls, idle.Token);
                    var kind = Wire.RequiredString(message.RootElement, "type");
                    if (kind == "stop") break;
                    if (kind != "ping") throw new InvalidDataException("Invalid control message type");
                    await session.SendAsync(new { type = "pong" }, ct);
                }
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                Report("Control heartbeat timed out; retiring stale session");
                Interlocked.Increment(ref networkErrors);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
            catch (Exception error) when (error is IOException or AuthenticationException or InvalidDataException or InvalidOperationException or SocketException)
            {
                Report($"Control: {error.Message}");
                if (tls.IsAuthenticated)
                    try { await Wire.WriteControlAsync(tls, new { type = "error", message = error.Message }, ct); }
                    catch (Exception) { /* The peer may already have disconnected. */ }
            }
            finally
            {
                if (session != null)
                {
                    session.Stop();
                    lock (Gate)
                    {
                        if (active == session)
                        {
                            active = null;
                            ClearFrames();
                        }
                    }
                    Report($"Control session {session.Id} ended");
                }
            }
        }
    }

    private async Task AcceptVideoAsync(TcpListener listener, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(ct);
                Track(Task.Run(() => HandleVideoAsync(client, certificate, token, ct)));
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task HandleVideoAsync(TcpClient client, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        if (Trust?.Rotation is { } rotation)
        { await HandleRotationProofAsync(client, rotation, ct); return; }
        if (Trust != null && !productionConnections.Wait(0)) { client.Dispose(); return; }
        using (client)
        using (var tls = new SslStream(client.GetStream(), false))
        {
            Session? session = null;
            try
            {
                string id;
                if (Trust != null)
                {
                    session = await AuthenticateProductionVideoAsync(tls, client, certificate, ct);
                    id = session.Id;
                }
                else
                {
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions {
                    ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, ct);
                using var bind = await Wire.ReadControlAsync(tls, ct);
                var value = bind.RootElement;
                if (Wire.RequiredString(value, "type") != "bind" || Wire.RequiredInt(value, "version") != Wire.Version ||
                    !Wire.FixedHexEquals(Wire.RequiredString(value, "secret"), token))
                    throw new AuthenticationException("Video authentication failed");
                id = Wire.RequiredString(value, "session");
                lock (Gate)
                {
                    if (active == null || !Wire.FixedHexEquals(active.Id, id) || !active.TryBindVideo())
                        throw new AuthenticationException("Video/control session mismatch or duplicate video channel");
                    session = active;
                }
                }
                await Wire.WriteControlAsync(tls, Trust == null ? (object)new { type = "ready", session = id }
                    : new { type = "ready", version = 3, session_id = id }, ct);
                Interlocked.Increment(ref reconnects);
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, session.Token);
                using var decoder = new MfDecoder(session.Mode, frame => PublishSessionFrame(session, frame));
                long rejectedFrames = 0;
                byte[]? config = null;
                uint generation = 0;
                ulong? previous = null;
                var needsKey = true;
                await session.SendAsync(new { type = "sync", version = Trust == null ? 2 : 3 }, linked.Token);
                Report($"Video bound session={id} peer={client.Client.RemoteEndPoint}");
                while (!linked.IsCancellationRequested)
                {
                    using var messageTimeout = CancellationTokenSource.CreateLinkedTokenSource(linked.Token);
                    messageTimeout.CancelAfter(TimeSpan.FromSeconds(2));
                    using var unit = await Wire.ReadVideoAsync(tls, messageTimeout.Token, Trust == null ? 2 : 3);
                    Wire.RequireBoundSession(unit.SessionId, session.Id);
                    Interlocked.Increment(ref received);
                    lastSequence = unit.Sequence;
                    lastPts = unit.PtsUs;
                    lastAndroidSendUs = unit.AndroidSendUs;
                    lastReceiveTicks = System.Diagnostics.Stopwatch.GetTimestamp();
                    if (previous.HasValue && unit.Sequence != previous.Value + 1)
                    {
                        decoder.Reset(); Interlocked.Increment(ref decoderResets);
                        config = null; needsKey = true;
                        await session.SendAsync(new { type = "sync", version = Trust == null ? 2 : 3 }, linked.Token);
                    }
                    previous = unit.Sequence;
                    if (unit.Flags == Wire.Config)
                    {
                        if (config != null && unit.Generation != generation)
                        {
                            decoder.Reset(); Interlocked.Increment(ref decoderResets);
                            needsKey = true;
                            lock (Gate) if (active == session) ClearFrames();
                        }
                        config = unit.Payload.AsSpan(0, unit.PayloadLength).ToArray();
                        generation = unit.Generation;
                        continue;
                    }
                    if (unit.Generation != generation)
                    {
                        decoder.Reset(); Interlocked.Increment(ref decoderResets);
                        config = null; needsKey = true;
                        await session.SendAsync(new { type = "sync", version = Trust == null ? 2 : 3 }, linked.Token);
                        continue;
                    }
                    int decodedNow;
                    if (needsKey)
                    {
                        if (unit.Flags != Wire.Key || config == null) continue;
                        var length = checked(config.Length + unit.PayloadLength);
                        var joined = ArrayPool<byte>.Shared.Rent(length);
                        try
                        {
                            config.CopyTo(joined, 0);
                            unit.Payload.AsSpan(0, unit.PayloadLength).CopyTo(joined.AsSpan(config.Length));
                            decodedNow = decoder.Push(joined, unit.PtsUs, length);
                        }
                        finally { ArrayPool<byte>.Shared.Return(joined); }
                        needsKey = false;
                    }
                    else
                    {
                        decodedNow = decoder.Push(unit.Payload, unit.PtsUs, unit.PayloadLength);
                    }
                    if (unit.Flags == Wire.Key && needsKey == false) Interlocked.Increment(ref keyframes);
                    var rejectedNow = decoder.RejectedFrames;
                    Interlocked.Add(ref decoderErrors, rejectedNow - rejectedFrames);
                    rejectedFrames = rejectedNow;
                    Interlocked.Add(ref decoded, decodedNow);
                    if (decodedNow > 0)
                    {
                        lastDecodeTicks = decoder.LastDecodedTicks;
                        Interlocked.Exchange(ref lastPushReceiveToDecodeTicks, lastDecodeTicks - lastReceiveTicks);
                        lastDecodedWidth = decoder.LastDecodedWidth;
                        lastDecodedHeight = decoder.LastDecodedHeight;
                        lastDecodedStride = decoder.LastDecodedStride;
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested || session?.Token.IsCancellationRequested == true) { }
            catch (Exception error) when ((error is IOException or SocketException) &&
                (ct.IsCancellationRequested || session?.Token.IsCancellationRequested == true)) { }
            catch (OperationCanceledException)
            {
                if (session?.State == ReceiverState.Streaming) session.MarkVideoIssue("video_timeout");
                Report("Video read timeout; closing stale channel");
                Interlocked.Increment(ref networkErrors);
            }
            catch (Exception error) when (error is IOException or AuthenticationException or InvalidDataException or InvalidOperationException or SocketException or ExternalException or JsonException or FormatException or CryptographicException)
            {
                if (session?.State == ReceiverState.Streaming)
                    session.MarkVideoIssue(error is ExternalException ? "decoder_failure" : "video_failure");
                Report($"Video: {error.Message}");
                if (error is ExternalException) Interlocked.Increment(ref decoderErrors);
                else Interlocked.Increment(ref networkErrors);
                if (tls.IsAuthenticated)
                    try { await Wire.WriteControlAsync(tls, Trust == null ? (object)new { type = "error", message = error.Message }
                        : new { type = "error", version = 3, code = ProductionError(error) }, ct); }
                    catch (Exception) { }
            }
            finally
            {
                if (Trust != null) EndProductionVideo(session, client); else session?.UnbindVideo();
                if (Trust != null) productionConnections.Release();
                lock (Gate) if (active == session) ClearFrames();
            }
        }
    }

}

public sealed record ReceiverSnapshot(string State, string Peer, string SessionId, string Mode,
    long ReceivedAccessUnits, long Keyframes, long DecodedFrames, long DecoderErrors, long NetworkErrors,
    long DecoderResets, long VideoConnections, ulong LastSequence, ulong LastPtsUs,
    ulong LastAndroidSendUs, long LastReceiveTicks, long LastDecodeTicks,
    long LastReceiveToDecodeTicks, uint Width, uint Height, uint Stride,
    int FrameQueueDepth, int FrameQueueHighWater, long SupersededPreviewFrames, long FrameEpoch = 0,
    string? ConnectionIssue = null, bool CaptureMayBeActive = false, long ControlDisconnects = 0);

internal enum ReceiverState { Disconnected, Connecting, Authenticating, Negotiating, Streaming, Recovering, Stopping, Error }

internal sealed class Session(string id, string mode, string lens, string peer, SslStream control)
{
    private readonly SemaphoreSlim sendGate = new(1, 1);
    private readonly CancellationTokenSource stop = new();
    private bool videoBound;
    private bool everStreamed;
    private string? currentIssue;
    private ReceiverState state = ReceiverState.Negotiating;
    private readonly TaskCompletionSource desktopStopAcknowledged = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public Task DesktopStopAcknowledged => desktopStopAcknowledged.Task;
    public void AcknowledgeDesktopStop() => desktopStopAcknowledged.TrySetResult();
    public string Id { get; } = id;
    public string Mode { get; internal set; } = mode;
    public string Lens { get; internal set; } = lens;
    public string Peer { get; } = peer;
    public ReceiverState State { get { lock (this) return state; } }
    public bool EverStreamed { get { lock (this) return everStreamed; } }
    public string? CurrentIssue { get { lock (this) return currentIssue; } }
    public void MarkVideoIssue(string issue) { lock (this) currentIssue = issue; }
    public CancellationToken Token => stop.Token;
    public bool TryBindVideo() { lock (this) { if (videoBound || stop.IsCancellationRequested) return false; videoBound = true; everStreamed = true; currentIssue = null; state = ReceiverState.Streaming; return true; } }
    public void UnbindVideo() { lock (this) { videoBound = false; if (!stop.IsCancellationRequested) state = ReceiverState.Recovering; } }
    public void BeginCameraChange() { lock (this) { if (!stop.IsCancellationRequested) { currentIssue = null; state = ReceiverState.Recovering; } } }
    public void Stop() { lock (this) state = ReceiverState.Stopping; desktopStopAcknowledged.TrySetCanceled(); stop.Cancel(); }
    public async Task SendAsync(object message, CancellationToken ct)
    {
        await sendGate.WaitAsync(ct);
        try { await Wire.WriteControlAsync(control, message, ct); }
        catch { Stop(); throw; } // A partially cancelled TLS record cannot be reused.
        finally { sendGate.Release(); }
    }
    public async Task StopAndSendAsync(object message, CancellationToken ct)
    {
        // Finish an in-flight sync record before cancellation, then serialize the Stop acknowledgement.
        // Cancelling a TLS write and reusing that stream can corrupt its next record.
        await sendGate.WaitAsync(ct);
        try { Stop(); await Wire.WriteControlAsync(control, message, ct); }
        finally { sendGate.Release(); }
    }
}

public static class DevelopmentIdentity
{
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Camylla", "m3-proof");

    public static string LoadOrCreateSecret()
    {
        Directory.CreateDirectory(DirectoryPath);
        var path = Path.Combine(DirectoryPath, "development-secret.txt");
        if (File.Exists(path))
        {
            var existing = File.ReadAllText(path).Trim();
            if (existing.Length != 64 || !existing.All(Uri.IsHexDigit))
                throw new InvalidDataException("Invalid stored development secret");
            return existing;
        }
        var secret = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        File.WriteAllText(path, secret);
        Console.WriteLine($"Generated development secret at {path}");
        return secret;
    }

    public static X509Certificate2 LoadOrCreate()
    {
        Directory.CreateDirectory(DirectoryPath);
        var path = Path.Combine(DirectoryPath, "receiver-dev-identity.pfx");
        if (File.Exists(path))
        {
            var stored = X509CertificateLoader.LoadPkcs12FromFile(path, null,
                X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
            if (stored.NotAfter.ToUniversalTime() > DateTime.UtcNow.AddDays(1)) return stored;
            stored.Dispose();
        }
        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest("CN=Camylla M3 development receiver", rsa, HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        var eku = new OidCollection { new("1.3.6.1.5.5.7.3.1") };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(eku, true));
        using var temporary = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddDays(30));
        File.WriteAllBytes(path, temporary.Export(X509ContentType.Pkcs12));
        Console.WriteLine($"Generated development receiver identity at {path}");
        return X509CertificateLoader.LoadPkcs12FromFile(path, null,
            X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
    }
}
