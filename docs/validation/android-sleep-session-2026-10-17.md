# Android active session survives device sleep — #71

Tracking: [#71](https://github.com/sangimed/camylla/issues/71), merged in
[PR #73](https://github.com/sangimed/camylla/pull/73).

## Ownership and failure path

The explicitly started foreground `CaptureService` owns capture authorization,
camera, encoder, and network sender. A screen-off Camera2 disconnect previously
reached the service's fatal callback, which ended the whole session and cleared
authorization. The phone's remembered desktop trust was persisted separately.

The service now suspends camera/encoder resources when camera access is lost
while locked, preserves the user's active authorization and network sender, and
rebinds the camera after wake/unlock. A recorded screen-off interruption also
covers Camera2 callbacks delivered after quick unlock. A partial CPU wake lock
keeps session work awake while the screen is off. `MainActivity` applies the
window keep-screen-on flag only while an authorized session is actively
capturing, reasserts it after Activity recreation, and clears it on stop or
Activity teardown. The system timeout is not changed.

## Regression evidence

`SleepSessionTest` uses the Android emulator's real foreground service, camera,
encoder, and UI lifecycle while injecting a Camera2 failure at controlled
points. It verifies:

- Active capture sets keep-screen-on; Activity recreation restores it.
- With emulator screen timeout temporarily set to 3 seconds, the screen remains
  interactive after 4 seconds of active capture. The original emulator setting
  is restored in `finally`.
- Explicit screen-off followed by a delayed camera failure after the device is
  interactive and unlocked retains capture authorization, paired trust, and the
  same network sender; camera capture resumes with a newer generation.
- A failure during locked suspension followed by explicit Stop retains saved
  non-default camera controls and releases camera, encoder, network sender,
  keep-screen-on, and the CPU wake lock.

Before the settings-preservation correction, the test failed because stopping
while suspended replaced saved controls with an empty object. The focused test
passes after the correction:

```powershell
cd src/android
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=org.camylla.proof.SleepSessionTest'
```

Result on Pixel 9 Pro XL API 35 emulator: 1 test, 0 failures, 0 skipped.
Android JVM unit tests and `git diff --check` passed. The PR Android and Windows
CI jobs passed, as did the merged `main` CI run.

The test uses an intentionally unreachable test peer. It proves sender ownership
and recovery state, not decoded live video delivery to a paired Windows client.
Physical screen-timeout, lock/unlock, and OBS streaming validation remain
outstanding. No screenshots or other visual captures were created.
