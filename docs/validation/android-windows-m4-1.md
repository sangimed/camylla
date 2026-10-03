# M4.1 Windows live preview validation

**Status:** automated/local Windows checks passed. Physical S21 Ultra to WinUI preview validation has **not** been run and remains the issue #22 acceptance gate.

## Architecture and frame contract

`Camylla.Receiver.Core` owns the existing M3 TLS control/video listeners, session state, wire parser, and the sole Media Foundation H.264 decoder. The CLI and WinUI desktop shell reference that core; the desktop does not start or parse the CLI. The virtual-camera DLL remains uninvolved in networking.

The decoder callback's NV12 pointer is borrowed. `MfDecoder` copies it synchronously into `OwnedNv12Frame` before returning. The owned frame exposes read-only pixels, width, height, stride, NV12 format, sample PTS in 100 ns units, and Windows `Stopwatch` decode ticks. Frame length and dimensions are checked before copying. The owned data is independent of subsequent Media Foundation buffer reuse.

`ReceiverHost.Frames` is a depth-one latest-frame slot. `CreateFrameReader()` makes an independent disposable depth-one slot for another consumer, such as later virtual-camera publication. Publishing exchanges references without waiting for UI work; a superseded pending frame is dropped. Each reader's high-water is at most one. All slots are cleared when the video channel unbinds, its control session retires, or the host stops. Consumers must also inspect receiver state and frame freshness; a frame is not a session authorization token.

The WinUI shell owns one `ReceiverHost` in the user process. It takes at most one newest frame per render turn. NV12 to BGRA conversion runs off the UI thread and no next conversion starts until that one completes. The display returns to a neutral waiting view when the stream state is not `Streaming`, the session changes, or no fresh frame has arrived within two seconds. The window cancellation path stops listeners, retires sessions, drains active handlers, and clears frame slots.

## Local checks completed

- Windows CMake x64 Debug build and CTest: 2/2 tests passed.
- Receiver and WinUI x64 Debug .NET builds: passed with zero warnings/errors.
- Receiver `--self-test`: borrowed and managed NV12 ownership, metadata, depth-one replacement, independent readers, wire framing, and start/shutdown/same-port restart passed.
- Receiver `--decoder-smoke`: Media Foundation decoder create/reset passed.
- Synthetic FFmpeg 1280×720 Annex B decoder-file check: 90/90 access units decoded as NV12.
- Synthetic TLS loopback: control/video authentication, mismatched bind rejection, 93 received access units, 91 decoded frames, one deliberate sequence-gap reset, zero decoder errors, timeout retirement, and fresh session bind passed. This used a generated video file, not the phone.
- The WinUI EXE remained running after launch on the local Windows development machine. UI Automation found the neutral `Waiting for a phone stream` placeholder with no phone connection. A normal main-window close request exited the process cleanly. This does not test loss after a live stream or visible moving preview.

## Physical test still required

On the M3 S21 Ultra and Windows 11 setup:

1. Build native Debug and the desktop project, run `src/windows/desktop/bin/x64/Debug/net10.0-windows10.0.19041.0/win-x64/Camylla.Desktop.exe`.
2. Enter the displayed development pin and secret, and the PC LAN address, into the Android proof app. Start rear 1280×720@30 on the phone.
3. Observe moving frames in the WinUI preview and record at least seven five-second samples of received, decoded, displayed, superseded, queue depth/high-water, decoder errors, and frame dimensions/stride. Compare delivered preview cadence to the requested 30 FPS.
4. Stop capture and verify the neutral waiting view; repeat app open/close at least three times. Run the CLI receiver afterward and verify live decode still works. Check process/socket cleanup and memory trend.

Record observed values and any failures here after a human runs this setup. The synthetic checks above do not establish the phone-to-desktop preview claim.
