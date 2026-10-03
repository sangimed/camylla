using System.Diagnostics;
using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Camylla.Desktop;

public sealed partial class MainWindow : Window
{
    private readonly ReceiverHost receiver = new();
    private readonly CancellationTokenSource stop = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private readonly SoftwareBitmapSource imageSource = new();
    private Task? receiverTask;
    private bool rendering;
    private bool closing;
    private long lastPreviewTicks;
    private long displayed;
    private string lastSessionId = "none";
    private string? lastError;

    public MainWindow()
    {
        InitializeComponent();
        PreviewImage.Source = imageSource;
        timer.Tick += OnTick;
        Closed += OnClosed;
        receiver.Log += message => Debug.WriteLine(message);
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
            var frame = receiver.Frames.TakeLatest();
            if (frame != null && snapshot.State == "Streaming")
            {
                // Convert a detached owned frame, never the borrowed decoder callback memory.
                var bgra = await Task.Run(() => ConvertNv12ToBgra(frame));
                if (closing) return;
                using var writer = new DataWriter();
                writer.WriteBytes(bgra);
                using var bitmap = SoftwareBitmap.CreateCopyFromBuffer(writer.DetachBuffer(),
                    BitmapPixelFormat.Bgra8, frame.Width, frame.Height, BitmapAlphaMode.Ignore);
                await imageSource.SetBitmapAsync(bitmap);
                lastPreviewTicks = Stopwatch.GetTimestamp();
                displayed++;
            }
            var fresh = lastPreviewTicks != 0 &&
                Stopwatch.GetElapsedTime(lastPreviewTicks) < TimeSpan.FromSeconds(2) &&
                snapshot.State == "Streaming";
            WaitingText.Visibility = fresh ? Visibility.Collapsed : Visibility.Visible;
            if (!fresh) await imageSource.SetBitmapAsync(null);
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
                $"· last sequence {snapshot.LastSequence} · PTS {snapshot.LastPtsUs} µs";
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
        imageSource.Dispose();
    }

    private static byte[] ConvertNv12ToBgra(OwnedNv12Frame frame)
    {
        var result = new byte[checked(frame.Width * frame.Height * 4)];
        var source = frame.Pixels;
        var uvBase = frame.Stride * frame.Height;
        for (var y = 0; y < frame.Height; y++)
        for (var x = 0; x < frame.Width; x++)
        {
            var luma = source[y * frame.Stride + x] - 16;
            var chroma = uvBase + (y / 2) * frame.Stride + (x & ~1);
            var u = source[chroma] - 128;
            var v = source[chroma + 1] - 128;
            var c = Math.Max(0, luma) * 298;
            var offset = (y * frame.Width + x) * 4;
            result[offset] = Clamp((c + 516 * u + 128) >> 8);
            result[offset + 1] = Clamp((c - 100 * u - 208 * v + 128) >> 8);
            result[offset + 2] = Clamp((c + 409 * v + 128) >> 8);
            result[offset + 3] = 255;
        }
        return result;
    }

    private static byte Clamp(int value) => (byte)Math.Clamp(value, 0, 255);
}
