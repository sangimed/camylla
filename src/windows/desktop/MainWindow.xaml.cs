using System.Buffers;
using System.Diagnostics;
using System.Runtime.InteropServices.WindowsRuntime;
using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Camylla.Desktop;

public sealed partial class MainWindow : Window
{
    private readonly ReceiverHost receiver = new();
    private readonly CancellationTokenSource stop = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private WriteableBitmap? imageSource;
    private Task? receiverTask;
    private bool rendering;
    private bool closing;
    private long lastPreviewTicks;
    private long displayed;
    private string lastSessionId = "none";
    private string? lastError;
    private StreamWriter? diagnosticsLog;
    private long lastLogTicks;

    public MainWindow()
    {
        InitializeComponent();
        timer.Tick += OnTick;
        Closed += OnClosed;
        receiver.Log += message =>
        {
            if (!message.StartsWith("Development session secret:", StringComparison.Ordinal))
                Debug.WriteLine(message);
        };
        try
        {
            var directory = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData), "Camylla");
            Directory.CreateDirectory(directory);
            diagnosticsLog = new StreamWriter(Path.Combine(directory, "m4-1-preview-diagnostics.csv"), false)
                { AutoFlush = true };
            diagnosticsLog.WriteLine("utc,state,receivedAU,decoded,displayed,superseded,queueDepth,queueHighWater,decoderErrors,networkErrors,width,height,stride,nv12ActiveBuffers,nv12BufferHighWater");
        }
        catch (IOException error) { Debug.WriteLine(error); }
        catch (UnauthorizedAccessException error) { Debug.WriteLine(error); }
        receiverTask = Task.Run(() => receiver.RunAsync(stop.Token));
        timer.Start();
    }

    private async void OnTick(object? sender, object e)
    {
        if (rendering || closing) return;
        rendering = true;
        try
        {
            if (receiverTask is { IsFaulted: true })
                lastError = receiverTask.Exception?.GetBaseException().Message;
            var snapshot = receiver.Snapshot();
            if (snapshot.SessionId != lastSessionId)
            {
                lastSessionId = snapshot.SessionId;
                lastPreviewTicks = 0;
            }
            using var frame = receiver.Frames.TakeLatest();
            if (frame != null && snapshot.State == "Streaming")
            {
                var bytes = Nv12BgraConverter.RequiredBytes(frame);
                // One BGRA conversion at a time; the buffer returns to the pool after UI copy.
                var bgra = await Task.Run(() =>
                {
                    var rented = ArrayPool<byte>.Shared.Rent(bytes);
                    try { Nv12BgraConverter.Convert(frame, rented.AsSpan(0, bytes)); return rented; }
                    catch { ArrayPool<byte>.Shared.Return(rented); throw; }
                });
                try
                {
                    if (closing) return;
                    var current = receiver.Snapshot();
                    if (current.State != "Streaming" || current.SessionId != snapshot.SessionId)
                    {
                        ClearPreview();
                        return;
                    }
                    // A slow conversion must not make an old frame look newly live.
                    if (Stopwatch.GetElapsedTime(frame.DecodedAtTicks) >= TimeSpan.FromSeconds(2))
                    {
                        ClearPreview();
                        return;
                    }
                    snapshot = current;
                    if (imageSource == null || imageSource.PixelWidth != frame.Width ||
                        imageSource.PixelHeight != frame.Height)
                    {
                        imageSource = new WriteableBitmap(frame.Width, frame.Height);
                        PreviewImage.Source = imageSource;
                    }
                    using var pixels = imageSource.PixelBuffer.AsStream();
                    pixels.Position = 0;
                    pixels.Write(bgra, 0, bytes);
                    imageSource.Invalidate();
                }
                finally { ArrayPool<byte>.Shared.Return(bgra); }
                lastPreviewTicks = Stopwatch.GetTimestamp();
                displayed++;
            }
            var fresh = lastPreviewTicks != 0 &&
                Stopwatch.GetElapsedTime(lastPreviewTicks) < TimeSpan.FromSeconds(2) &&
                snapshot.State == "Streaming";
            WaitingText.Visibility = fresh ? Visibility.Collapsed : Visibility.Visible;
            if (!fresh) ClearPreview();
            StateText.Text = lastError != null ? $"Receiver error: {lastError}" :
                fresh ? $"Streaming · {snapshot.Width} × {snapshot.Height}" :
                $"Waiting for phone · {snapshot.State}";
            PinBox.Text = receiver.CertificatePin;
            SecretBox.Text = receiver.DevelopmentSecret;
            DiagnosticsText.Text =
                $"Received AU {snapshot.ReceivedAccessUnits} · decoded {snapshot.DecodedFrames} · displayed {displayed} " +
                $"· superseded {snapshot.SupersededPreviewFrames} · queue {snapshot.FrameQueueDepth}/1 " +
                $"· high-water {snapshot.FrameQueueHighWater} · decoder errors {snapshot.DecoderErrors} " +
                $"· network errors {snapshot.NetworkErrors} · resets {snapshot.DecoderResets} " +
                $"· NV12 {snapshot.Width}×{snapshot.Height}/stride {snapshot.Stride} " +
                $"· active buffers {OwnedNv12Frame.ActiveBufferCount}/high-water {OwnedNv12Frame.BufferHighWater} " +
                $"· last sequence {snapshot.LastSequence} · PTS {snapshot.LastPtsUs} µs";
            WriteDiagnostics(snapshot);
        }
        catch (Exception error)
        {
            lastError = error.Message;
        }
        finally { rendering = false; }
    }

    private void OnClosed(object sender, WindowEventArgs args)
    {
        closing = true;
        timer.Stop();
        stop.Cancel();
        receiver.Stop();
        if (receiverTask != null)
            try { receiverTask.GetAwaiter().GetResult(); }
            catch (OperationCanceledException) { }
            catch (Exception error) { Debug.WriteLine(error); }
        receiver.Dispose();
        stop.Dispose();
        diagnosticsLog?.Dispose();
        ClearPreview();
    }

    private void WriteDiagnostics(ReceiverSnapshot snapshot)
    {
        if (diagnosticsLog == null || (lastLogTicks != 0 &&
            Stopwatch.GetElapsedTime(lastLogTicks) < TimeSpan.FromSeconds(5))) return;
        lastLogTicks = Stopwatch.GetTimestamp();
        try
        {
            if (diagnosticsLog.BaseStream.Length >= 1_000_000) return;
            diagnosticsLog.WriteLine(FormattableString.Invariant($"{DateTime.UtcNow:O},{snapshot.State},{snapshot.ReceivedAccessUnits},{snapshot.DecodedFrames},{displayed},{snapshot.SupersededPreviewFrames},{snapshot.FrameQueueDepth},{snapshot.FrameQueueHighWater},{snapshot.DecoderErrors},{snapshot.NetworkErrors},{snapshot.Width},{snapshot.Height},{snapshot.Stride},{OwnedNv12Frame.ActiveBufferCount},{OwnedNv12Frame.BufferHighWater}"));
        }
        catch (IOException error) { Debug.WriteLine(error); diagnosticsLog.Dispose(); diagnosticsLog = null; }
    }

    private void ClearPreview()
    {
        PreviewImage.Source = null;
        imageSource = null;
        WaitingText.Visibility = Visibility.Visible;
        lastPreviewTicks = 0;
    }
}
