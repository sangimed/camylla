# M4.1 Windows live preview validation

**Status:** the issue #22 physical acceptance gate passed on the S21 Ultra / Windows setup on 2026-10-03, after correcting padded 1080p NV12 frame copying. The phone produces and the receiver decodes about 30 FPS; measured desktop presentation is slower (720p: 15.67–21.13 FPS in the seven recorded intervals). This is not a claim of 30 FPS presentation or broad device compatibility. PR #32 was merged on 2026-10-03. Subsequent camera publication validation is recorded in [M4.2](android-windows-m4-2.md).

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

## Physical validation — 2026-10-03

The authorized USB device was Samsung S21 Ultra `SM_G998B`, serial `R5CNC16D4TE`. Windows Ethernet IPv4 was `192.168.1.236/24`; phone Wi-Fi IPv4 was `192.168.1.201/24`. Control/video used TLS/TCP ports 58413/58414 with the existing development identity. The pin and secret were obtained locally and entered through adb; credentials are excluded from committed evidence. Desktop and receiver x64 Debug builds passed with zero warnings/errors. Rear camera capture used the Exynos H.264 encoder; Android initially reported 30.1 encoded FPS at 1280×720.

The user confirmed working 720p preview, then confirmed working 1080p preview after the fix below. Separate color-fidelity and orientation assessments were not explicitly provided, so they are not recorded as separate visual PASS results.

### Failure collected and corrected

The first 1080p attempt received and decoded frames but displayed none; the managed callback rejected each frame, reaching 883 decoder errors. The phone's saved H.264 was retained locally before any restart. An offline reproduction showed 1920×1080, stride 1920, and 3,133,440 borrowed bytes instead of 3,110,400 compact bytes: the NV12 storage height was aligned to 1088. The old exact-length check rejected those valid padded frames.

`CopyFromBorrowed` now validates the stored NV12 height, copies the visible luma rows, and copies chroma from the padded luma-plane offset into compact owned storage. It rejects truncated or inconsistent layouts. A regression test uses distinct visible, padding, and chroma bytes to check the plane offset. The recorded phone stream then decoded 347/347 frames with zero rejections. The rebuilt desktop displayed 1080p, and the user confirmed it worked. Two 1080p intervals measured 9.5 and 10.1 displayed FPS; the user changed back to 720p during sampling, so this is not a seven-interval 1080p performance measurement. The raw camera video remains local and is not committed.

### Seven consecutive 720p intervals after the fix

Rows are increments between adjacent samples; timestamps are UTC. All frames were NV12 1280×720, stride 1280. Memory values are the nearest independent process sample, in MiB (working set / private bytes). `D/N` are cumulative decoder/network errors; `active/HW` are owned NV12 buffers.

| End UTC | Seconds | Received | Decoded | Displayed | Display FPS | Superseded | Queue/HW | D/N | active/HW | Memory MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| 20:36:28 | 5.058 | 151 | 151 | 98 | 19.38 | 54 | 0/1 | 0/1 | 1/3 | 214.77 / 178.19 |
| 20:36:33 | 5.040 | 152 | 152 | 99 | 19.64 | 52 | 1/1 | 0/1 | 2/3 | 215.74 / 179.10 |
| 20:36:38 | 5.017 | 150 | 150 | 106 | 21.13 | 44 | 1/1 | 0/1 | 2/3 | 216.10 / 179.38 |
| 20:36:43 | 5.054 | 152 | 152 | 101 | 19.98 | 51 | 1/1 | 0/1 | 2/3 | 220.60 / 184.32 |
| 20:36:48 | 5.038 | 151 | 151 | 79 | 15.68 | 72 | 1/1 | 0/1 | 2/3 | 221.56 / 185.25 |
| 20:36:53 | 5.040 | 151 | 151 | 79 | 15.67 | 72 | 1/1 | 0/1 | 2/3 | 220.32 / 184.66 |
| 20:36:58 | 5.020 | 150 | 150 | 83 | 16.53 | 67 | 1/1 | 0/1 | 2/3 | 220.61 / 185.02 |

Received and decoded cadence was about 30 FPS. Slower rendering superseded 44–72 pending frames per interval without exceeding queue high-water one or NV12 buffer high-water three. The existing network error count of one followed the earlier mode/session change; it did not increase during these seven intervals. Decoder errors stayed zero throughout the corrected physical run.

Desktop PID 38124 was sampled for about 15.5 minutes. Peak working set was 244,117,504 bytes (232.81 MiB); peak private bytes were 206,884,864 (197.30 MiB). Memory increased during warm-up and mixed-resolution use. During 20:47:03–20:48:58 UTC, private bytes ranged from 200,142,848 to 202,723,328, with a first-to-last increase of 749,568 bytes. After Stop, the final pre-close sample was 230,977,536 working-set bytes and 190,304,256 private bytes. This short run shows bounded frame storage and a late memory plateau, not proof against every long-duration leak.

### Recovery, Stop, and desktop lifecycle

- The user performed the instructed 8–10-second Wi-Fi interruption and confirmed both the waiting view during loss and automatic return of moving frames after Wi-Fi ON, without Start/Stop. The CSV recorded `Recovering` at 20:49:02 UTC with queue and active buffers zero, then `Streaming` at 20:49:07 and continued frame increments. Network errors rose from one to three during disruption/reconnection; decoder errors remained zero. The outage duration itself was instructed, not instrumented precisely.
- The user then performed Android Stop and confirmed the waiting view. At 20:49:58 UTC the CSV recorded `Disconnected`, received 25,062, decoded 25,059, displayed 14,368, superseded 10,688, queue zero and active NV12 buffers zero. Counters remained fixed. Network errors became four; the CLI reproduction below shows that a normal phone Stop is currently counted as video EOF. This diagnostic classification does not prevent cleanup.
- Three normal desktop close/reopen cycles succeeded, with PIDs 37436, 33832, and 11200. Each reopen produced `Disconnected` with zero counters and exactly two listening ports. Each close exited within the ten-second bound and left no receiver listeners. The final desktop process was closed before the CLI run.

### Physical CLI regression and current automated checks

The CLI authenticated the same phone after desktop closure. Seven streaming samples progressed from received/decoded 86/85 to 986/985, increasing by 150/150 every five seconds, at NV12 1280×720/stride 1280. Decoder and network errors remained zero while streaming; queue depth/high-water stayed 1/1. Android Stop produced `Disconnected`, received/decoded 1,402/1,401, queue zero, and one network EOF error. Ctrl+C then ended the CLI; process and receiver listeners were absent afterward. The PowerShell capture pipeline returned exit code 1 on Ctrl+C, so this is observed process/socket cleanup rather than a claim of exit code zero.

After the copy fix, receiver self-tests (including the padded-plane regression) and decoder create/reset smoke passed. Synthetic TLS regression, with its receiver explicitly listening on separate ports 58513/58514, received 93 AUs and decoded 91 frames with zero decoder errors, one deliberate sequence-gap reset, mismatch rejection, timeout retirement, and a fresh authenticated bind. The first synthetic client invocation lacked a listening test receiver and failed with connection refused; no physical stream or physical log was restarted or discarded as a result.

### Issue #22 acceptance assessment

| Criterion | Result and evidence |
| --- | --- |
| Diagnostic CLI and existing M3 transport/decode checks still run | PASS: physical CLI decode plus self-tests, decoder smoke, and synthetic TLS regression after the fix. |
| Live S21 frames through the existing transport and MF decoder | PASS: user-confirmed moving 720p/1080p desktop preview; shared core retains the sole native decoder path. |
| Newest frame with bounded storage and superseded-frame drops | PASS: seven measured intervals, depth/HW 0–1/1, buffer HW three, superseded counts increasing, zero live buffers after Stop. Delivered cadence is below 30 FPS. |
| Window/session shutdown releases resources | PASS: Stop clears frames; three closes exit and release both listener ports; CLI obtains those ports afterward. |
| Receiver state/frame interfaces usable by later slices | PASS: shared `ReceiverHost`, owned leases, metadata, and independent depth-one `FrameReader` consumers; existing independent-reader lifetime tests pass. |

All five acceptance criteria are satisfied on this setup. Publication, production pairing, exhaustive color fidelity, long-duration soak testing, and 30 FPS desktop presentation remain outside the demonstrated claim. No merge was performed.

Committed evidence is in [evidence/m4-1-2026-10-03](evidence/m4-1-2026-10-03): complete corrected desktop CSV, seven interval calculations, memory observations, failed 1080p CSV, offline corrected decoder result, reopen results, physical CLI log, and synthetic TLS client result. Original logs and raw phone H.264 are also retained locally in `D:\Repositories\camylla\validation-artifacts\m4-1`. Each desktop CSV was copied before the next app launch could truncate it.
