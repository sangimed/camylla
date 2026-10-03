using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;

namespace Camylla.Receiver;

/// <summary>Local, pull-driven, depth-one camera publication. No decoder callback waits for IPC.</summary>
public sealed class CameraPipePublisher : IAsyncDisposable
{
    public const string PipeName = "CamyllaFrameV1";
    public const int HeaderBytes = 72;
    private const int MaxClients = 4;
    private readonly ReceiverHost receiver;
    private readonly Func<(bool Streaming, string Session)> state;
    private readonly FrameReader reader;
    private readonly CancellationTokenSource stop = new();
    private readonly object gate = new();
    private readonly List<Task> clients = new();
    private readonly byte[] generation = Guid.NewGuid().ToByteArray();
    private OwnedNv12Frame? current;
    private string session = "none";
    private ulong sequence;
    private Task? run;
    private long published, neutral, failures;
    private bool firstInstance = true;
    private string? lastError;

    public long Published => Interlocked.Read(ref published);
    public long Neutral => Interlocked.Read(ref neutral);
    public long Failures => Interlocked.Read(ref failures);
    public string? LastError => Volatile.Read(ref lastError);

    public CameraPipePublisher(ReceiverHost receiver) : this(receiver, () =>
    {
        var snapshot = receiver.Snapshot();
        return (snapshot.State == "Streaming", snapshot.SessionId);
    }) { }

    internal CameraPipePublisher(ReceiverHost receiver, Func<(bool Streaming, string Session)> state)
    {
        this.receiver = receiver;
        this.state = state;
        reader = receiver.CreateFrameReader();
    }

    public void Start()
    {
        if (run != null) throw new InvalidOperationException("Publisher already started");
        run = Task.Run(RunAsync);
    }

    private async Task RunAsync()
    {
        var pump = Task.Run(PumpAsync);
        try
        {
            while (!stop.IsCancellationRequested)
            {
                NamedPipeServerStream? pipe = null;
                try
                {
                    pipe = CreateSecuredPipe();
                    await pipe.WaitForConnectionAsync(stop.Token);
                    var connected = pipe;
                    pipe = null;
                    var task = Task.Run(() => ServeAsync(connected));
                    lock (clients)
                    {
                        clients.RemoveAll(t => t.IsCompleted);
                        clients.Add(task);
                    }
                }
                catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or
                    IdentityNotMappedException)
                {
                    Volatile.Write(ref lastError, error.Message);
                    Interlocked.Increment(ref failures);
                    try { await Task.Delay(100, stop.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
                }
                finally { pipe?.Dispose(); }
            }
        }
        finally
        {
            await pump;
            Task[] pending;
            lock (clients) pending = clients.ToArray();
            await Task.WhenAll(pending);
        }
    }

    private async Task PumpAsync()
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                var publicationState = state();
                using var next = reader.Store.TakeLatest();
                lock (gate)
                {
                    if (!publicationState.Streaming || publicationState.Session != session)
                    {
                        current?.Dispose();
                        current = null;
                        session = publicationState.Session;
                        Guid.NewGuid().ToByteArray().CopyTo(generation, 0);
                        sequence = 0;
                    }
                    if (publicationState.Streaming && next != null &&
                        Stopwatch.GetElapsedTime(next.DecodedAtTicks) < TimeSpan.FromMilliseconds(500))
                    {
                        var old = current;
                        current = next.Retain();
                        old?.Dispose();
                        sequence++;
                    }
                }
                await Task.Delay(10, stop.Token);
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
        finally { lock (gate) { current?.Dispose(); current = null; } }
    }

    private async Task ServeAsync(NamedPipeServerStream pipe)
    {
        using (pipe)
        {
            try
            {
                var request = new byte[4];
                var header = new byte[HeaderBytes];
                while (!stop.IsCancellationRequested)
                {
                    await pipe.ReadExactlyAsync(request, stop.Token);
                    if (!request.AsSpan().SequenceEqual("CMY1"u8)) break;
                    OwnedNv12Frame? frame;
                    byte[] streamGeneration;
                    ulong frameSequence;
                    lock (gate)
                    {
                        frame = current?.Retain();
                        streamGeneration = (byte[])generation.Clone();
                        frameSequence = sequence;
                    }
                    using (frame)
                    {
                        var live = frame != null && state().Streaming &&
                            ((frame.Width == 1280 && frame.Height == 720) ||
                             (frame.Width == 1920 && frame.Height == 1080)) &&
                            frame.Stride >= frame.Width && frame.Stride <= 4096 &&
                            frame.Pixels.Length <= 4096 * 1080 * 3 / 2 &&
                            Stopwatch.GetElapsedTime(frame.DecodedAtTicks) < TimeSpan.FromMilliseconds(500);
                        WriteHeader(header, live ? frame : null, streamGeneration, frameSequence);
                        await pipe.WriteAsync(header, stop.Token);
                        if (live)
                        {
                            await pipe.WriteAsync(frame!.PixelsMemory, stop.Token);
                            Interlocked.Increment(ref published);
                        }
                        else Interlocked.Increment(ref neutral);
                    }
                }
            }
            catch (Exception error) when (error is IOException or OperationCanceledException or ObjectDisposedException)
            {
                if (!stop.IsCancellationRequested) Interlocked.Increment(ref failures);
            }
        }
    }

    private static void WriteHeader(Span<byte> header, OwnedNv12Frame? frame, byte[] generation, ulong sequence)
    {
        header.Clear();
        "CMYF"u8.CopyTo(header);
        BinaryPrimitives.WriteUInt16LittleEndian(header[4..], 1);
        BinaryPrimitives.WriteUInt16LittleEndian(header[6..], frame == null ? (ushort)0 : (ushort)1);
        if (frame == null) return;
        BinaryPrimitives.WriteUInt32LittleEndian(header[8..], (uint)frame.Width);
        BinaryPrimitives.WriteUInt32LittleEndian(header[12..], (uint)frame.Height);
        BinaryPrimitives.WriteUInt32LittleEndian(header[16..], (uint)frame.Stride);
        BinaryPrimitives.WriteUInt32LittleEndian(header[20..], (uint)frame.Pixels.Length);
        generation.CopyTo(header[24..]);
        BinaryPrimitives.WriteUInt64LittleEndian(header[40..], sequence);
        BinaryPrimitives.WriteInt64LittleEndian(header[48..], frame.Pts100ns);
        var ageMs = (ulong)Math.Max(0, Stopwatch.GetElapsedTime(frame.DecodedAtTicks).TotalMilliseconds);
        BinaryPrimitives.WriteUInt64LittleEndian(header[56..], GetTickCount64() - ageMs);
    }

    private NamedPipeServerStream CreateSecuredPipe()
    {
        var userSid = WindowsIdentity.GetCurrent().User?.Value ?? throw new UnauthorizedAccessException("No user SID");
        var frameServerSid = ((SecurityIdentifier)new NTAccount("NT SERVICE", "FrameServer")
            .Translate(typeof(SecurityIdentifier))).Value;
        var sddl = $"D:P(A;;GA;;;{userSid})(A;;GA;;;SY)(A;;GA;;;{frameServerSid})";
        if (!ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, out var descriptor, out _))
            throw new IOException("Unable to create pipe security descriptor", Marshal.GetLastWin32Error());
        try
        {
            var attributes = new SecurityAttributes { Length = Marshal.SizeOf<SecurityAttributes>(),
                SecurityDescriptor = descriptor };
            // PIPE_REJECT_REMOTE_CLIENTS; the user ACE alone would otherwise allow remote SMB pipe access.
            var openMode = 0x40000003u | (firstInstance ? 0x00080000u : 0u);
            var handle = CreateNamedPipe(@"\\.\pipe\" + PipeName, openMode, 0x8, MaxClients,
                64 * 1024, 4, 0, ref attributes);
            if (handle.IsInvalid) throw new IOException("Unable to create camera pipe", Marshal.GetLastWin32Error());
            firstInstance = false;
            return new NamedPipeServerStream(PipeDirection.InOut, true, false, handle);
        }
        finally { LocalFree(descriptor); }
    }

    public async ValueTask DisposeAsync()
    {
        stop.Cancel();
        if (run != null)
            try { await run; } catch (OperationCanceledException) { }
        reader.Dispose();
        stop.Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr SecurityDescriptor;
        public int InheritHandle;
    }
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string sddl, uint revision,
        out IntPtr descriptor, out uint size);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("kernel32.dll", EntryPoint = "CreateNamedPipeW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafePipeHandle CreateNamedPipe(string name, uint openMode, uint pipeMode,
        uint maxInstances, uint outBufferSize, uint inBufferSize, uint timeout, ref SecurityAttributes attributes);
    [DllImport("kernel32.dll")] private static extern ulong GetTickCount64();
}
