# Camera PR #44 — local retest, 2026-10-09

Tracking: [PR #44](https://github.com/sangimed/camylla/pull/44),
[#26](https://github.com/sangimed/camylla/issues/26) and
[#43](https://github.com/sangimed/camylla/issues/43).
Tested implementation: `ae69c12b16a2d054e9a39b5343c3bf87d2995fa4`.
The PR remains a draft; this automated retest does not complete physical
acceptance or a roadmap milestone.

## Results

- Android offline `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest --rerun-tasks`: PASS. All 67 actionable tasks
  executed; XML reports contain 45 JVM tests, zero failures and zero errors.
  Existing deprecated API and native-library stripping warnings remain.
- Installed both rebuilt debug APKs on the connected Xiaomi 2405CPCFBG /
  Android 16. `CameraProductTest`: PASS, `OK (7 tests)`. This covers real EGL
  circle proportions on portrait preview and landscape output, rotation/mirror
  and focus-coordinate agreement, clean encoder output with preview aids,
  brightness, mobile drawers, late/replaced capabilities, command validation
  and isolated settings. No test opens a camera sensor.
- Windows desktop and receiver Debug builds: PASS, zero warnings/errors.
  Separate outputs `build/camera-tests-desktop/` and
  `build/camera-tests-receiver/` avoid replacing running application binaries.
- Native Debug build and CTest: PASS, both protocol and virtual-camera core
  suites (`2/2`).
- Receiver `--self-test`, `--pairing-self-test`, `--rotation-self-test`,
  `--camera-controls-self-test`, `--decoder-smoke` and `--frame-memory-smoke`:
  PASS. Rejection/error diagnostics in negative scenarios are expected and
  their assertions passed. The pooled-frame smoke processed 300 frames with
  zero active buffers remaining, buffer/queue high-water marks of one and
  2,174,600 managed allocated bytes. This is a bounded functional smoke check,
  not an elapsed-duration release gate.
- `git diff --check`: PASS.
- Existing GitHub CI for the tested implementation: Android and Windows PASS
  ([run](https://github.com/sangimed/camylla/actions/runs/37984089945)).

## Physical acceptance still pending

Before and after the device run, `adb shell dumpsys activity services
org.camylla.proof` reported no running service. The rebuilt APK is installed;
capture remains stopped. Live phone/Windows/OBS proportions, physical focus
and torch, and synchronized local/remote control acceptance remain pending.
No OBS physical PASS is claimed from synthetic rendering tests. See the
[aspect-ratio record](camera-aspect-ratio-2026-10-09.md) and
[mobile UX record](camera-mobile-ux-2026-10-08.md) for the focused user checks.
