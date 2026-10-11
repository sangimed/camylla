# Android UI redesign validation — 2026-10-09, updated 2026-10-11

Scope: focused validation for [#59](https://github.com/sangimed/camylla/issues/59).

## Automated results

- Clean Android debug build, JVM unit tests, and `lintDebug`: passed.
- Xiaomi 2405CPCFBG, Android 16: the redesign navigation instrumentation test passed. It exercises Simple/Pro, Pro controls, Connections navigation, return to Camera, and visible Start/Stop actions.
- The complete connected instrumentation suite ran 18 tests: 8 passed and 10 failed. Eight `PairingHardwareTest` cases require the Windows pairing test receiver, a fresh invitation placed at `files/pairing-invitation-test`, and pre-existing paired trust; that opt-in fixture was not prepared for this UI run. The scanner content test also expected the previous title; its assertion now matches “Connect a PC.”
- Re-running the scanner test was blocked because Android returned `INSTALL_FAILED_USER_RESTRICTED` for both Gradle installation and a direct `adb install -r`, despite the user authorizing installation in chat. The updated test compiles, but its rerun is unverified.
- The installed application launched successfully during the earlier focused UI run.

## Still required

Streaming/recovery and user visual acceptance remain outstanding. The pairing protocol suite should be run separately with its Windows receiver and prepared invitation, following [M4.4 test setup](android-windows-m4-4.md#reproduce-the-opt-in-protocol-hardware-test).

## Device follow-up — 2026-10-11

Installed and launched the updated debug APK on the connected Xiaomi 2405CPCFBG
tablet (Android 16, 1600×2560 portrait / 2560×1600 landscape). Inspection found
that first-run Camera lacked its promised pairing action and showed Start and Stop
at once. The Camera screen now has a centered **Connect your PC** card when no PC
is paired, and shows only Start while idle or Stop during capture. The navigation
instrumentation test was updated to assert these states.

Screenshots show the idle first-run UI in portrait, landscape, and 1.3× font scale:
[portrait](evidence/android-ui-redesign-2026-10-10/portrait-verified-2.png),
[landscape](evidence/android-ui-redesign-2026-10-10/landscape-final.png),
[large text](evidence/android-ui-redesign-2026-10-10/large-text-final.png).
The portrait and landscape layouts reflow without overlap; the enlarged text
remains legible. Build, Android test APK compilation, and JVM tests pass after the
follow-up change. The updated instrumentation test could not be run because Android
again returned `INSTALL_FAILED_USER_RESTRICTED` while installing the test APK.

The screenshot pass used the tablet's wide layout in both orientations; no separate
phone-sized device was available. The captures are idle UI evidence only: no paired
Windows PC or OBS stream was available, so streaming/recovery and user visual
acceptance remain pending.
