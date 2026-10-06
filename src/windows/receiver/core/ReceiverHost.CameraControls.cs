using System.Text.Json;

namespace Camylla.Receiver;

public sealed record CameraControlCapability(string Key, string Label, double Minimum, double Maximum,
    double Step, string Unit, IReadOnlyDictionary<int, string> Choices, string? UnavailableReason);
public sealed record CameraControlSnapshot(string SessionId, int Generation,
    IReadOnlyDictionary<string, CameraControlCapability> Capabilities,
    IReadOnlyDictionary<string, double> Applied, IReadOnlySet<string> Pending, string Focus, string Message);
public sealed record CameraCommandResult(double? Applied, string? Error);

public sealed partial class ReceiverHost
{
    private readonly object cameraGate = new();
    private string cameraSessionId = "";
    private int cameraGeneration;
    private long cameraRequestId;
    private readonly Dictionary<string, CameraControlCapability> cameraCapabilities = [];
    private Dictionary<string, double> cameraApplied = [];
    private HashSet<string> cameraPending = [];
    private string cameraFocus = "Unavailable", cameraMessage = "";
    private sealed record CameraRequest(string SessionId, int Generation, string Control, CameraControlCapability Capability, TaskCompletionSource<CameraCommandResult> Completion);
    private readonly Dictionary<long, CameraRequest> cameraRequests = [];

    public CameraControlSnapshot CameraControls()
    {
        lock (cameraGate) return new(cameraSessionId, cameraGeneration, new Dictionary<string, CameraControlCapability>(cameraCapabilities),
            new Dictionary<string, double>(cameraApplied), new HashSet<string>(cameraPending), cameraFocus, cameraMessage);
    }
    public Task<CameraCommandResult> SetCameraControlAsync(string control, double value, CancellationToken ct = default) =>
        SendCameraControlAsync(control,value,ct,null,null);
    public Task<CameraCommandResult> SetCameraFocusAsync(double x, double y, CancellationToken ct = default) =>
        !double.IsFinite(x) || !double.IsFinite(y) || x < 0 || x > 1 || y < 0 || y > 1
            ? Task.FromResult(new CameraCommandResult(null,"Invalid focus coordinates")) : SendCameraControlAsync("focusPoint",1,ct,x,y);
    private async Task<CameraCommandResult> SendCameraControlAsync(string control, double value, CancellationToken ct, double? x, double? y)
    {
        Session session;
        lock (Gate) session = active ?? throw new InvalidOperationException("Start capture on the phone first");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct, session.Token);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        long id; CameraRequest request;
        Task sending;
        lock (cameraGate)
        {
            if (session.Id != cameraSessionId || session.Token.IsCancellationRequested || !cameraCapabilities.TryGetValue(control, out var capability))
                return new(null, "Control unavailable for the active session");
            if (capability.UnavailableReason != null) return new(null, capability.UnavailableReason);
            if (!double.IsFinite(value) || value < capability.Minimum || value > capability.Maximum ||
                capability.Choices.Count > 0 && (value != Math.Truncate(value) || !capability.Choices.ContainsKey((int)value)) ||
                capability.Step == 1 && value != Math.Truncate(value)) return new(null, "Unsupported value");
            if (cameraRequests.Count >= 32) return new(null, "Too many pending commands");
            id = ++cameraRequestId;
            request = new(session.Id, cameraGeneration, control, capability, new(TaskCreationOptions.RunContinuationsAsynchronously));
            cameraRequests.Add(id, request);
            // Allocate the ID and enter Session's serialized writer under the same lock, so
            // concurrent desktop callers cannot put request 2 on the wire before request 1.
            sending = session.SendAsync(x != null ? (object)new { type = "camera_focus", version = 3, session_id = session.Id, generation = request.Generation,
                request_id = id, x, y } : new { type = "camera_command", version = 3, session_id = session.Id, generation = request.Generation,
                request_id = id, control, value }, timeout.Token);
        }
        try
        {
            await sending;
            return await request.Completion.Task.WaitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            if (!session.Token.IsCancellationRequested)
                try { await session.SendAsync(new { type = "camera_cancel", version = 3, session_id = session.Id,
                    generation = request.Generation, request_id = id }, session.Token); } catch (Exception error) when (error is IOException or OperationCanceledException) { }
            return new(null, ct.IsCancellationRequested ? "Cancelled" : session.Token.IsCancellationRequested ? "Capture stopped" : "Camera command timed out");
        }
        finally { lock (cameraGate) cameraRequests.Remove(id); }
    }
    private void ClearCameraSession(Session session)
    {
        lock (cameraGate)
        {
            if (cameraSessionId != session.Id) return;
            foreach (var request in cameraRequests.Values) request.Completion.TrySetResult(new(null, "Capture stopped"));
            cameraRequests.Clear(); cameraCapabilities.Clear(); cameraApplied.Clear(); cameraPending.Clear();
            cameraSessionId = ""; cameraGeneration = 0; cameraFocus = "Unavailable"; cameraMessage = "Capture stopped";
        }
    }
    private void BeginCameraControlsChange(Session session, int generation)
    {
        lock (cameraGate)
        {
            if (cameraSessionId != session.Id || generation <= cameraGeneration) return;
            foreach (var request in cameraRequests.Values.Where(r => r.Control is not ("camera" or "mode")))
                request.Completion.TrySetResult(new(null, "Camera generation changed"));
            cameraGeneration = generation;
            cameraCapabilities.Clear(); cameraApplied.Clear(); cameraPending.Clear();
            cameraFocus = "Unavailable"; cameraMessage = "Changing camera";
        }
    }
    private void ReceiveCameraRecord(Session session, JsonElement record)
    {
        var type = Wire.RequiredString(record, "type");
        if (type == "camera_capability") V3Record.Require(record, type, "session_id", "generation", "option");
        else if (type == "camera_state") V3Record.Require(record, type, "session_id", "generation", "applied", "pending", "focus", "message");
        else V3Record.Require(record, type, "session_id", "generation", "request_id", "control", "applied", "error");
        if (V3Record.Hex(record, "session_id", 16) != session.Id) throw new InvalidDataException("stale_session");
        var generation = Wire.RequiredInt(record, "generation");
        if (generation < 1) throw new InvalidDataException("invalid_record");
        lock (cameraGate)
        {
            if (type == "camera_result")
            {
                if (!record.GetProperty("request_id").TryGetInt64(out var id) || id <= 0) throw new InvalidDataException("invalid_record");
                if (!cameraRequests.TryGetValue(id, out var request)) return;
                if (request.SessionId != session.Id || request.Generation != generation || request.Control != Wire.RequiredString(record, "control"))
                    throw new InvalidDataException("stale_session");
                var applied = record.GetProperty("applied"); var error = record.GetProperty("error");
                if ((applied.ValueKind == JsonValueKind.Null) == (error.ValueKind == JsonValueKind.Null)) throw new InvalidDataException("invalid_record");
                if (error.ValueKind != JsonValueKind.Null && error.ValueKind != JsonValueKind.String) throw new InvalidDataException("invalid_record");
                double? actual = null;
                if (applied.ValueKind != JsonValueKind.Null) {
                    if (!applied.TryGetDouble(out var number) || !double.IsFinite(number)) throw new InvalidDataException("invalid_record");
                    var capability = request.Capability;
                    if (number < capability.Minimum || number > capability.Maximum || capability.Choices.Count > 0 &&
                        (number != Math.Truncate(number) || !capability.Choices.ContainsKey((int)number))) throw new InvalidDataException("invalid_record");
                    actual = number;
                }
                request.Completion.TrySetResult(new(actual, error.ValueKind == JsonValueKind.Null ? null : error.GetString()));
                return;
            }
            if (cameraSessionId != session.Id || generation > cameraGeneration)
            {
                // A lens/mode command may still receive its final result after first capture of
                // the new generation. Every other old-generation request is cancelled.
                foreach (var request in cameraRequests.Values.Where(r => r.Control is not ("camera" or "mode")))
                    request.Completion.TrySetResult(new(null, "Camera generation changed"));
                cameraCapabilities.Clear(); cameraApplied.Clear(); cameraPending.Clear();
                cameraSessionId = session.Id; cameraGeneration = generation;
            }
            if (generation != cameraGeneration) return;
            if (type == "camera_capability")
            {
                var option = record.GetProperty("option");
                var key = BoundedString(option, "key", 40); var label = BoundedString(option, "label", 100);
                var min = Number(option, "min"); var max = Number(option, "max"); var step = Number(option, "step");
                if (min > max || step <= 0 || cameraCapabilities.Count >= 40 && !cameraCapabilities.ContainsKey(key)) throw new InvalidDataException("invalid_record");
                var choices = new Dictionary<int, string>();
                foreach (var choice in option.GetProperty("choices").EnumerateObject()) {
                    if (choices.Count >= 32 || !int.TryParse(choice.Name, out var choiceId) || choice.Value.ValueKind != JsonValueKind.String ||
                        choice.Value.GetString()!.Length > 100 || choiceId < min || choiceId > max) throw new InvalidDataException("invalid_record");
                    choices.Add(choiceId, choice.Value.GetString()!);
                }
                var reason = option.GetProperty("reason");
                if (reason.ValueKind is not (JsonValueKind.Null or JsonValueKind.String)) throw new InvalidDataException("invalid_record");
                if (reason.ValueKind == JsonValueKind.String && reason.GetString()!.Length > 1024) throw new InvalidDataException("invalid_record");
                cameraCapabilities[key] = new(key, label, min, max, step, BoundedString(option, "unit", 100), choices,
                    reason.ValueKind == JsonValueKind.Null ? null : reason.GetString());
            }
            else
            {
                var applied = new Dictionary<string, double>();
                foreach (var value in record.GetProperty("applied").EnumerateObject()) {
                    if (applied.Count >= 40 || value.Name.Length > 40 || !value.Value.TryGetDouble(out var number) || !double.IsFinite(number)) throw new InvalidDataException("invalid_record");
                    applied.Add(value.Name, number);
                }
                var pending = record.GetProperty("pending");
                if (pending.ValueKind != JsonValueKind.Array || pending.GetArrayLength() > 32) throw new InvalidDataException("invalid_record");
                cameraApplied = applied; cameraPending = pending.EnumerateArray().Select(v => v.GetString() ?? throw new InvalidDataException("invalid_record")).ToHashSet();
                cameraFocus = BoundedString(record, "focus", 100); cameraMessage = BoundedString(record, "message", 1024);
            }
        }
    }
    private static string BoundedString(JsonElement value, string key, int limit) {
        var text = Wire.RequiredString(value, key); if (text.Length > limit) throw new InvalidDataException("invalid_record"); return text;
    }
    private static double Number(JsonElement value, string key) {
        if (!value.GetProperty(key).TryGetDouble(out var number) || !double.IsFinite(number)) throw new InvalidDataException("invalid_record"); return number;
    }
}
