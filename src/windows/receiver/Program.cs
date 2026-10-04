using System.Diagnostics;
using Camylla.Receiver;

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
if (args.Length != 0 && (args.Length != 3 || args[0] != "--ports"))
    throw new ArgumentException("Usage: Camylla.Receiver [--self-test|--frame-memory-smoke|--decoder-smoke|--decoder-file path|--loopback-test path [controlPort videoPort]|--ports controlPort videoPort]");
using var host = args.Length == 3
    ? new ReceiverHost(int.Parse(args[1]), int.Parse(args[2])) : new ReceiverHost();
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
