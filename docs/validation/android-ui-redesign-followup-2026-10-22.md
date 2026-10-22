# Android camera workspace follow-up validation — 2026-10-22

## Scope and source

This record covers the Android-only follow-up on branch
`feat/59-droidcam-camera-workspace-followup`, based on `main` at
`1c5b5dec5ff6b8ccb8ed0bf4b1a38233aa24892e`. It does not modify Windows code,
camera/encoder/transport/pairing services, or the streaming protocol. Detailed
cause analysis is in
[`android-ui-redesign-diagnosis-2026-10-22.md`](android-ui-redesign-diagnosis-2026-10-22.md).

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
  1600×2560 at 320dpi.
- `am get-current-user` reports user 0. Security Space user 10 exists and is
  running. The user explicitly requested that the agent not switch spaces.
- An initial alpha.7 candidate installed in user 10 after the user enabled
  Xiaomi's Install via USB permission. The final APK (SHA-256 above) update
  failed with `INSTALL_FAILED_USER_RESTRICTED`. Pulling the installed APK from
  `pm path --user 10` confirmed it still matches the earlier candidate
  (`26f565a0907cfbc04bd8a349730833b5740ed554f60324474952d122205503b9`), not
  the final APK. User 0 remains uninstalled.
- `am start --user 10 -n org.camylla.proof/.MainActivity` returned “Starting”,
  but no Camylla process or resumed Activity appeared; MIUI's USB-install
  settings activity remained foreground. No user-switch command was issued.
- Camera and notification runtime permissions are not granted in user 10.
- Physical portrait/landscape UI, settings navigation, pairing, camera,
  Start/Stop, active rotation/recovery, and OBS stream: **NOT TESTED**. The user
  authorized local device screenshots, but no Camylla screenshot was captured
  because the final APK was not running visibly on the phone.

## Before / after evidence boundary

The merged alpha6 source and saved emulator captures confirm the prior layout's
dashboard header, identical vertical composition in both Xiaomi orientations,
and blank stopped preview. The current source substantially restructures the
camera destination and orientation policy. The one passing API 35 UI test
confirms the new workspace mounts and exposes its main controls. It does not
prove a visual comparison on the physical device. No pixel-fidelity or
physical-acceptance claim is made.

## Independent review

- UI Finish Gate: **HOLD**. No source-level blocker was identified, but the
  reviewer marked active/recovery visual evidence and constrained landscape /
  larger-font geometry evidence as MAJOR gaps. Physical UI evidence is absent.
- Reality Checker: APK installation into user 10 passed for the earlier
  candidate; installation/update and visible launch of the final APK are not
  verified. Pairing, camera, OBS, and rotation remain untested on the Xiaomi.
- The corrected emulator rotation/navigation instrumentation has not completed
  due AVD storage. No final visual approval is claimed.

## Acceptance status

**Implementation buildable; physical acceptance incomplete.** The user needs to
allow updating the final APK in Security Space and bring that existing profile
to the foreground for launch and authorized portrait/landscape inspection. No
profile restriction was bypassed, no profile switch was issued, and the
roadmap was not changed.
