# M2 Android feasibility: S21 Ultra validation

**Status:** physical-device validation pending. Source and local JVM tests do not establish the M2 exit gate.

## Build and install

Open `src/android` as an Android Studio project. Use Android Studio Ladybug (2024.2.1) or newer with Android Gradle Plugin 8.7.3, Gradle 8.9 (wrapper), JDK 17, Android SDK Platform 35, and SDK Build Tools 34.0.0 or newer. The proof pins CameraX 1.5.3, which fits this toolchain. From a terminal with the Android SDK installed:

```powershell
cd src/android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The proof targets Android 10+ (API 29), but only the physical S21 Ultra is the M2 gate. Grant Camera permission when prompted. Notification permission is requested on Android 13+ so the capture notification is visible. No microphone permission is requested. Open the app while visible and tap **Start capture**; Android restricts starting a camera foreground service from the background. The notification offers **Stop**. The service does not restart capture after Stop or process death.

The app uses CameraX `Preview.SurfaceProvider` directly with the encoder input surface. A requested 30 FPS is only a request. The displayed FPS is derived from H.264 output presentation timestamps over a five-second window. A timestamp gap over 50 ms is reported as a possible late/dropped output, not a definitive camera-drop count. The requested size is exact; an unsupported camera/encoder combination reports an error instead of silently changing resolution. The encoder bitrate is an experimental 3 Mbit/s for 720p and 6 Mbit/s for 1080p.

The raw Annex B H.264 diagnostic file is in the app cache at `cache/diagnostics/capture-*.h264`. It is capped at 64 MiB per binding; the encoder continues draining after the cap. At most three recent files are retained when a new capture binding starts. A camera switch or mode change creates another file. These files are validation artifacts, not a recording feature. Pull the files from a debug installation with:

```powershell
adb shell run-as org.camylla.proof ls cache/diagnostics
adb exec-out run-as org.camylla.proof cat cache/diagnostics/capture-NAME.h264 > capture-NAME.h264
adb logcat -c
adb logcat -v threadtime -s CamyllaProof:* > camylla-android-log.txt
```

The logcat command runs until interrupted. Start it before the capture run. Record the app's displayed stats at the start, every minute, and the end (screen capture or notes). After extracting a `.h264` file, check it with `ffprobe -show_streams -count_frames capture-...h264` and decode a sample with `ffmpeg -v error -i capture-...h264 -f null -`. Raw H.264 does not carry the CameraX `SurfaceRequest` rotation metadata. Compare the displayed/logged transform rotation and decoded pixels at each phone orientation; record whether the encoded picture requires a later transform. Continuous-video AF is requested through CameraX Camera2 interop if available, falling back to continuous-picture AF if that is the supported mode. Check real focus behavior by moving between near and far subjects.

## Physical validation record

Record exact phone model, Android version/build, app commit, codec name, camera ID, charge state, ambient temperature, test date, and test operator. For every mode below record requested/actual resolution, encoded frame count, five-second FPS samples and minimum, timestamp-gap count, keyframes, file validity, and any camera/codec errors. A 64 MiB file cap means long-run file validity can be checked with an early segment; the frame counters continue for the whole run.

| Check | Result / evidence |
| --- | --- |
| Rear 1280×720 at measured ~30 FPS | Pending |
| Rear 1920×1080 at measured ~30 FPS | Pending |
| Front 1280×720 at measured ~30 FPS | Pending |
| Front 1920×1080 at measured ~30 FPS, if supported | Pending |
| Rear ↔ front switch, fresh encoder/keyframe/file | Pending |
| Zoom range and visible change on both lenses | Pending |
| Continuous autofocus near ↔ far and support report | Pending |
| Portrait/landscape rotation metadata and decoded picture | Pending |
| Home/app switch while capture continues | Pending |
| Screen off, screen on, return to app, notification Stop | Pending |
| Repeated start/stop (at least five cycles), no stale camera | Pending |
| At least 10 minutes continuous capture, FPS over time | Pending |
| Thermal status over time and physical heat observation | Pending |
| Battery start/end %, charging state, elapsed time | Pending |
| Valid H.264 SPS/PPS, keyframes and decoded output | Pending |

If 1080p or a control is unavailable, record the exact error, camera ID, Android build and logs. Run 720p first, then 1080p, on both lenses. A phone maintaining 30 FPS for a short run does not establish sustained reliability. Do not mark M2 complete until the S21 Ultra has produced valid sustained H.264 at the required supported baseline modes and the lifecycle, thermal and battery observations above are captured. Optional 60 FPS is outside this proof's gate.
