# DroidCam-Informed Android UI Implementation Plan

> **For agentic workers:** Use this plan task by task. Each task ends with focused verification.

**Goal:** Reorganize Camylla Android into a polished, responsive camera-first UI grounded in the supplied DroidCam OBS screenshots while preserving all working functionality.

**Architecture:** Keep the existing native Kotlin View stack and foreground `CaptureService` boundary. Extract small presentation helpers for adaptive layout and settings rows, and reorganize `MainActivity` and `CameraControlsPanel` around the reference console and category settings without changing camera, pairing, transport, or protocol behavior.

**Tech Stack:** Kotlin, Android Views, CameraX/Camera2, Gradle, JUnit, Android instrumentation.

**Spec:** `docs/mobile-ui-redesign.md`; evidence: `docs/research/droidcam-android-ui-2026-10-21.md`.

## Global Constraints

- Keep native programmatic Views; do not add Compose or migrate frameworks.
- Keep camera, encoder, service, pairing security, transport, authentication, and Windows protocol unchanged.
- Preserve explicit phone-side Start authorization and all observed/applied/pending/unavailable semantics.
- Keep Camylla branding and original vector assets; do not ship reference screenshots or third-party art.
- Preserve session and preview continuity through repeated rotation (#70).
- Capture visual evidence only under the user's explicit authorization; OBS is the release consumer.

## Review Focus

- Activity recreation during active streaming must not start/restart the session; verify navigation and preview lifecycle state restoration.
- Font scaling and short landscape height must not clip or hide Start/Stop; verify adaptive layout decisions and instrumentation visibility.
- A trusted PC must never appear as a live stream; verify status and action visibility by service state.
- Pending/unavailable camera values must not be displayed as applied; preserve descriptor-driven rows and verify control unit tests.
- Preference rows must not imply unsupported capabilities; audit each category against current settings/service APIs.

---

### Task 1: Adaptive camera layout and design primitives

**Files:**
- Create: `src/android/app/src/main/java/org/camylla/proof/CameraWorkspaceLayout.kt`
- Modify: `src/android/app/src/main/java/org/camylla/proof/MobileUi.kt`
- Test: `src/android/app/src/test/java/org/camylla/proof/CameraWorkspaceLayoutTest.kt`
- Modify: `src/android/app/src/test/java/org/camylla/proof/DesignSystemTest.kt`

Add a pure available-bounds layout policy for compact, stacked, and split arrangements; avoid physical screen metrics. Keep Camylla tokens, add shared separator/row/overlay helpers as needed, and maintain 48dp touch areas.

- [ ] Test compact, wide, and short-height bounds and exact thresholds.
- [ ] Run `:app:testDebugUnitTest --tests org.camylla.proof.CameraWorkspaceLayoutTest --tests org.camylla.proof.DesignSystemTest`.
- [ ] Implement the policy and token helpers; rerun the focused tests.

### Task 2: Camera console and runtime presentation

**Files:**
- Modify: `src/android/app/src/main/java/org/camylla/proof/MainActivity.kt`
- Modify: `src/android/app/src/main/java/org/camylla/proof/CameraControlsPanel.kt`
- Modify: `src/android/app/src/main/java/org/camylla/proof/CameraQuickControls.kt`
- Test: `src/android/app/src/androidTest/java/org/camylla/proof/CameraProductTest.kt`

Build a compact preview-led console: landscape uses a wide preview with a narrow status/control rail and compact camera-context actions; portrait stacks preview, quick controls, and a fixed capture action. Keep settings/navigation compact on Camera. Derive status and actions only from service stats and camera descriptors. Preserve tap focus, pinch zoom, presets, and all advanced controls.

- [ ] Extend instrumentation assertions for single Start/Stop visibility, quick controls, and page actions.
- [ ] Run focused `:app:assembleDebugAndroidTest` and current JVM UI/control tests.
- [ ] Implement the console and run `:app:testDebugUnitTest` plus Android test APK compilation.

### Task 3: Preference-style settings and category navigation

**Files:**
- Create: `src/android/app/src/main/java/org/camylla/proof/PreferenceRows.kt`
- Modify: `src/android/app/src/main/java/org/camylla/proof/MainActivity.kt`
- Modify: `src/android/app/src/main/java/org/camylla/proof/CameraControlsPanel.kt`
- Test: `src/android/app/src/androidTest/java/org/camylla/proof/CameraProductTest.kt`

Replace the generic settings dashboard with an icon-led category index and flat, divided rows. Map only real output, camera, power/screen, connection/security, and diagnostic capabilities. Keep the existing pairing and key-rotation flows and make camera settings available without changing capture authorization.

- [ ] Assert category navigation, existing pairing/security entry points, diagnostic access, and no nonfunctional control.
- [ ] Implement preference rows and category detail navigation with large-text wrapping and scroll behavior.
- [ ] Run the focused instrumentation compile and JVM suite.

### Task 4: Rotation restoration and integrated validation

**Files:**
- Modify: `src/android/app/src/main/java/org/camylla/proof/MainActivity.kt`
- Modify: `src/android/app/src/androidTest/java/org/camylla/proof/CameraProductTest.kt`
- Reuse: `src/android/app/src/androidTest/java/org/camylla/proof/RotationSessionHardwareTest.kt`

Restore selected destination and presentation state through Activity recreation; reattach the current preview and re-read service state. Do not alter foreground capture lifecycle. Validate visual hierarchy and control coverage, then use ADB only if a device is connected and authorized.

- [ ] Run `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --no-daemon`.
- [ ] Run connected device tests only where an available device/fixture supports them; report unexecuted checks explicitly.
- [ ] Complete a source audit against the capability inventory and report physical PASS/FAIL/NOT TESTED accurately.
