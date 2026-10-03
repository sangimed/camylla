using System.Net;
using System.Diagnostics;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Camylla.Receiver;

internal static class Program
{
    private static int ControlPort = 58413;
    private static int VideoPort = 58414;
    private static readonly TimeSpan ControlIdleTimeout = TimeSpan.FromSeconds(8);
    private static Session? active;
    private static readonly object Gate = new();
    private static long received, keyframes, decoded, decoderErrors, networkErrors, decoderResets, reconnects;
    private static ulong lastSequence, lastPts, lastAndroidSendUs;
    private static long lastReceiveTicks, lastDecodeTicks;
    private static long lastPushReceiveToDecodeTicks;
    private static uint lastDecodedWidth, lastDecodedHeight, lastDecodedStride;

    private static async Task Main(string[] args)
    {
        if (args.Length == 1 && args[0] == "--self-test") { await SelfTest.RunAsync(); return; }
        if (args.Length == 1 && args[0] == "--decoder-smoke") {
            using var decoder = new MfDecoder("1280x720@30");
            decoder.Reset();
            Console.WriteLine("Media Foundation decoder create/reset passed");
            return;
        }
        if (args.Length == 2 && args[0] == "--decoder-file") {
            SelfTest.DecodeAnnexBFile(args[1]);
            return;
        }
        if (args.Length is 2 or 4 && args[0] == "--loopback-test") {
            var controlPort = args.Length == 4 ? int.Parse(args[2]) : ControlPort;
            var videoPort = args.Length == 4 ? int.Parse(args[3]) : VideoPort;
            await LoopbackSelfTest.RunAsync(args[1], controlPort, videoPort);
            return;
        }
        if (args.Length == 3 && args[0] == "--ports") {
            ControlPort = int.Parse(args[1]);
            VideoPort = int.Parse(args[2]);
            if (ControlPort is < 1 or > 65535 || VideoPort is < 1 or > 65535 || ControlPort == VideoPort)
                throw new ArgumentException("Invalid listener ports");
        }
        else if (args.Length != 0) throw new ArgumentException("Usage: Camylla.Receiver [--self-test|--decoder-smoke|--decoder-file path|--loopback-test path [controlPort videoPort]|--ports controlPort videoPort]");
        var certificate = DevelopmentIdentity.LoadOrCreate();
        var token = DevelopmentIdentity.LoadOrCreateSecret();
        Console.WriteLine($"Development certificate SHA-256 pin: {Convert.ToHexString(SHA256.HashData(certificate.RawData))}");
        Console.WriteLine($"Development session secret: {token}");
        Console.WriteLine($"Control TLS/TCP port {ControlPort}; video TLS/TCP port {VideoPort}");
        var control = new TcpListener(IPAddress.Any, ControlPort);
        var video = new TcpListener(IPAddress.Any, VideoPort);
        control.Start(4);
        video.Start(4);
        using var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
        await Task.WhenAll(AcceptControlAsync(control, certificate, token, stop.Token),
            AcceptVideoAsync(video, certificate, token, stop.Token), PrintStatsAsync(stop.Token));
    }

    private static async Task AcceptControlAsync(TcpListener listener, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => HandleControlAsync(client, certificate, token, ct), ct);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private static async Task HandleControlAsync(TcpClient client, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
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
                Console.WriteLine($"Authenticated control peer={session.Peer} session={id} mode={mode} lens={lens}");
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
                Console.Error.WriteLine("Control heartbeat timed out; retiring stale session");
                Interlocked.Increment(ref networkErrors);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
            catch (Exception error) when (error is IOException or AuthenticationException or InvalidDataException or InvalidOperationException or SocketException)
            {
                Console.Error.WriteLine($"Control: {error.Message}");
                if (tls.IsAuthenticated)
                    try { await Wire.WriteControlAsync(tls, new { type = "error", message = error.Message }, ct); }
                    catch (Exception) { /* The peer may already have disconnected. */ }
            }
            finally
            {
                if (session != null)
                {
                    session.Stop();
                    lock (Gate) { if (active == session) active = null; }
                    Console.WriteLine($"Control session {session.Id} ended");
                }
            }
        }
    }

    private static async Task AcceptVideoAsync(TcpListener listener, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await listener.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => HandleVideoAsync(client, certificate, token, ct), ct);
            }
            catch (OperationCanceledException) { break; }
        }
    }

    private static async Task HandleVideoAsync(TcpClient client, X509Certificate2 certificate,
        string token, CancellationToken ct)
    {
        using (client)
        using (var tls = new SslStream(client.GetStream(), false))
        {
            Session? session = null;
            try
            {
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions {
                    ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, ct);
                using var bind = await Wire.ReadControlAsync(tls, ct);
                var value = bind.RootElement;
                if (Wire.RequiredString(value, "type") != "bind" || Wire.RequiredInt(value, "version") != Wire.Version ||
                    !Wire.FixedHexEquals(Wire.RequiredString(value, "secret"), token))
                    throw new AuthenticationException("Video authentication failed");
                var id = Wire.RequiredString(value, "session");
                lock (Gate)
                {
                    if (active == null || !Wire.FixedHexEquals(active.Id, id) || !active.TryBindVideo())
                        throw new AuthenticationException("Video/control session mismatch or duplicate video channel");
                    session = active;
                }
                await Wire.WriteControlAsync(tls, new { type = "ready", session = id }, ct);
                Interlocked.Increment(ref reconnects);
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, session.Token);
                using var decoder = new MfDecoder(session.Mode);
                byte[]? config = null;
                uint generation = 0;
                ulong? previous = null;
                var needsKey = true;
                await session.SendAsync(new { type = "sync" }, linked.Token);
                Console.WriteLine($"Video bound session={id} peer={client.Client.RemoteEndPoint}");
                while (!linked.IsCancellationRequested)
                {
                    using var messageTimeout = CancellationTokenSource.CreateLinkedTokenSource(linked.Token);
                    messageTimeout.CancelAfter(TimeSpan.FromSeconds(2));
                    var unit = await Wire.ReadVideoAsync(tls, messageTimeout.Token);
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
                        await session.SendAsync(new { type = "sync" }, linked.Token);
                    }
                    previous = unit.Sequence;
                    if (unit.Flags == Wire.Config)
                    {
                        config = unit.Payload;
                        generation = unit.Generation;
                        continue;
                    }
                    if (unit.Generation != generation)
                    {
                        decoder.Reset(); Interlocked.Increment(ref decoderResets);
                        config = null; needsKey = true;
                        await session.SendAsync(new { type = "sync" }, linked.Token);
                        continue;
                    }
                    int decodedNow;
                    if (needsKey)
                    {
                        if (unit.Flags != Wire.Key || config == null) continue;
                        var joined = new byte[config.Length + unit.Payload.Length];
                        config.CopyTo(joined, 0);
                        unit.Payload.CopyTo(joined, config.Length);
                        decodedNow = decoder.Push(joined, unit.PtsUs);
                        needsKey = false;
                    }
                    else
                    {
                        decodedNow = decoder.Push(unit.Payload, unit.PtsUs);
                    }
                    if (unit.Flags == Wire.Key && needsKey == false) Interlocked.Increment(ref keyframes);
                    Interlocked.Add(ref decoded, decodedNow);
                    if (decodedNow > 0)
                    {
                        lastDecodeTicks = decoder.LastDecodedTicks;
                        Interlocked.Exchange(ref lastPushReceiveToDecodeTicks,
                            lastDecodeTicks - lastReceiveTicks);
                        lastDecodedWidth = decoder.LastDecodedWidth;
                        lastDecodedHeight = decoder.LastDecodedHeight;
                        lastDecodedStride = decoder.LastDecodedStride;
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested || session?.Token.IsCancellationRequested == true) { }
            catch (OperationCanceledException)
            {
                Console.Error.WriteLine("Video read timeout; closing stale channel");
                Interlocked.Increment(ref networkErrors);
            }
            catch (Exception error) when (error is IOException or AuthenticationException or InvalidDataException or InvalidOperationException or SocketException or ExternalException)
            {
                Console.Error.WriteLine($"Video: {error.Message}");
                if (error is ExternalException) Interlocked.Increment(ref decoderErrors);
                else Interlocked.Increment(ref networkErrors);
                if (tls.IsAuthenticated)
                    try { await Wire.WriteControlAsync(tls, new { type = "error", message = error.Message }, ct); }
                    catch (Exception) { }
            }
            finally { session?.UnbindVideo(); }
        }
    }

    private static async Task PrintStatsAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(5000, ct); } catch (OperationCanceledException) { break; }
            Session? session;
            lock (Gate) session = active;
            Console.WriteLine($"state={session?.State.ToString() ?? "Disconnected"} peer={session?.Peer ?? "none"} " +
                $"session={session?.Id ?? "none"} mode={session?.Mode ?? "none"} " +
                $"receivedAU={Interlocked.Read(ref received)} keyframes={Interlocked.Read(ref keyframes)} " +
                $"decoded={Interlocked.Read(ref decoded)} decoderErrors={Interlocked.Read(ref decoderErrors)} " +
                $"networkErrors={Interlocked.Read(ref networkErrors)} " +
                $"decoderResets={Interlocked.Read(ref decoderResets)} videoConnections={Interlocked.Read(ref reconnects)} " +
                $"lastSeq={lastSequence} lastPtsUs={lastPts} androidSendUs={lastAndroidSendUs} " +
                $"receiveTicks={lastReceiveTicks} decodedFrameTicks={lastDecodeTicks} " +
                $"lastPushReceiveToDecodeMs={Stopwatch.GetElapsedTime(0, Interlocked.Read(ref lastPushReceiveToDecodeTicks)).TotalMilliseconds:F2} " +
                $"nv12={lastDecodedWidth}x{lastDecodedHeight}/stride{lastDecodedStride} " +
                "queueDepth=0 queueHighWater=1");
        }
    }
}

internal enum ReceiverState { Disconnected, Connecting, Authenticating, Negotiating, Streaming, Recovering, Stopping, Error }

internal sealed class Session(string id, string mode, string lens, string peer, SslStream control)
{
    private readonly SemaphoreSlim sendGate = new(1, 1);
    private readonly CancellationTokenSource stop = new();
    private bool videoBound;
    private ReceiverState state = ReceiverState.Negotiating;
    public string Id { get; } = id;
    public string Mode { get; } = mode;
    public string Lens { get; } = lens;
    public string Peer { get; } = peer;
    public ReceiverState State { get { lock (this) return state; } }
    public CancellationToken Token => stop.Token;
    public bool TryBindVideo() { lock (this) { if (videoBound || stop.IsCancellationRequested) return false; videoBound = true; state = ReceiverState.Streaming; return true; } }
    public void UnbindVideo() { lock (this) { videoBound = false; if (!stop.IsCancellationRequested) state = ReceiverState.Recovering; } }
    public void Stop() { lock (this) state = ReceiverState.Stopping; stop.Cancel(); }
    public async Task SendAsync(object message, CancellationToken ct)
    {
        await sendGate.WaitAsync(ct);
        try { await Wire.WriteControlAsync(control, message, ct); }
        finally { sendGate.Release(); }
    }
}

internal static class DevelopmentIdentity
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
