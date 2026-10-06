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
start count zero). The tablet remains asleep/keyguard locked, preventing visual
UI acceptance and a user-initiated capture check.
No screenshots from a locked/asleep device count as UI evidence.

## Physical and visual acceptance still required

1. Unlock the tablet and visually confirm the idle layout. The current app/test
   APKs are installed and all four instrumented tests pass. Confirm idle launch
   does not capture.
2. Launch the current Windows desktop build. On the phone, the user explicitly
   taps Start. Verify rear/front 720p30 and 1080p30 when exposed, actual delivered
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
5. Switch lens/mode during a live OBS consumer: require neutral output during
   binding, fresh configuration/keyframe, correct final dimensions/colors and no
   old image. Enable every preview aid and confirm OBS stays free of overlays.
6. Restart app/service, apply/reset/delete presets and change available device
   capabilities: revalidate saved values, retain preferred peer, never restore
   capture authorization or active light/locks. Obtain the user's visual
   confirmation of framing, controls and clean webcam output.

Reboot/unattended capture remains separate issue #34. Thermal/endurance and
clean-machine deployment evidence remain their own existing project gates.
