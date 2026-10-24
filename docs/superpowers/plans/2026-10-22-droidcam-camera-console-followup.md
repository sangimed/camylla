# DroidCam-inspired Android camera console follow-up

## Goal

Make the active Camylla camera experience structurally recognizable as a compact
camera console, based on direct inspection of the five user-supplied DroidCam OBS
screenshots. Resolve the previous redesign's shared compact layout on both Xiaomi
orientations. Preserve Camylla camera/session/pairing functionality and Windows.

## Boundaries

- Keep the existing native Kotlin Android View stack.
- Keep `CaptureService`, Camera2/CameraRenderer, encoder, transport, pairing,
  authentication, and Windows protocols unchanged unless a presentation seam
  proves insufficient. The reference's main-camera screenshot shows an active
  stream; a stopped workspace must show an honest no-frame prompt.
- Keep explicit phone-side Start/Stop and derive all status from service,
  transport, peer, and camera capability state.
- Use no DroidCam artwork or shipped screenshots. User-local comparison captures
  are temporary, authorized, and deleted after the review.
- Build with explicit alpha7-style debug version properties so the new install
  can be distinguished from the stale default debug version.

## TDD implementation tasks

### 1. Responsive camera workspace policy

Files: `CameraWorkspaceLayout.kt`,
`src/android/app/src/test/java/org/camylla/proof/CameraWorkspaceLayoutTest.kt`.

Add explicit policies for portrait stack, constrained landscape, wide landscape
console, tablet portrait stack, and large-text compact mode. Use available window
bounds and font scale. Keep the 800×500dp Xiaomi landscape eligible for the split
console when control/preview minimums fit (target 700dp width and 420dp height);
keep portrait at 500×800dp stacked. Large text and insufficient height select a
scrollable compact layout. Write threshold/edge tests first and observe failure.

### 2. Dedicated camera console

Files: `MainActivity.kt`, `CameraControlsPanel.kt`, `CameraQuickControls.kt`,
`MobileUi.kt` and focused `CameraProductTest.kt` coverage.

Replace the shared three-button dashboard header on Camera with a shallow camera
toolbar, a peer/session affordance, gear-based Settings entry, full-dominant
preview viewport, compact edge quick controls, and one sticky explicit capture
action. Landscape uses the leading connection/output rail and a preview taking
the remaining width; portrait uses a preview above a horizontal quick-control
rail and pinned Start/Stop. Use actual service state for paired-ready, starting,
capturing, recovering, stopped, and error. Keep the center image unobscured.
Write or extend focused tests for the layout/state action visibility before
changing the Activity.

### 3. Reference-shaped settings surfaces

Files: `MainActivity.kt`, `PreferenceRows.kt`, `MobileUi.kt`, and existing
instrumentation navigation coverage.

Make settings a flat icon-led category index and titled preference detail pages
with dividers, values, subtitles, trailing controls, and back navigation. Keep
Video, Camera, Security, and Diagnostics mapped to existing capabilities. Keep
pair/trust workflows in Connections. Remove overlarge card styling and preserve
all existing callbacks. Cover category/detail/back behavior and long-text
wrapping with focused assertions.

### 4. Accessibility and state regression coverage

Files: focused JVM tests and `CameraProductTest.kt` only where needed.

Verify all capture states use truthful labels, pairing is not streaming, one
Start/Stop is visible, quick actions call existing controls, unavailable actions
retain explanations, and 48dp target geometry survives font scaling. Verify
rotation returns to the same page and reattaches preview without issuing a new
start/stop. Do not change #70 service/session ownership.

### 5. Build and automated validation

From `src/android`, run explicit-version `:app:assembleDebug`, focused UI/layout
JVM tests, `:app:assembleDebugAndroidTest`, `:app:lintDebug`, and focused emulator
navigation/orientation instrumentation. Record command/output and APK SHA. Run
no unrelated long-duration test.

### 6. Physical baseline and redesigned app

Device target is Xiaomi 2405CPCFBG, Android 16, Android user 10. First verify the
active profile and attempt installation of the exact alpha6 release artifact for
baseline. The OS currently cancels ADB installs with
`INSTALL_FAILED_USER_RESTRICTED`; do not alter install policy or switch profiles.
If the phone permits the installation through its normal UI, capture only the
authorized Camylla screens needed for baseline and post-redesign comparison,
including portrait and landscape main/settings views. Then build/install the new
debug artifact into user 10, verify package/version/Activity/process/permissions,
exercise Settings and real camera actions, inspect the actual display, and delete
the temporary captures. If OS authorization remains blocked, report physical
acceptance as NOT TESTED and keep emulator/build results separate.

## Acceptance

- Directly visible structure differs from the previous screen in portrait and
  landscape: camera toolbar is compact, preview has dominant usable dimensions,
  camera actions form grouped edge rails, and Settings uses flat preference
  index/detail navigation.
- The 800×500dp Xiaomi landscape chooses the console; 500×800dp portrait chooses
  stacked preview/controls; large text and short-height windows remain usable.
- No visible action is inert, no device-unavailable action is fabricated, and no
  paired/remembered state is called streaming.
- Active rotation preserves the same service session and preview attachment.
- Exact installed source/APK/package/Activity/profile evidence is recorded. No
  physical visual acceptance claim is made without a successful install, launch,
  and authorized display inspection.
- UI Finish Gate and Reality Checker report no unresolved BLOCKER/MAJOR issue,
  or remaining blockers are reported as incomplete.
