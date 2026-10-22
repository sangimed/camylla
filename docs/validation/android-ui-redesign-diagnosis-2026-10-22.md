# Android UI redesign diagnosis — 2026-10-22

## Finding

The v0.6.0-alpha.6 source redesign was present in the release, but it was not
validated on the user's physical device. The release validation record explicitly
left physical camera, pairing, streaming, and active-stream rotation untested.
On the connected Xiaomi checked for this follow-up, Camylla is not installed for
either Android user, so its prior installed version and visible Activity cannot
be established from the current device state.

There are also confirmed design reasons the release could look little changed on
this device even when the release APK was installed:

- The redesign retained the CAMYLLA header, Simple/Advanced control, three large
  Camera/Connections/Settings buttons, Camylla palette, and standard Android
  buttons. Its largest visual difference was limited to sufficiently wide
  landscape workspaces and the Settings category rows.
- `CameraWorkspaceLayout.choose` selects SPLIT only at 840dp width and above.
  The Xiaomi's 1600×2560 display at 320dpi is 500×800dp in portrait and 800×500dp
  in landscape. Portrait therefore selects COMPACT by width; landscape cannot
  reach SPLIT or STACKED by the implementation's width/height thresholds and
  also selects COMPACT. The two orientations use the same vertically stacked
  preview/control structure.
- The preview SurfaceView is only attached to the active capture service. In an
  unpaired/stopped state, the saved emulator captures show a large empty black
  preview area and “Connect your PC” prompt, not a live camera image. That differs
  sharply from the reference's active camera console and leaves most of the
  screen visually empty.
- The release kept the existing design tokens and Android View conventions. It
  restructured Settings, but did not replace the familiar main navigation and
  button hierarchy with a distinct camera console.

A stale local debug APK is a further plausible delivery failure: the workspace's
debug APK was timestamped before the redesign merge and Gradle defaults to
versionCode 5 / versionName `0.5-simple-pairing` unless release properties are
passed. There is no evidence that this APK was installed on the user's device,
so it is not stated as the confirmed cause.

## Evidence

- `main` at `1c5b5dec5ff6b8ccb8ed0bf4b1a38233aa24892e` contains PR #83's redesign
  merge (`05f78835f0323e2adedbbacc0e1dfafaa1c4c793`). The published alpha6 APK's
  SHA-256 is `df3ea5e2bd7f511179e839a20e67a0b6055d31406cd773d7ea4a214d81017cf3`,
  matching the release manifest and issue record.
- The launcher is `org.camylla.proof/.MainActivity`. Its `onCreate` builds the
  UI in Kotlin and calls `setContentView(root)`. The changed presentation paths
  were `MainActivity.kt`, `CameraControlsPanel.kt`, new
  `CameraWorkspaceLayout.kt`, and new `PreferenceRows.kt`, plus presentation
  tests. No XML layouts or `res/` resources exist in the Android main source,
  so no landscape/qualified XML layout can override these views.
- The saved alpha6 emulator screenshots are
  [`portrait`](evidence/issue-59-emulator-portrait.png) and
  [`landscape`](evidence/issue-59-emulator-landscape.png). They show the
  unpaired state and a blank preview region. They do not prove physical-device
  appearance or active-stream visuals.
- The prior validation record
  [`android-ui-redesign-2026-10-21.md`](android-ui-redesign-2026-10-21.md)
  reports emulator navigation/orientation tests and explicitly marks physical
  camera, pairing, live OBS stream, and active-stream rotation as NOT TESTED.
- The first ADB snapshot on 2026-10-22 found device `d3ec6d70` (`2405CPCFBG`),
  Android 16/API 36, 1600×2560, 320dpi. User 0 was foreground and user 10
  (Security Space) was stopped. `org.camylla.proof` was absent for both users,
  and the launcher was resumed. No historical package/version/activity could
  be recovered from that snapshot.
- The user supplied the five DroidCam references from their local Downloads
  folder and authorized their analysis. They were inspected directly; the
  screenshot-by-screenshot findings are recorded in
  `docs/research/droidcam-android-ui-2026-10-21.md`. The third-party images are
  not copied into this repository or application.
- Initial install attempts returned
  `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`, even though USB
  debugging was enabled (`adb_enabled=1`). After the user confirmed enabling
  Xiaomi's Install via USB permission, an initial alpha.7 candidate installed
  into user 10 with `adb install --user 10 -r`. That candidate's SHA-256 is
  `26f565a0907cfbc04bd8a349730833b5740ed554f60324474952d122205503b9`.
- The final rebuilt alpha.7 APK has SHA-256
  `6643e1ea2840fb67bed0e7458fa99ac4d30522c0bd2c696d1fd3b22e7818b71e`, with
  the same package/version metadata. Updating user 10 with this final artifact
  returned `INSTALL_FAILED_USER_RESTRICTED` again. Pulling the installed APK
  from `pm path --user 10` and hashing it confirmed that the device still has
  the earlier candidate, not the final APK. User 0 remains uninstalled.
- `am get-current-user` still reports user 0. An explicit
  `am start --user 10 -n org.camylla.proof/.MainActivity` returned “Starting”,
  but subsequent process and resumed-Activity checks found no Camylla process
  and MIUI's USB-install settings activity remained foreground. The user had
  explicitly asked that we not switch spaces, so no user-switch command was
  issued. Camera and notification runtime permissions remain ungranted in user
  10. The initial alpha.7 candidate is installed there, but the final APK,
  visible launch, and physical UI are not validated.

## Changes required to prevent recurrence

1. Replace the shared, dashboard-like page header with a dedicated camera
   workspace whose preview and camera controls are the primary composition in
   both orientations. Use actual available bounds so this device does not fall
   into the same stacked mode in portrait and landscape.
2. Make the camera image visible in the ready camera workspace. If preview-only
   camera ownership is required before an explicitly authorized stream, keep it
   separate from peer authorization, encoder, and transport state; never label
   preview or remembered pairing as an active stream.
3. Pin Start/Stop and high-use camera actions to reachable camera-specific
   positions. Keep advanced controls in real, capability-backed settings
   surfaces with truthful applied/pending/unavailable states.
4. Build from the feature commit with explicit version properties; record commit,
   APK SHA, package version/versionCode, target Android user, `pm path`, and
   resolved/running Activity. Install and launch on the actual intended profile
   before claiming physical validation.
5. Compare authorized portrait/landscape device captures against the release
   baseline, the supplied DroidCam images, and the new layout. Exercise settings
   navigation and active rotation; emulator tests alone are not visual acceptance.

## Scope and certainty

The layout/design causes above are supported by source and committed emulator
evidence. The specific historical APK and Android profile that were on the
device when the user saw the unchanged interface are unknown because the current
device has no Camylla installation and no earlier physical install record was
available. Do not attribute the user's observation to an outdated APK without
new evidence.
