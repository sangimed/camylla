# Tap-to-focus feedback — 2026-10-09

Tracking: [#60](https://github.com/sangimed/camylla/issues/60).
Design decisions: [tap-focus feedback](../tap-focus-feedback.md).

## Implemented behavior

The phone preview shows a thin focus target square on a valid single tap. It
pulses during acquisition, shows a green check for success or an amber cross
for failure, and disappears after approximately one or two seconds respectively.
A confirmed focus lock retains a discreet LOCK indicator. No new controls or
settings are introduced.

Feedback requires the latest target revision, its actual AF-trigger frame,
matching returned AF region/mode and a terminal Camera2 autofocus result.
Command acceptance, passive autofocus, obsolete frames and late success after
timeout cannot claim success. A new tap supersedes the old target, including
repeated taps on the same area. Pinch gestures suppress tap and long-press
recognition for the entire multi-pointer gesture. Zoom, preview-transform,
focus-mode, surface/lifecycle and camera-session changes clear obsolete feedback.

The focus target is an Android View over the local preview SurfaceView. It is
not an encoder/rendering overlay and is absent from the camera-state protocol.
Actual camera-to-OBS visual acceptance remains a separate check below.

## Automated verification

- Offline Android build: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` passed on the final implementation.
- JVM reports: 51 tests, zero failures/errors. Six focus-feedback tests cover
  trigger/region/revision correlation, repeated targets, replacement, timeout,
  dismissal and lock/invalidation.
- Xiaomi 2405CPCFBG / Android 16: installed the rebuilt app and instrumentation
  APKs. `FocusTargetViewTest,CameraProductTest`: `OK (8 tests)` on the final APK.
- The new on-device test draws all four feedback states, checks position and
  bounded size, verifies non-color success/failure shapes and accessible text,
  and rejects feedback from an obsolete layout. The existing seven product
  tests cover GPU preview/output transforms, clean output, controls and settings.
- These device tests synthesize rendering input and do not open the sensor.
- `git diff --check` passed. Existing deprecated API warnings remain.

## Physical acceptance

The APK replacement interrupted the previously active session. After the final
tests, MainActivity was opened and no running capture service was reported.
No new camera session was started by the agent. Pairing data was preserved by
the in-place install; persistence is not claimed as a fresh physical test.

Pending direct user checks with a phone-user-started session:

- Tap a real subject: correct square position, acquisition and genuine result.
- Tap two areas quickly, including the same area twice: latest target wins.
- Confirm disappearance, long-press lock/unlock and pinch without accidental focus.
- Check portrait/landscape, mirror/zoom, lens change and Stop without a stale marker.
- Confirm Windows/OBS picture remains clean, with no focus square or result symbols.
- Record unavailable/fixed-focus cases only on actually tested lenses.

No physical PASS is inferred from counters or synthetic tests. Issue #60 remains
open and no roadmap item is marked complete before tested merge and acceptance.

## API references

- [Camera2 AF triggers](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER)
- [Capture start frame numbers](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.CaptureCallback#onCaptureStarted(android.hardware.camera2.CameraCaptureSession,%20android.hardware.camera2.CaptureRequest,%20long,%20long))
