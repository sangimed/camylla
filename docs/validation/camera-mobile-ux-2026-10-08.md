# #26 mobile preview and quick controls — 2026-10-08

Implementation checkpoint for [#26](https://github.com/sangimed/camylla/issues/26).
This change is not merged and does not complete a roadmap milestone or the
physical camera acceptance matrix. Existing local ROADMAP.md edits were preserved.

## Findings and implemented changes

The existing Camera2/EGL camera adapter, control acknowledgements, preferences,
preview transforms and separate encoder output were retained. Inspection found:

- Pairing, diagnostics and settings occupied the permanent camera surface.
- Quick autofocus/lock/light buttons did not reflect confirmed applied state.
- The mobile mode spinner retained its selection after Windows changed the mode.
- Binding advertised a generation before its capabilities arrived, but the
  mobile panel rebuilt only when that generation changed.
- Windows rebuilt descriptors only when their count changed; an outstanding
  result could also update the UI after a generation change.
- Android 35 system bars overlapped the camera status/actions.

The mobile preview now receives the remaining portrait space and at least 60%
of landscape width (the controls column is capped at 320 dp). Camera/mode,
Auto/Lock/torch and digital zoom/centre AF form the compact quick controls.
The zoom button displays the confirmed ratio and opens its bounded slider on
demand. Advanced, Setup and Diagnostics share one independently scrolling drawer,
limited to 22% of display height; opening it closes the zoom adjustment and vice
versa. Existing advanced groups and presets remain available. Start/Stop remain
outside these scrolling panels. Insets protect status/navigation bars and cutouts.

Camera and mode selection use the actual catalogue and per-lens supported modes.
Idle selection saves preferences without opening a camera. Active labels follow
Android's current camera/mode, including Windows changes, and show mode binding
as Applying. Focus/torch/zoom labels derive from capture-confirmed applied values;
pending commands retain those values with Applying, rather than optimistic state.
Auto prefers continuous video AF, then continuous picture AF, and never claims
manual/fixed focus is continuous autofocus. Missing results disable actions;
unavailable controls display their reasons, including a lens with no zoom range.
Before Start, controls say Start first rather than claiming unsupported hardware.

The panel now compares descriptors as well as generation. Delayed sliders and
open choice dialogs cannot apply to a different current camera generation.
Windows observes descriptor replacement and ignores UI completions from replaced
editors/generations. Action errors remain readable for five seconds, and current
capture-control rejections appear beside connection status. Focus/zoom feedback
is also displayed over the phone preview using an Android view; this view is
outside the encoder path. Existing grid/zebras/peaking/histogram separation remains.

## Verification

- Android Studio JBR / SDK 35, offline Gradle:
  `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` passed.
  All 41 JVM tests passed, including four new quick-control tests for continuous
  AF selection, applied/pending/remote updates, unsupported controls and zoom.
  Existing Android deprecation warnings remain.
- Installed the debug app and test APKs on Xiaomi 2405CPCFBG / Android 16.
  `CameraProductTest` passed all six instrumented tests. The two new checks
  exercise descriptors arriving after binding, applied torch updates, descriptor
  rejection, mutually exclusive drawers and visible Start/Stop while Advanced is
  open. Existing real EGL tests continue to verify clean encoder output with
  preview aids and rotated/mirrored focus mapping. These tests never open a sensor.
- Windows desktop Debug x64 build passed with zero warnings/errors using
  `-p:OutDir=D:/Repositories/camylla/build/camera-ux-desktop/ --no-restore`.
  The initial normal output build failed to copy a DLL locked by the existing
  running desktop; the separate output avoids interrupting that application.
- Receiver `--camera-controls-self-test` passed: authenticated applied/rejected
  commands, bounds, unavailable reasons, focus, generation cancellation, late
  results and Stop. No new reconnection/diagnostic/endurance campaign was run.
- Inspected idle tablet portrait/landscape and 360×640 dp phone-sized
  portrait/landscape screenshots using a temporary 720×1280 display override
  at 320 dpi. Preview, quick-control labels and capture actions are visible.
  These phone-sized checks use the tablet, not a second physical phone.
  Original display size and rotation settings were restored (auto rotation off,
  user rotation 1). Local screenshots/XML remain under ignored build/.
- Post-install idle service inspection reports `startForegroundCount=0`.
  Capture was stopped for APK installation and was never restarted by the agent.
  The new APK is installed. The running desktop still uses its previous binary;
  the newly built executable is in build/camera-ux-desktop/.

## Remaining focused user checks

1. Close the previous desktop and open the built desktop executable. Explicitly
   tap Start on the phone. Check live preview and reachable Stop in portrait and
   landscape, including Advanced open and the zoom slider expanded.
2. Change supported camera/mode, zoom, Auto/Lock and torch locally, then through
   Windows. Confirm matching applied labels, physical focus/light effects and
   explicit fixed-focus/no-flash reasons on a limited lens. If the camera changes
   while a choice dialog is open, choose it again instead of applying an old choice.
3. Enable preview aids, confirm clean OBS output, then tap Stop and confirm the
   physical light releases. This remains visual/hardware acceptance, not a new
   reconnection test or an elapsed-duration gate.
