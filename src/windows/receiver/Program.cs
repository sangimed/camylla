using System.Diagnostics;
using Camylla.Receiver;

if (args.Length == 1 && args[0] == "--pairing-self-test") { await ProductionSelfTest.RunAsync(); return; }
if (args.Length == 2 && args[0] == "--android-pairing-self-test")
{
    // Explicit local hardware harness. The capability is never printed or used to start capture.
    using var hardware = new ReceiverHost(production: true);
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(120));
    hardware.Log += Console.WriteLine;
    var invitation = hardware.Trust!.BeginInvitation(ProductionTrust.LanAddress());
    var file = Path.GetFullPath(args[1]);
    if (File.Exists(file)) throw new IOException("Use a fresh hardware invitation file");
    await File.WriteAllTextAsync(file, invitation.Uri);
    try { await hardware.RunAsync(deadline.Token); }
    catch (OperationCanceledException) when (deadline.IsCancellationRequested) { }
    finally { File.Delete(file); }
    return;
}
if (args.Length == 1 && args[0] == "--self-test") { await SelfTest.RunAsync(); return; }
if (args.Length == 1 && args[0] == "--ipc-host-smoke") { await SelfTest.IpcHostSmokeAsync(); return; }
if (args.Length == 1 && args[0] == "--frame-memory-smoke") { SelfTest.FrameMemorySmoke(); return; }
if (args.Length == 1 && args[0] == "--decoder-smoke") {
    using var decoder = new MfDecoder("1280x720@30");
    decoder.Reset();
    Console.WriteLine("Media Foundation decoder create/reset passed");
    return;
}
if (args.Length == 2 && args[0] == "--decoder-file") { SelfTest.DecodeAnnexBFile(args[1]); return; }
if (args.Length is 2 or 4 && args[0] == "--loopback-test") {
    await LoopbackSelfTest.RunAsync(args[1], args.Length == 4 ? int.Parse(args[2]) : 58413,
        args.Length == 4 ? int.Parse(args[3]) : 58414);
    return;
}
var development = args.Length > 0 && args[0] == "--development";
var options = development ? args.Skip(1).ToArray() : args;
if (options.Length != 0 && (options.Length != 3 || options[0] != "--ports"))
    throw new ArgumentException("Usage: Camylla.Receiver [--development] [--ports controlPort videoPort] or an explicit self-test command");
if (development) Console.WriteLine("M3 development diagnostic mode; not production pairing");
using var host = options.Length == 3
    ? new ReceiverHost(int.Parse(options[1]), int.Parse(options[2]), production: !development)
    : new ReceiverHost(production: !development);
using var stop = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
var run = host.RunAsync(stop.Token);
try {
    while (!stop.IsCancellationRequested) {
        await Task.Delay(5000, stop.Token);
        var s = host.Snapshot();
        Console.WriteLine($"state={s.State} peer={s.Peer} session={s.SessionId} mode={s.Mode} " +
            $"receivedAU={s.ReceivedAccessUnits} keyframes={s.Keyframes} decoded={s.DecodedFrames} decoderErrors={s.DecoderErrors} " +
            $"networkErrors={s.NetworkErrors} decoderResets={s.DecoderResets} videoConnections={s.VideoConnections} " +
            $"lastSeq={s.LastSequence} lastPtsUs={s.LastPtsUs} androidSendUs={s.LastAndroidSendUs} " +
            $"receiveTicks={s.LastReceiveTicks} decodedFrameTicks={s.LastDecodeTicks} " +
            $"lastPushReceiveToDecodeMs={Stopwatch.GetElapsedTime(0, s.LastReceiveToDecodeTicks).TotalMilliseconds:F2} " +
            $"nv12={s.Width}x{s.Height}/stride{s.Stride} queueDepth={s.FrameQueueDepth} " +
            $"queueHighWater={s.FrameQueueHighWater} supersededPreview={s.SupersededPreviewFrames}");
    }
} catch (OperationCanceledException) { }
stop.Cancel();
await run;
