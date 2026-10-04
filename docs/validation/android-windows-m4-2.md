# M4.2 S21 Ultra / Windows virtual-camera validation

**Status:** all five issue #23 acceptance criteria and the physical gate passed on the tested S21 Ultra / Windows / OBS setup on 2026-10-04. OBS 720p/1080p live preview, desktop 1080p preview, Wi-Fi loss/recovery, Stop, live producer exit/restart, consumer reopen, repeated normal desktop close/reopen, and physical CLI decode are confirmed as described below. PR #33 remains unmerged.

## Setup

- Windows 11 25H2, build 26200.9168; Samsung S21 Ultra SM_G998B, Android 15 / API 35, adb serial R5CNC16D4TE.
- Desktop Ethernet 192.168.1.236/24; phone Wi-Fi 192.168.1.201/24. TLS control/video ports 58413/58414. Development pin/secret were obtained locally and entered using adb; they are excluded from committed evidence.
- OBS 32.2.2 x64, obs-websocket 5.7.4, dedicated scene collection/profile `Camylla M4.2 validation`. The registered camera is `Camylla POC (Windows Virtual Camera)`.
- OBS capture device logs confirm NV12 1280x720 and 1920x1080 at 30 FPS (333333 units), with capture buffering disabled. IPC responses per second are measured below; they are not a measurement of unique frames displayed by OBS or end-to-end latency.
- Native source x64 Release, desktop and receiver x64 Debug. The worktree incorporates merged PR #32 and main through merge commit f28464e.
- Actual FrameServer PID 11380 runs as LocalService and loads the M4.2 Release source DLL. Its SHA-256 is C815477C7DD59F3A2A2316D9C546971F7E1DEEF8541AB1B54DF8C1B7ACFE53F0. Token group inspection confirms `NT SERVICE\FrameServer`, SID S-1-5-80-3915894004-2104103821-3047269622-1811662266-774708259, attributes 14 (enabled, enabled-by-default, mandatory).

## Failures retained and corrected

1. FrameServer initially retained an M1 DLL from the main worktree. Module path/hash evidence was captured before releasing the test source and reloading the host.
2. With the M4.2 DLL loaded, the phone decoded correctly in the desktop but OBS was black and IPC errors increased while live responses stayed zero. A same-user native consumer could receive frames. The source's producer check depended on opening the desktop process token from the service context. The exact failing API was not isolated: two service impersonation probes failed to duplicate the service token, and that failure is not attributed to the source itself. The corrected check uses the connected pipe owner and server session; the publisher explicitly assigns its user SID as owner. This removes cross-process TOKEN_QUERY dependency while keeping the active-console-user boundary, restricted DACL, and remote rejection. Physical FrameServer publication then worked. Administrator/SYSTEM ownership assignment remains within the privileged machine trust boundary.
3. The first desktop close released network listeners but left its process blocked. Synchronous UI shutdown waited on `DisposeAsync`, whose continuation had captured that blocked UI context. `ConfigureAwait(false)` fixes that wait. A test with a connected client and a UI context that does not process callbacks now passes, as do three physical normal close/reopen cycles. The failed process/CSV were saved before terminating the blocked process for deployment.
4. A Release relink while FrameServer held the DLL failed with LNK1104; a later receiver rebuild while the physical CLI was running failed because that executable assembly was locked. Those failures did not discard physical evidence. The host was stopped for controlled deployment, and the CLI was stopped normally before the successful build.
5. The background Wi-Fi watcher collected the loss row, then failed when its Windows PowerShell process reached the newer .NET API used by the OBS RPC helper. No automatic loss PNG was obtained. The user confirmed black output and automatic recovery, and the full CSV plus recovery capture were retained. Subsequent screenshot helpers ran under PowerShell 7.

## Seven samples per mode

Each dataset has seven approximately five-second samples and therefore six adjacent rate intervals. The first sample may include stream startup. Rates below use adjacent deltas, not accumulated startup averages.

| Observation | 720p | 1080p |
| --- | --- | --- |
| UTC samples | 14:45:10.315–14:45:40.503 | 15:01:34.486–15:02:04.780 |
| Receive/decode FPS | 29.88–30.10 | 29.91–30.07 |
| Complete live IPC responses/s | 29.90–30.08 | 29.91–30.07 |
| Desktop displayed FPS | 18.27–19.58 | 9.01–9.59 |
| Preview queue depth/high-water | <=1 / 1 | <=1 / 1 |
| IPC queue depth/high-water | <=1 / 1 | <=1 / 1 |
| IPC current frame / active clients | 1 / 1 | 1 / 1 |
| Owned NV12 active buffers / high-water during samples | 2–3 / 3 | 2 / 3–4 |
| Decoder/network errors during samples | 0 / 0 | 0 / 0 |
| IPC error counter during samples | 0, unchanged | 1, unchanged |
| Desktop private memory MiB | 134.90–136.94 | 179.71–184.53 |
| OBS private memory MiB | 205.76–206.09 | 229.44–229.88 |
| Maximum desktop / OBS working set MiB | 182.18 / 262.13 | 220.83 / 242.41 |

The 1080p IPC error counter was already one before capture, after the capture-device mode changed; it did not increase during streaming. It is not reported as zero. Across the 720p run, owned-buffer high-water later reached four. These short observations demonstrate bounded behavior during this run, not a long-duration memory soak. The desktop preview remains slower than decode and IPC publication.

## Physical observations and visual confirmations

- **OBS 720p:** user confirmed the moving preview (`previw obs ok`). A real 1280x720 source screenshot was inspected and retained locally.
- **OBS 1080p:** user confirmed the moving preview (`1080p OBS OK`). A real 1920x1080 source screenshot was inspected and retained locally.
- **Desktop 1080p:** user confirmed the moving desktop preview (`bureau ok`). A separate desktop 720p visual confirmation was not recorded in this M4.2 run; the earlier M4.1 report contains its physical preview result. The M4.2 720p CSV records presentation, without treating that counter as user visual confirmation.
- **Wi-Fi OFF/ON:** user performed the requested approximately 8–10 second interruption and confirmed black output followed by automatic recovery (`noir puis recup ok`). The exact toggle duration was not independently timed. At 14:48:46.264 UTC, `Recovering` had zero active buffers, no current IPC frame, and rising neutral responses. By 14:48:51.300, `Streaming` resumed. Decoder/IPC errors remained zero; network errors rose from zero to two during interruption/recovery.
- **Android Stop:** user confirmed OBS black and desktop waiting (`stop ok`). At 14:54:43.616 UTC the receiver was `Disconnected`, received/decoded 16876/16874, preview and IPC queues/current frame zero, active buffers zero, decoder/IPC errors zero. Network errors became three: normal video EOF is counted as a network error, as in M4.1. Counters stopped advancing except neutral responses.
- **Desktop close/reopen:** corrected processes 24072, 10676, and 20800 exited normally; reopened PIDs 10676, 20800, and 22948 returned to waiting with zero active buffers, one IPC client, and zero IPC errors. Both listener ports were absent after every close. OBS source captures were black while the desktop was absent. The source reopened when its mode changed to 1080p and later displayed the confirmed phone stream.
- **Live producer exit:** after another confirmed desktop 1080p preview, PID 9768 closed normally in 113.7 ms at 15:13:27.761 UTC and released both ports. A source screenshot requested after an additional 800 ms was black. This is a point observation, not a measured maximum transition latency. User confirmed black OBS output (`oui obs est noir`).

- **Live restart and explicit consumer reopen:** desktop PID 5340 reopened; Android capture, which had not been stopped, automatically reauthenticated in 1080p. At 15:17:20.393 UTC, received/decoded were 115/113 with zero network/decoder/IPC errors. The OBS camera input was then removed and recreated with its saved 1080p settings, reopening the consumer independently of the desktop. The live source screenshot was inspected locally. IPC errors became one during this deliberate connection teardown, then stayed fixed while publication resumed. User confirmed animated recovery and performed Stop (`récupération OK, arrêté`). Final disconnected observations show received/decoded 14247/14245, zero queue/current frame and active buffers, zero decoder errors, one network EOF error, and one retained IPC disconnect error. This is not presented as an error-free consumer teardown.
## Physical CLI regression

After the desktop closed, the already-running Android capture automatically authenticated and bound to the CLI in 1080p. Seven first streaming reports progressed from received/decoded 116/114 to 1016/1014, approximately 30 decoded FPS, zero decoder/network errors, NV12 1920x1080/stride 1920, queue depth/high-water 1/1. There were 24 streaming reports in the retained log.

User Stop (`arreté`) produced `Disconnected`, received/decoded 3619/3617, queue zero and one normal video EOF network error. A helper attached only to the CLI console (CLI PID 8596 plus helper PID 20620), sent Ctrl+C, and detached. The CLI process and both listeners were absent afterward. No forced CLI termination or exit-code-zero claim is made. Credentials are removed from the committed log.

## Automated checks

- Native Debug and Release source builds; CTest 2/2, including NV12 modes, bounds, padded stride, version/reserved fields, freshness and 30 FPS timestamp arithmetic.
- Receiver self-test: owned-frame/stride conversion, real local 720p and padded 1080p transfer, freshness expiry, Stop, consumer reopen and publisher restart.
- Anonymous Windows token attempting a real pipe open is rejected with `ERROR_ACCESS_DENIED (5)`. This is a measured different-principal rejection, not a test of a second interactive account or RDP session.
- A client that never drains its live body leaves preview/publisher queues at depth one and active owned buffers bounded at four above the pretest baseline; another reader still obtains valid live bytes. Malformed request closes that connection. Cancellation releases the current frame and completes within the three-second test bound despite the stalled writer.
- UI-context shutdown regression with a connected client; receiver listener lifecycle and wire self-tests; decoder create/reset smoke; updated C# publisher to native consumer cross-process live smoke; final desktop build with zero warnings/errors.

## Issue #23 assessment

| Acceptance criterion | Evidence / assessment |
| --- | --- |
| Unauthorized/malformed/oversized IPC rejected; slow peers bounded | PASS for the tested anonymous principal, malformed request/header and size tests, stale producer tests, and stalled-consumer test. Additional interactive accounts and session switching are untested. |
| One MF decoder for preview and camera; no networking in DLL | PASS: both readers retain the owned decoded-frame seam; receive/auth/decode stay in the receiver core. Physical simultaneous preview and OBS confirmed at 1080p. |
| 720p30 / supported 1080p30 valid NV12 layout and consumer cadence | PASS on this setup: OBS confirms NV12 modes and 30 FPS negotiation; approximately 30 complete IPC responses/s, byte/stride and source timestamp tests, and user live-preview confirmation in both OBS modes. This does not claim 30 unique OBS frames/s or 30 FPS desktop presentation. |
| Disconnect/exit/recovery neutral without stale final image | PASS: Wi-Fi and Stop visually confirmed; live desktop exit black screenshot and user confirmation, then automatically recovered live output after restart. |
| Consumer reopen and desktop restart do not wedge; lifetime/freshness tested | PASS: three normal close/reopen cycles, automatic live producer restart, explicit OBS camera input recreation and user-confirmed resumed 1080p. Lifetime/freshness and blocked-UI shutdown tests pass. |

All five criteria and the physical gate pass on this setup. This does not claim compatibility with untested consuming apps, other users/RDP, a memory soak, 30 FPS desktop presentation, or a measured end-to-end latency bound. No PR merge is performed.

## Evidence

Committed data is in [evidence/m4-2-2026-10-04](evidence/m4-2-2026-10-04). Original logs, live phone screenshots, local UI dumps, and failed diagnostic helpers remain in `D:\Repositories\camylla\validation-artifacts\m4-2`. Live camera images and development credentials are not committed. CSVs were archived before subsequent desktop launches could truncate them.
