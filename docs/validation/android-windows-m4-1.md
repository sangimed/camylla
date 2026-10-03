# M4.1 Windows live preview validation

**Status:** automated/local Windows checks passed. Physical S21 Ultra to WinUI preview validation has **not** been run and remains the issue #22 acceptance gate.

## Architecture and frame contract

`Camylla.Receiver.Core` owns the existing M3 TLS control/video listeners, session state, wire parser, and the sole Media Foundation H.264 decoder. The CLI and WinUI desktop shell reference that core; the desktop does not start or parse the CLI. The virtual-camera DLL remains uninvolved in networking.

The decoder callback's NV12 pointer is borrowed. `MfDecoder` copies it synchronously into an `ArrayPool<byte>` buffer before returning. `OwnedNv12Frame` is a disposable, read-only lease exposing width, height, stride, NV12 format, sample PTS in 100 ns units, and Windows `Stopwatch` decode ticks. Frame length and dimensions are checked before copying. The owned data is independent of subsequent Media Foundation buffer reuse. A caller must dispose its lease after consuming it; `Retain()` creates a separate lease when another consumer needs the same bytes. The pool receives the buffer only after the last lease ends. There is no finalizer that could return an array while a span borrowed from a live lease is still in use; deterministic disposal is required.

`ReceiverHost.Frames` is a depth-one latest-frame slot. `CreateFrameReader()` makes an independent disposable depth-one slot for another consumer, such as later virtual-camera publication. Each slot retains its own lease. Publishing exchanges leases without waiting for UI work; a superseded pending frame is disposed. `TakeLatest()` transfers that slot's lease to its consumer, which must dispose it. Each reader's high-water is at most one pending frame; one additional frame can be in use by each consumer. All slots are cleared when the video channel unbinds, its control session retires, or the host stops. A retired session cannot publish after its slot is cleared. Consumers must also inspect receiver state and frame freshness; a frame is not a session authorization token.

The WinUI shell owns one `ReceiverHost` in the user process. It takes at most one newest frame per render turn. NV12 to BGRA conversion runs off the UI thread into a rented BGRA buffer; no next conversion starts until that one completes. The UI copies that result into a reusable `WriteableBitmap` pixel buffer and returns the BGRA rental. It does not allocate a BGRA array, DataWriter buffer, or SoftwareBitmap per frame. It rechecks session identity, Streaming state, and decoded-frame age after the background conversion before presenting; a stale result is discarded. The display returns to a neutral waiting view when the stream state is not `Streaming`, the session changes, or no fresh frame has arrived within two seconds. The window cancellation path stops listeners, retires sessions, drains active handlers, and clears frame slots.

The preview converter assumes limited-range BT.709 for HD (including 720p and 1080p) and limited-range BT.601 for SD. The native decoder callback currently supplies no output color matrix/range metadata, so this is an explicit size-based default rather than a verified property of every phone stream. Physical validation must judge colors on the S21 Ultra, and a later format-metadata change is needed if streams with different matrix/range are supported.

At 1280×720, the previous code allocated at least 1,382,400 managed NV12 bytes plus 3,686,400 managed BGRA bytes per decoded/displayed frame: 5,068,800 bytes per frame, about 152 MB/s at 30 FPS, before DataWriter and SoftwareBitmap copies. This is code-path accounting, not an old-process measurement. The network ingress also allocated one variable-length H.264 payload array per access unit and a joined array for a keyframe after configuration; those are now rented and returned with explicit valid lengths. The small codec configuration copy is retained only until the next reset/configuration. The new synthetic 300-frame/10-second workload allocated 2,174,600 managed bytes total (about 7.2 KB/frame), returned all active NV12 leases, and ended at 18,087,936 process private bytes. This workload exercises frame handoff and conversion, not TLS ingress or the WinUI pixel buffer; physical desktop memory and cadence still need measurement. Pool inventory and a consumer that holds leases indefinitely can increase resident memory; consumers must promptly dispose taken frames.

## Local checks completed

- Windows CMake x64 Debug build and CTest: 2/2 tests passed.
- Receiver and WinUI x64 Debug .NET builds: passed with zero warnings/errors.
- Receiver `--self-test`: borrowed and managed NV12 ownership, lease retention/release, depth-one replacement, independent readers, stride-aware BT.601 SD and BT.709 HD NV12→BGRA vectors, wire framing, and start/shutdown/same-port restart passed.
- Receiver `--frame-memory-smoke`: 300 synthetic 720p frames paced at 30 FPS in 10.00 seconds; 2,174,600 managed bytes allocated, active NV12 buffers zero at completion, buffer and queue high-water one.
- Receiver `--decoder-smoke`: Media Foundation decoder create/reset passed.
- Synthetic FFmpeg 1280×720 Annex B decoder-file check: 90/90 access units decoded as NV12.
- Synthetic TLS loopback was repeated after the encoded-payload pooling change: control/video authentication, mismatched bind rejection, 93 received access units, 91 decoded frames, one deliberate sequence-gap reset, zero decoder errors, timeout retirement, and fresh session bind passed. This used a generated video file, not the phone.
- The WinUI EXE remained running after launch on the local Windows development machine. UI Automation found the neutral `Waiting for a phone stream` placeholder with no phone connection. A normal main-window close request exited the process cleanly. This does not test loss after a live stream or visible moving preview.

## Diagnostic sampling

The desktop writes one CSV row about every five seconds to `%LOCALAPPDATA%\Camylla\m4-1-preview-diagnostics.csv`. It truncates on app launch and stops writing at 1 MB. Rows include UTC time, state, received/decoded/displayed/superseded counts, queue depth/high-water, decoder/network errors, width/height/stride, and live NV12 buffer count/high-water. The development secret is shown only in the temporary setup UI; it is not written to this file or desktop debug output.

During a physical run, collect that CSV and sample `Get-Process Camylla.Desktop | Select-Object WorkingSet64,PrivateMemorySize64` at matching intervals. Compare successive displayed counts for delivered cadence and retain the file with the physical result record.

## Physical test still required

On the M3 S21 Ultra and Windows 11 setup:

1. Build native Debug and the desktop project, run `src/windows/desktop/bin/x64/Debug/net10.0-windows10.0.19041.0/win-x64/Camylla.Desktop.exe`.
2. Enter the displayed development pin and secret, and the PC LAN address, into the Android proof app. Start rear 1280×720@30 on the phone.
3. Observe moving frames in the WinUI preview and record at least seven five-second samples of received, decoded, displayed, superseded, queue depth/high-water, decoder errors, and frame dimensions/stride. Compare delivered preview cadence to the requested 30 FPS.
4. Stop capture and verify the neutral waiting view; repeat app open/close at least three times. Run the CLI receiver afterward and verify live decode still works. Check process/socket cleanup and memory trend.

Record observed values and any failures here after a human runs this setup. The synthetic checks above do not establish the phone-to-desktop preview claim.
