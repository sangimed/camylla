# Android camera workspace follow-up validation — 2026-10-22

## Scope and source

This record covers the Android-only follow-up on branch
`feat/59-droidcam-camera-workspace-followup`, based on `main` at
`1c5b5dec5ff6b8ccb8ed0bf4b1a38233aa24892e`. It does not modify Windows code,
camera/encoder/transport/pairing services, or the streaming protocol. Detailed
cause analysis is in
[`android-ui-redesign-diagnosis-2026-10-22.md`](android-ui-redesign-diagnosis-2026-10-22.md).

- Android implementation commit: `4b22a59`.
- Research and validation documentation commit: `148b86c`.
- Draft PR: [#85](https://github.com/sangimed/camylla/pull/85), referencing
  #59 and related to #79.

## Change summary

The alpha6 camera page used a branded header, a dashboard-like Camera /
Connections / Settings row, and the same stacked workspace in both Xiaomi
orientations. The follow-up gives the Camera destination a compact toolbar with
Settings, peer/state, and a one-line truthful capture status; makes the preview
and quick controls the central workspace; moves landscape connection/output
facts and Start/Stop into an edge rail; and keeps Connections and flat Settings
detail pages one action away. The visible stopped state distinguishes “Not
paired · Stopped” from a trusted peer and active capture state. It never treats
remembered trust as streaming. Camera logic and service ownership are unchanged.

The layout policy now selects:

- A portrait stack for phone portrait and tablet portrait.
- A preview plus narrow status/action rail for landscape workspaces at least
  700dp wide and 420dp high.
- A compact stack at short heights, narrow windows, or font scale 1.25 and up.

The five supplied DroidCam screenshots were inspected locally and mapped in
[`docs/research/droidcam-android-ui-2026-10-21.md`](../research/droidcam-android-ui-2026-10-21.md).
No reference assets were copied or committed.

## Build identity

- Build: `:app:assembleDebug` with versionCode `7` and versionName
  `0.6.0-alpha.7-debug`.
- Android test APK: `:app:assembleDebugAndroidTest`.
- App package and launcher: `org.camylla.proof` /
  `org.camylla.proof.MainActivity`.
- Final local debug APK:
  `src/android/app/build/outputs/apk/debug/app-debug.apk`.
- Final local APK SHA-256:
  `6643e1ea2840fb67bed0e7458fa99ac4d30522c0bd2c696d1fd3b22e7818b71e`.
- `aapt dump badging` reports the expected package, version, and launcher.
  The APK signature matches the previously installed debug candidate.

## Verification results

### Host-side Android checks

- Focused JVM tests `CameraWorkspaceLayoutTest` and
  `CameraQuickControlsTest`: **PASS**.
- `:app:assembleDebug`: **PASS**.
- `:app:assembleDebugAndroidTest`: **PASS** (test APK compiled only).
- `:app:lintDebug`: **PASS** (lint reported warnings).
- `git diff --check`: **PASS**.

### Android emulator

The connected test target was restricted with `ANDROID_SERIAL=emulator-5554`;
no connected test was directed to the Xiaomi.

- A `CameraProductTest` run started all 9 tests. The new
  `cameraWorkspaceUsesCompactDestinationToolbarAndTruthfulCaptureAction`
  test passed, confirming Activity creation, toolbar destinations, explicit
  Start/Stop presence, and a visible preview viewport on API 35.
- The same run failed 4 tests: the rotation/navigation test inspected a
  destroyed Activity instance after rotation; this instrumentation test was
  changed to reacquire the resumed Activity, but a post-correction run could
  not install the test APK because the AVD reported
  `INSTALL_FAILED_INSUFFICIENT_STORAGE` (316 MiB free). Therefore the corrected
  rotation/navigation test is **NOT TESTED**.
- Three existing synthetic renderer tests failed on the AVD with Android's
  `ImageReader` buffer format mismatch (`0x2` producer vs `0x1` configured).
  They do not exercise the redesigned UI. The camera pipeline was not modified.
- No emulator screenshot was captured for this follow-up.

### Xiaomi physical device

- Device: Xiaomi `2405CPCFBG`, serial `d3ec6d70`, Android 16/API 36, display
  1600×2560 at 320dpi. The tablet was in landscape when connected; a temporary
  display-rotation lock was used to inspect portrait and then released. The
  original accelerometer-rotation setting was 1 and remains 1.
- After the user enabled Xiaomi's Install via USB gate, updating the exact final
  APK into Security Space succeeded with `adb install --user 10 -r`. The APK
  identity is package `org.camylla.proof`, versionCode 7, versionName
  `0.6.0-alpha.7-debug`, launcher `org.camylla.proof/.MainActivity`; the local
  artifact SHA-256 is listed above. The package path resolves under user 10.
- With the user's explicit authorization, ADB switched to user 10. The
  top-resumed Activity became `u10 org.camylla.proof/.MainActivity`, with a live
  Camylla process (PID 22632). This completes the source → APK → installed
  package → running Activity → visible UI chain. No screenshot was uploaded or
  committed; authorized local captures were deleted after inspection.
- Physical UI: **PASS**. In landscape, the preview is the dominant surface with
  connection/Start controls in a left rail and quick camera controls below. In
  portrait, the preview remains the largest region, camera/video selectors sit
  above, quick controls below, and explicit Start spans the bottom. Both
  orientations show truthful not-paired/stopped/disconnected state. Settings
  navigation and Camera category pages were opened and inspected in both
  orientations; optical camera, advanced controls, and presets are discoverable.
- The device reported “PC: Not paired” / “Not paired · Stopped” and “Network:
  Disconnected”. Camera and notification runtime permissions are not granted in
  user 10. No camera imagery was captured, and Start was not activated because
  it would require granting camera access. Pairing, live capture/Start/Stop,
  hardware controls, active rotation/recovery, and OBS streaming remain
  **NOT TESTED** on this device.
- The physical screenshots above are from the installed `6643e1ea...` build
  before the finish-gate corrections below. Source was then corrected to
  visibly dim disabled Start and remove the parent Settings back bar while in
  category detail. The corrected APK was built with explicit alpha.7 identity;
  its SHA-256 is
  `bf65b542e36243e52522b4c23cfeb19f629a91f2f91ba012d582b7983b0515ed`. The
  update was canceled by MIUI with
  `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; therefore these
  corrections have **not** yet been reinstalled or visually verified on the
  physical device. The device continues running the previous installed build.
- Authorized local screenshots were inspected by the independent reviewers and
  are not in the repository. The shell policy rejected temporary-file cleanup,
  so they remain in the local Windows Temp folder pending cleanup.

## Before / after evidence boundary

The merged alpha6 source and saved emulator captures confirm the prior layout's
dashboard header, identical vertical composition in both Xiaomi orientations,
and blank stopped preview. The current source substantially restructures the
camera destination and orientation policy. The one passing API 35 UI test
confirms the new workspace mounts and exposes its main controls. It does not
prove the physical screen by itself; the Xiaomi screenshots now confirm that
the redesigned Activity is visibly running in both orientations. In portrait,
the stopped viewport displays “Start camera to preview”; in landscape the
unpaired state explains that pairing is needed. DroidCam's supplied active
landscape view still differs because Camylla has no paired PC or live camera
image in this validation session. No pixel-fidelity claim is made.

## Independent review

- UI Finish Gate: **HOLD** after reviewing the physical captures. It confirmed
  the layout in both orientations and found no blocker, but raised MAJOR
  findings for enabled-looking disabled Start, duplicate Settings back
  navigation, and missing active/recovery states. Source corrections for the
  first two were built, but the updated APK could not be installed after MIUI
  canceled the update. Active/recovery and larger-font geometry remain untested.
- Reality Checker: physical launch and navigation passed for the previous
  installed build. It independently confirmed the duplicate camera-detail
  navigation issue. Pairing, camera, OBS, and streaming-rotation recovery remain
  untested. The corrected APK update is not physically verified.
- The corrected emulator rotation/navigation instrumentation has not completed
  due AVD storage. No final visual approval is claimed.

## Acceptance status

**Implementation buildable; pre-correction physical portrait/landscape UI
verified; final visual acceptance remains on HOLD.** The installed app visibly
proved the redesigned composition, but the two MAJOR visual corrections in the
new APK are not yet on-device. Runtime camera permission, pairing, live stream
and OBS checks also remain outstanding. The roadmap was not changed.
