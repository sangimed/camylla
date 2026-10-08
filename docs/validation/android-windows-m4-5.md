# M4.5 camera controls — integrated checkpoint

Issue [#26](https://github.com/sangimed/camylla/issues/26) retains the full
[product scope](../camera-controls-ux.md). The implementation now connects the
camera adapter, mobile preview, persisted settings and authenticated Windows
controls. This is a reviewable implementation checkpoint, not a physical camera
or visual acceptance PASS. Do not close #26 on the automated evidence alone.

## Implemented path

Camera2 owns the selected logical or physical camera and its capture requests.
A single camera texture feeds an EGL renderer with separate phone preview and
MediaCodec output surfaces. Sensor controls use capture-result metadata to
confirm application; software controls complete after a rendered frame.
[ADR 0007](../adr/0007-camera2-controls-and-separate-preview-rendering.md)
explains the change from the earlier CameraX feasibility path.

Capabilities come from the selected lens's Android metadata and supported
720p30/1080p30 stream modes. Metadata is a candidate capability, not proof of a
working device/session combination. Unsupported controls have explicit reasons;
runtime request/session failures and missing result confirmation report errors.
The catalogue includes physical lenses reachable through a logical camera when
Android exposes their characteristics. Optical selection and digital zoom have
separate labels.

Implemented families: autofocus, mapped tap/centre focus, acquisition feedback,
manual distance and lock; torch and API-dependent strength; EV, AE lock and
manual ISO/exposure time bounded to the 30 FPS frame interval; WB presets, lock
and manual red/blue sensor gains; stabilization and anti-flicker; labelled
software brightness/contrast/saturation; independent preview/output quarter-turn
rotation and mirroring; preview orientation lock; preview-only grid, zebras,
edge peaking and histogram. Fixed-focus lenses retain a visible unavailable
reason for manual focus/tap where appropriate.

The phone has a large preview, persistent Start/Stop, quick controls, grouped
advanced controls, pinch/tap gestures with explicit button alternatives, saved
lens/mode/preferred desktop, reset and named presets. Windows builds supported
controls from phone descriptors and displays canonical applied/pending/error
state. Clicking the desktop preview submits a normalized focus target. Target
acceptance is separate from the subsequent AF result.

## Command and reconfiguration contract

Capture still requires the user's Start on the phone. The foreground service is
not sticky; preferences/presets never restore capture authorization, torch or
locks. Stop invalidates the generation, cancels pending controls and recovery,
closes the sensor/renderer/encoder and clears desktop publication state.

`CameraControlState` is integrated into the camera handler. Local and remote
commands share an increasing adapter request ID, an ephemeral phone-authorized
capture binding and camera generation. The TLS control session independently
checks remote peer/session identity and monotonically increasing wire IDs.
Each control has one pending command; newer requests supersede it, at most 32
controls are pending, and camera confirmation expires after three seconds.
Windows waits up to five seconds and sends cancellation if its caller cancels.
Late completions after cancellation, timeout, generation change or Stop cannot
acknowledge another request. Receipt alone does not mean applied.

V3 control extensions are `camera_command`, `camera_focus`, `camera_cancel`,
`camera_capability`, `camera_state`, `camera_result`, `camera_changing` and
`camera_reconfigure`. Records are bounded and session/generation scoped.
Descriptors carry numeric range, step, unit, choices and unavailable reason;
results carry exactly one applied value or error. State events are rate limited.
`camera_changing` immediately clears old desktop controls/frames. Reconfiguration
renews a one-use video ticket on the existing authenticated control channel;
the next video channel needs fresh codec configuration and a keyframe.
A frame epoch also invalidates desktop preview and IPC cached frames.

Focus coordinates inverse the actual displayed rotation/mirror and letterbox
rectangle, then map into sensor crop and zoom. The renderer and coordinate
mapper share the same clockwise quarter-turn convention. Physical stabilization
crop and device-specific lens behavior still need the acceptance checks below.

## Automated evidence

Validated in this workspace on 2026-10-06:

- Android `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest --offline`: successful; 33 JVM tests, including
  cancellation, obsolete peer/session/generation, ordering, timeouts, bounds,
  Stop and focus mapping for all rotation/mirror combinations.
- Windows desktop Debug build: successful, zero warnings/errors.
- Receiver `--camera-controls-self-test`: mutual TLS control acknowledgements,
  unsupported/bounded values, focus targets, cancellation/late results,
  generation changes, mode/video-ticket reconfiguration and Stop passed.
- Receiver `--self-test`: frame ownership/conversion, bounded publication,
  epoch invalidation, IPC access control, slow consumers, shutdown and wire
  lifecycle passed. The test uses an isolated pipe to avoid the running desktop.
- Receiver `--pairing-self-test` and `--rotation-self-test`: passed, including
  one-use trust/binds, revocation, media exclusion and retired identity rejection.

The final `CameraProductTest` run passed all four instrumented tests on Xiaomi
2405CPCFBG after reconnecting and installing the current app/test APKs:
strict camera-wire parsing, capability/persistence checks, real EGL
software brightness and clean encoder output despite preview aids, and all
eight rendered rotation/mirror combinations with mapped focus coordinates.
These use synthetic images and do not open a camera sensor. An idle app launch
also left the bound capture service outside foreground capture (foreground
start count zero). After the user reconnected/unlocked the tablet, inspection
of actual idle screenshots in portrait and landscape confirmed the large
preview placeholder, readable controls and persistent Start/Stop. The original
rotation setting was restored after the check. This is idle-layout observation,
not user-confirmed live framing or capture acceptance.

The current Windows desktop was launched with existing remembered trust and
listeners on 58413/58414. The user then explicitly started capture on the tablet.
The physical checkpoint below records that run and fixes found during it.

## Physical tablet checkpoint, 2026-10-06

Xiaomi 2405CPCFBG, Android 16 / BP2A.250605.031.A3; Windows 11 25H2;
OBS 32.2.2 / WebSocket 5.7.4. The tablet exposes rear camera 0 (2.783 mm)
and front camera 1 (1.93 mm), both with baseline 720p30/1080p30 metadata.
The rear supports variable focus and torch; the front is fixed focus with
manual focus, tap AF, lock and torch unavailable. No S21 Ultra was connected.

The user started capture. The rear Camera2/GL/encoder path produced about
30 encoded FPS. [Recorded stable intervals](evidence/m4-5-tablet-rates-2026-10-06.csv)
exclude transitions and intervals with changing network-error counters:

| Actual decoded size | Five-second intervals | Decoded FPS range | Windows displayed FPS range |
| --- | --- | --- | --- |
| 1280x720 | 64 | 28.56–30.35 | 15.65–22.31 |
| 1920x1080 | 28 | 29.28–30.40 | 8.70–10.23 |

These local observations do not establish camera-to-OBS latency.
Decode errors remained zero. The desktop preview does not present 30 FPS.
Deliberate video-channel reconfiguration increments the diagnostic network-error
counter for EOF; the original run reached four. Control stayed authenticated
with the same session ID across lens/mode changes. A desktop close/reopen also
recovered under the still-active phone authorization, without another Start.

Observed camera-result acknowledgements from real authenticated Windows UI
commands: zoom 2x and back; manual focus requested 3 diopters, actual 3.012;
focus lock/unlock and return to continuous Auto; centre AF target followed by
separate Focused/Not focused state; torch on/off; EV +3 steps; AE lock;
manual exposure with ISO 200 and 10 ms; daylight/Auto/manual WB with red/blue
gains 2 and WB lock; stabilization On and anti-flicker 50 Hz. Software brightness
0.1, contrast 1.2, saturation 0.5, monitoring aids, output 90 degrees and output
mirroring also completed. Rear/front and 720p/1080p changes resumed decoding.
Manual focus requested at zero returned about 0.579 diopters and timed out;
it was correctly not acknowledged as applied. This endpoint remains a device
limitation to characterize, not a successful infinity-focus check.

The mobile UI saved and loaded a temporary named preset during capture.
After explicit Stop and APK replacement, the preset still appeared and was
deleted through the mobile UI. App launch remained stopped. Start/Stop stayed
visible while the Pro panel and preset dialogs were used. The newer APK,
including the unavailable-value display fix below, is installed.

OBS screenshots showed clean live camera output with preview aids enabled.
Screenshot inspection is engineering evidence, not the user's visual acceptance
of framing, physical light release, near/far focus or transformed tap targets.
The final Stop was invoked through the phone's Stop button with torch confirmed
On immediately beforehand. Camera clients were released, receiver controls and
active buffers cleared, counters stopped and OBS became neutral black. Physical
lamp confirmation is still awaiting the user's answer.

### Fixes found and verified

1. An unsupported torch-strength result contained an implausible raw device
   value. Both UIs now display Unavailable with the reason; single tap AF has
   a readable mode label. Android build/33 JVM tests and desktop build pass.
   The corrected desktop labels were inspected on the live session.
2. The existing native IPC consumer rejected a valid frame whose size differed
   from its negotiated format. OBS fixed at 1080p became black after capture
   switched to 720p. The source now resizes validated NV12 luma and paired UV
   into the consumer's stable baseline format using nearest-neighbour sampling.
   Same-size frames preserve stride handling. Tests cover both directions,
   identity, padded rows, colour/chroma quadrants and input/output bounds;
   both CTest suites pass. Direct native IPC probes received live frames in
   fixed 720p and 1080p formats from the real 720p source. The Release DLL was
   registered and subsequent OBS screenshots at fixed 1920x1080 showed live
   720p capture, plus resumed live 1080p capture and neutral Stop output.

The elevated reload helper reported FrameServer stop failures even though
registration succeeded and the observed host PID changed; subsequent OBS
behaviour confirms the resizing path, not a successful helper exit status.
OBS test windows were closed; two headless processes left by OBS shutdown were
terminated. Temporary WebSocket enablement was restored to its disabled state.
No recording or broadcast was started. Camera images and unsanitized logs stay
in local validation artifacts, outside Git.
No screenshots from a locked/asleep device count as UI evidence.

## Remaining acceptance and deferred hardware coverage

The user explicitly declined further S21 testing for this checkpoint. Its
device-specific matrix is deferred and does not block the tablet-based review;
no S21 compatibility PASS is claimed. The S21 references below describe future
coverage, not work required before this checkpoint can be reviewed. User visual
confirmation of the tablet images and physical lamp is still requested.

1. Unlock the tablet and visually confirm the idle layout. The current app/test
   APKs are installed and all four instrumented tests pass. Confirm idle launch
   does not capture.
2. The tablet rear/front and mode paths above have been exercised. On S21 Ultra,
   the user explicitly taps Start. Verify exposed 720p30/1080p30 and delivered
   FPS, large preview, readable quick/advanced controls and reachable Stop in
   portrait, landscape and tablet layouts.
3. On S21 Ultra, exercise exposed optical cameras, manual/auto/tap focus and
   lock/unlock, real torch on/off and available strength. Confirm light releases
   on Stop, switch, app/service failure and camera interruption. On the tablet,
   verify limited/fixed-focus controls give honest unavailable reasons.
4. Compare phone changes and authenticated desktop commands for sensor
   exposure/WB, software adjustments and transforms. Check actual values,
   pending/errors, rapid slider supersession, timeout/cancel and stale responses.
   Verify focus reaches the intended point at zoom and every transform.
5. Extend the tablet OBS checks to the S21 Ultra: require neutral output during
   binding, fresh configuration/keyframe, correct final dimensions/colors and no
   old image. Enable every preview aid and confirm OBS stays free of overlays.
6. Restart app/service, apply/reset/delete presets and change available device
   capabilities: revalidate saved values, retain preferred peer, never restore
   capture authorization or active light/locks. Obtain the user's visual
   confirmation of framing, controls and clean webcam output.

Reboot/unattended capture remains separate issue #34. Thermal behavior and
clean-machine deployment evidence remain their own existing project gates.
