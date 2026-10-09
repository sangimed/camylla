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

The list above records the original detailed checklist, rather than claiming
each scenario was individually measured.

## User acceptance and merge

The user explicitly started capture on the tablet, reported the phone preview
and working focus, then requested that the desktop client be launched. The
desktop client was opened and receiver diagnostics changed to Streaming at
1280x720. The user confirmed the visible desktop picture: “Oui, je vois le retour
et ça a l'air d'être bon”, followed by “On valide”, and authorized merging PR #62.

This records user acceptance of the delivered focus interaction and desktop
return on the Xiaomi/Windows configuration. It does not add individual physical
PASS claims for every lock, rapid-tap, transform or unsupported-lens scenario;
no new direct OBS observation was reported in this follow-up. Preview-only
separation and the existing synthetic clean-output tests remain the evidence
for overlay isolation.

The tested implementation is `da92e43651b1bd8f834fb1805522efb9eaa7448c`.
Both [Android and Windows CI checks](https://github.com/sangimed/camylla/actions/runs/37991172177)
passed. [PR #62](https://github.com/sangimed/camylla/pull/62) merged into main at
`484ea635c34742117674db15afc574df4ac0f92d` on 2026-10-09. Issue #60 can close
for the user-accepted enhancement; broader release/hardware checks remain in
their existing validation issues.

## API references

- [Camera2 AF triggers](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER)
- [Capture start frame numbers](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.CaptureCallback#onCaptureStarted(android.hardware.camera2.CameraCaptureSession,%20android.hardware.camera2.CaptureRequest,%20long,%20long))
