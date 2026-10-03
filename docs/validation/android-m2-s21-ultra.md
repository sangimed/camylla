# M2 Android feasibility: S21 Ultra validation

**Status:** M2 physical feasibility validated on one Samsung Galaxy S21 Ultra. This is not a broad Android compatibility claim. Detailed thermal/battery, other-mode, and long-duration measurements remain unrecorded.

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

The recorded physical run used a Samsung Galaxy S21 Ultra and the hardware encoder `c2.exynos.h264.encoder`. Exact Android build, camera ID, charge state, ambient temperature, test date/operator, and app commit are not present in the available evidence; they are not inferred here. The CameraX `Preview.SurfaceProvider` → MediaCodec input Surface path produced valid Annex B H.264. A 64 MiB file cap means a diagnostic file may cover only an early segment of a long run.

| Check | Result / evidence |
| --- | --- |
| Rear 1280×720 at requested 30 FPS | About 29.9 encoded FPS; zero output timestamp gaps over 50 ms in the recorded run |
| Rear 1920×1080 at measured ~30 FPS | No exact result recorded |
| Front 1280×720 at measured ~30 FPS | No exact result recorded |
| Front 1920×1080 at measured ~30 FPS, if supported | No exact result recorded |
| Rear ↔ front switch, fresh encoder/keyframe/file | Camera switching manually exercised successfully; exact counts not recorded |
| Zoom, autofocus, mode/control paths | Manually exercised successfully; continuous-video AF was requested; individual numeric results not recorded |
| Portrait/landscape rotation metadata and decoded picture | No exact result recorded |
| Home/app switch while capture continues | Foreground-service/background/return lifecycle manually validated |
| Screen off, screen on, return to app, notification Stop | Screen off/on manually validated; exact timings not recorded |
| Repeated start/stop | Manually validated; cycle count not recorded |
| Continuous duration, FPS over time | Recorded run yielded 3,984 frames parsed by `ffprobe`; no exact duration or minute-by-minute samples recorded |
| Thermal status and physical heat | No numeric result recorded |
| Battery start/end %, charging state, elapsed time | No numeric result recorded |
| Valid H.264 SPS/PPS, keyframes and decoded output | `ffprobe` parsed 3,984 frames; H.264 High profile, `yuv420p`, BT.709, progressive; full FFmpeg decode completed with no errors |

The physical result establishes the initial 720p30 producer direction and the manually exercised lifecycle paths on this phone. It does not establish sustained thermal/battery behavior, every mode on both lenses, or behavior on other Android devices. Preserve those gaps for later compatibility and release validation; optional 60 FPS remains outside the core gate.
