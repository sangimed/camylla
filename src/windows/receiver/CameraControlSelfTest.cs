using Camylla.Receiver;
using System.Text.Json;

internal static partial class ProductionSelfTest
{
    public static async Task RunCameraControlsAsync()
    {
        var directory = Path.Combine(Path.GetTempPath(), "Camylla-control-test-" + Guid.NewGuid().ToString("N"));
        using var trust = new ProductionTrust(directory);
        using var phone = TestIdentity("Camera control test phone");
        SeedPhone(trust, phone);
        var controlPort = FreePort(); var videoPort = FreePort();
        using var host = new ReceiverHost(controlPort, videoPort, trust);
        using var lifetime = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var running = host.RunAsync(lifetime.Token);
        try
        {
            using var control = await Connect(controlPort, trust.DesktopId, phone, lifetime.Token);
            await Wire.WriteControlAsync(control, new { type = "start", version = 3, phone_id = ProductionTrust.PeerId(phone),
                client_nonce = ProductionTrust.RandomHex(16), codec = "h264", modes = new[] { "1280x720@30" }, mode = "1280x720@30", lens = "rear" }, lifetime.Token);
            using var started = await Wire.ReadControlAsync(control, lifetime.Token);
            var id = Wire.RequiredString(started.RootElement, "session_id");
            async Task Capability(string key, string? reason = null, int generation = 1) => await Wire.WriteControlAsync(control,
                new { type = "camera_capability", version = 3, session_id = id, generation,
                    option = new { key, label = key, min = 1.0, max = 4.0, step = 0.01, unit = "×", reason, choices = new Dictionary<string, string>() } }, lifetime.Token);
            async Task State(int generation = 1) => await Wire.WriteControlAsync(control, new {
                type = "camera_state", version = 3, session_id = id, generation, applied = new { zoom = 1.0 }, pending = Array.Empty<string>(), focus = "Auto", message = "Ready" }, lifetime.Token);
            async Task Result(JsonElement request, double? actual, string? error) => await Wire.WriteControlAsync(control, new {
                type = "camera_result", version = 3, session_id = id, generation = request.GetProperty("generation").GetInt32(),
                request_id = request.GetProperty("request_id").GetInt64(), control = request.GetProperty("control").GetString(), applied = actual, error }, lifetime.Token);
            async Task WaitFor(Func<bool> condition) {
                for (var i = 0; i < 100; i++) { if (condition()) return; await Task.Delay(10, lifetime.Token); }
                throw new Exception("Timed out waiting for canonical camera state");
            }
            await Capability("zoom"); await Capability("focus", "Fixed-focus lens"); await State();
            await WaitFor(() => host.CameraControls().Capabilities.Count == 2);
            Check((await host.SetCameraControlAsync("focus", 2)).Error == "Fixed-focus lens", "explicit unsupported reason");
            Check((await host.SetCameraControlAsync("zoom", double.NaN)).Error != null, "nonfinite value rejected locally");
            Check((await host.SetCameraControlAsync("zoom", 5)).Error != null, "out-of-range value rejected locally");
            var pending = host.SetCameraControlAsync("zoom", 2, lifetime.Token);
            using (var command = await Wire.ReadControlAsync(control, lifetime.Token))
            {
                Check(Wire.RequiredString(command.RootElement, "type") == "camera_command" && !pending.IsCompleted,
                    "TLS request receipt is not an applied acknowledgement");
                await Result(command.RootElement, 1.98, null);
            }
            Check((await pending).Applied == 1.98, "camera-confirmed actual value returned");
            var rejected = host.SetCameraControlAsync("zoom", 3, lifetime.Token);
            using (var command = await Wire.ReadControlAsync(control, lifetime.Token)) await Result(command.RootElement, null, "Camera refused zoom");
            Check((await rejected).Error == "Camera refused zoom", "camera rejection reaches desktop");
            await Capability("focusPoint");
            await WaitFor(() => host.CameraControls().Capabilities.ContainsKey("focusPoint"));
            Check((await host.SetCameraFocusAsync(double.NaN,0.5)).Error != null, "invalid focus coordinates rejected");
            var focus = host.SetCameraFocusAsync(0.25,0.75,lifetime.Token);
            using (var command = await Wire.ReadControlAsync(control,lifetime.Token)) {
                Check(Wire.RequiredString(command.RootElement,"type") == "camera_focus" && command.RootElement.GetProperty("x").GetDouble() == 0.25,
                    "desktop focus coordinates use authenticated session");
                await Wire.WriteControlAsync(control,new { type = "camera_result",version = 3,session_id = id,generation = 1,
                    request_id = command.RootElement.GetProperty("request_id").GetInt64(),control = "focusPoint",applied = 1,error = (string?)null },lifetime.Token);
            }
            Check((await focus).Applied == 1 && host.CameraControls().Focus == "Auto", "focus target acknowledgement does not invent AF completion");
            using (var cancellation = new CancellationTokenSource())
            {
                var cancelled = host.SetCameraControlAsync("zoom", 2, cancellation.Token);
                using var command = await Wire.ReadControlAsync(control, lifetime.Token);
                cancellation.Cancel();
                using var cancel = await Wire.ReadControlAsync(control, lifetime.Token);
                Check(Wire.RequiredString(cancel.RootElement, "type") == "camera_cancel" &&
                    cancel.RootElement.GetProperty("request_id").GetInt64() == command.RootElement.GetProperty("request_id").GetInt64(), "cancellation reaches camera owner");
                Check((await cancelled).Error == "Cancelled", "cancelled desktop task returns explicit reason");
                await Result(command.RootElement, 2, null); // Late completion is ignored.
            }
            var obsolete = host.SetCameraControlAsync("zoom", 2, lifetime.Token);
            using var oldCommand = await Wire.ReadControlAsync(control, lifetime.Token);
            await Capability("zoom", generation: 2); await State(2);
            Check((await obsolete).Error == "Camera generation changed", "generation change cancels pending controls");
            await Result(oldCommand.RootElement, 2, null);
            await WaitFor(() => host.CameraControls().Generation == 2);
            Check(host.CameraControls().Applied.GetValueOrDefault("zoom") == 1, "late result cannot replace canonical state");
            var oldEpoch = host.Snapshot().FrameEpoch;
            await Wire.WriteControlAsync(control,new { type = "camera_changing",version = 3,session_id = id,generation = 3 },lifetime.Token);
            await WaitFor(() => host.CameraControls().Generation == 3);
            Check(host.CameraControls().Applied.Count == 0 && host.CameraControls().Capabilities.Count == 0 &&
                host.Snapshot().State == "Recovering", "camera change immediately clears stale controls and publication state");
            await Wire.WriteControlAsync(control,new { type = "camera_reconfigure",version = 3,session_id = id,
                request_nonce = ProductionTrust.RandomHex(16),mode = "1920x1080@30",lens = "front",generation = 3 },lifetime.Token);
            using var renewed = await Wire.ReadControlAsync(control,lifetime.Token);
            Check(Wire.RequiredString(renewed.RootElement,"type") == "video_ticket" && host.Snapshot().Mode == "1920x1080@30" &&
                host.Snapshot().FrameEpoch > oldEpoch && host.Frames.PendingDepth == 0, "reconfiguration clears publication before issuing new bind ticket");
            using var video = await Connect(videoPort,trust.DesktopId,phone,lifetime.Token);
            await Wire.WriteControlAsync(video,new { type = "bind",version = 3,session_id = id,phone_id = ProductionTrust.PeerId(phone),
                client_nonce = Wire.RequiredString(started.RootElement,"client_nonce"),server_nonce = Wire.RequiredString(started.RootElement,"server_nonce"),
                video_ticket = Wire.RequiredString(renewed.RootElement,"video_ticket") },lifetime.Token);
            using var ready = await Wire.ReadControlAsync(video,lifetime.Token);
            using var sync = await Wire.ReadControlAsync(control,lifetime.Token);
            Check(Wire.RequiredString(ready.RootElement,"type") == "ready" && Wire.RequiredString(sync.RootElement,"type") == "sync",
                "reconfigured video requires a fresh configuration/keyframe");
            await Capability("zoom",generation:3); await State(3);
            await WaitFor(() => host.CameraControls().Generation == 3);
            var stopped = host.SetCameraControlAsync("zoom", 2, lifetime.Token);
            using var finalCommand = await Wire.ReadControlAsync(control, lifetime.Token);
            await Wire.WriteControlAsync(control, new { type = "stop", version = 3, session_id = id }, lifetime.Token);
            using var stopReply = await Wire.ReadControlAsync(control, lifetime.Token);
            Check(Wire.RequiredString(stopReply.RootElement, "type") == "stopped", "Stop acknowledged");
            Check((await stopped).Error == "Capture stopped", "Stop cancels outstanding camera command");
            await WaitFor(() => host.CameraControls().SessionId.Length == 0);
            Check(host.CameraControls().Applied.Count == 0 && host.CameraControls().Capabilities.Count == 0, "Stop clears controls and applied state");
            Console.WriteLine("Camera controls over mutual TLS: bounds, unsupported reasons, applied/rejected acknowledgements, generation cancellation, late results and Stop passed");
        }
        finally { lifetime.Cancel(); host.Stop(); await running; }
    }
}
