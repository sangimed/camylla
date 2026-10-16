# Android rotation preserves the camera session — #70

Tracking: [#70](https://github.com/sangimed/camylla/issues/70).
Unmerged engineering checkpoint; physical paired-stream and OBS acceptance remain
unverified. No roadmap milestone is marked complete.

## Ownership investigation

- `ProductIdentity` stores paired desktops in encrypted, atomically written
  protected storage using Android Keystore keys. Recreating `MainActivity` reads
  that trust again; it does not revoke or replace the paired desktop.
- The phone uses native Activities and Views, not Compose or a ViewModel.
  Preferences retain camera/mode/desktop choices. An active camera session lives
  in the explicitly started `CaptureService`, independently of Activity state.
- Android's default orientation configuration handling recreates `MainActivity`.
  `onStop` detaches its preview and unbinds; the new Activity rebinds and attaches
  its new `SurfaceView`. The UI builds horizontal or vertical layouts from the
  configuration. Rotations remain enabled.
- `CaptureService` owns one `CameraEngine`, one `AvcEncoder`, and one
  `NetworkSender`. Camera2 and EGL operations run on the camera handler;
  MediaCodec drains and network/control sockets run on their own workers.
  Preview attachment and display rotation do not rebind the camera, allocate a
  new encoder, or restart transport. Generation checks reject obsolete callbacks.
- An explicit start enters a camera foreground service. Backgrounding detaches
  phone monitoring while the session continues. Stop, a fatal pipeline error,
  or service destruction closes camera, encoder, and network resources. The
  service returns `START_NOT_STICKY`, preserving the explicit-start rule after
  process death.
- There is no wake lock or keep-screen-on flag. The service owns a thermal
  listener and reduces encoder bitrate under severe thermal/network pressure.
  This change does not alter screen-off, battery, or thermal policy; those
  behaviors are not established by a simulated GPU test.

## Reproduction and root cause

A `SurfaceView` window can become invalid as Android destroys the old Activity,
before the queued camera-handler detach runs. `CameraRenderer.frame()` previously
treated a failed preview `eglMakeCurrent`/draw like a failed encoder render. Its
`onError` reached `CameraEngine.fatal`, then `CaptureService.endSession`, closing
the camera, encoder, and network connection. Persisted pairing was not actually
deleted, but the active session ended.

The JVM regression runs the production renderer, simulating only Android's native
EGL/GL/Surface boundary. It replaces portrait/landscape previews six times and
invalidates each preview before queued detachment, matching the lifecycle race.
The test requires all 18 encoder frames/completions, a displayed frame on every
replacement preview, no fatal callback, and exactly one release per preview.
A second test ensures encoder surface failure still reports a fatal error.

Before the production change, this command ran two tests with one failure:

```powershell
cd src/android
./gradlew.bat :app:testDebugUnitTest --tests org.camylla.proof.CameraPreviewLifecycleTest
```

The failure was `A lost phone preview must not end the camera session`: expected
no fatal callbacks, observed six `Camera rendering: Check failed.` callbacks.
There were no test errors. After the correction, the same command passed both
tests in nine seconds.

## Correction

The renderer now isolates phone-preview rendering errors and detaches the failed
preview while keeping encoder rendering active. Before releasing/replacing a
preview it makes the encoder surface current, so the old window is no longer the
current EGL target. Encoder/camera failures retain their existing fatal path.
The replacement Activity can attach its new preview to the same renderer.

The only production change is in `CameraRenderer.kt`. Mockito is a JVM test-only
dependency for the native boundary; no runtime dependency or session ownership
change is introduced.

## Automated verification

```powershell
cd src/android
./gradlew.bat :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- Debug and release JVM suites: 57 tests each, zero failures/errors/skips.
- Debug application APK and instrumentation APK assemble successfully.
- Debug lint: zero errors, 117 warnings; no diagnostic names either new test or
  the changed renderer. Existing deprecation/native-symbol/Mockito JVM warnings
  remain visible in the build output.
- `git diff --check` passes.

## Focused physical check still required

The available device is a Pixel 9 Pro XL API 35 emulator; no physical paired
phone/desktop is connected. The instrumentation test was run without its explicit
capture opt-in and skipped before starting capture (1 skipped, 0 failed). Neither
physical camera capture nor OBS consumer validation was executed. No images,
screenshots, recordings, or other visual captures were created.

`RotationSessionHardwareTest` is opt-in, because it starts capture. Install the
debug application and test APK, grant camera permission, pair a running desktop,
and run:

```powershell
adb shell am instrument -w -e class org.camylla.proof.RotationSessionHardwareTest -e rotationSession true org.camylla.proof.test/androidx.test.runner.AndroidJUnitRunner
```

The test starts the selected paired stream in a visible Activity, alternates
portrait/landscape four times, waits for a valid replacement preview and expected
preview rotation, and requires encoded/sent frames to advance. It asserts the
same service, camera, encoder, network sender, camera generation, capture
authorization, transport session, phone identity, and paired desktops throughout.
It stops capture and closes the Activity afterward. Without the explicit argument
it is skipped. It creates no visual capture.

With OBS open as the consuming application, also confirm that the picture remains
live through rotations and background/return, that the phone preview is correctly
oriented, and that Stop releases capture and leaves pairing available for a fresh
explicit start. Record results and versions in this record and #70. No fixed
duration endurance wait is required.
