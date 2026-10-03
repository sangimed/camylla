# M3 secure Android-to-Windows transport validation

**Status:** implementation and local build checks complete; physical decoded-frame
acceptance and recovery measurements pending. Do not mark M3 complete from this
document until a real phone → local Wi-Fi → Windows run increases decoded-frame
count with recorded diagnostics.

## Setup

Use a Samsung Galaxy S21 Ultra on the same local Wi-Fi/LAN as a Windows 11 PC.
Build the Android app with `cd src/android; .\gradlew.bat :app:assembleDebug
:app:testDebugUnitTest`, then install its debug APK. Build the Windows components
from the repository root with:

```powershell
cmake -S . -B build -A x64
cmake --build build --config Debug
dotnet build src/windows/receiver/Camylla.Receiver.csproj
dotnet run --project src/windows/receiver/Camylla.Receiver.csproj
```

The native decoder DLL built by CMake is copied to the receiver output directory
by the .NET build target. On a machine without a .NET 10 SDK, install that SDK for
development. The receiver prints its generated development certificate pin and a
persistent development secret. Treat the secret as a credential. Enter the PC LAN
address, pin and secret into the Android proof app while it is visible. Start rear
`1280x720@30` from the phone. Keep the control and video ports (58413/58414)
reachable through the Windows firewall on the local network.

Expected acceptance path:

```text
S21 Ultra CameraX rear 720p30
→ MediaCodec H.264
→ bounded Android sender
→ authenticated TLS/TCP video + control
→ Windows receiver
→ inbox Media Foundation H.264 decoder
→ increasing decoded-frame count at the NV12 callback seam
```

The Windows receiver alone does not start phone capture. The phone notification
Stop action and app Stop button terminate the active capture session. Blank network
fields retain the local M2 diagnostic file behavior without opening a network
session. The M3 path requires all three address/pin/secret fields.

## Result record

| Measurement | Physical result |
| --- | --- |
| Phone / encoder name | S21 Ultra / pending M3 run log |
| Selected resolution / requested FPS | Rear 1280×720 / 30 planned; pending actual negotiation log |
| Sent frames / bytes | Pending |
| Received access units / sequence / PTS | Pending |
| Decoded frames / NV12 dimensions | Pending |
| Keyframes | Pending |
| Android queue high-water mark | Pending |
| Windows queue high-water mark | Pending |
| Memory behavior over run | Pending |
| Network errors / reconnect attempts | Pending |
| Decoder errors / resets | Pending |
| Recovery time after connectivity returns | Pending |

The repository's M2 physical result is recorded separately in
[android-m2-s21-ultra.md](android-m2-s21-ultra.md). It does not imply an M3 network
or Windows decode result. At implementation time, the attached `SM-G998B` had
Wi-Fi address `192.168.1.201` and could reach the PC's LAN address
`192.168.1.236`; its lock screen prevented starting the app's capture session.
No video access units or decoded frames were measured in that reachability check.
Those addresses are transient test context, not protocol configuration.

## Short disconnect/recovery test to perform

With decoded frames increasing, stop the Windows receiver for several seconds,
restart it and observe the existing phone session reauthenticate using its
in-memory development secret. Separately interrupt and restore the phone's Wi-Fi briefly while
the receiver remains open. Record the exact interruption and first new decoded
frame times, sent/received sequence values, queue peak, dropped frames, sync
requests, decoder resets and errors. Check that old dependent frames are not
replayed, codec configuration and a fresh keyframe precede resumed decoding, and
the phone's explicit Stop still ends capture. The design target is recovery within
about five seconds after usable connectivity returns; no such measurement has yet
been made.

## Local checks completed

- Android `assembleDebug` and `testDebugUnitTest` passed.
- Full Windows CMake Debug build and CTest suite passed.
- Receiver .NET build, wire self-test and Media Foundation decoder create/reset
  smoke test passed on this Windows machine.
- An FFmpeg-generated Annex B H.264 1280×720 stream with access-unit delimiters
  produced 90 decoded NV12 frames from 90 access units through the inbox Media
  Foundation decoder with its low-latency option enabled. This was synthetic
  Windows decode validation, not phone-to-Windows transport evidence.
- A local synthetic TLS loopback test authenticated separate control/video
  connections, rejected a video channel with a mismatched session ID, then sent
  90 H.264 access units followed by a deliberate sequence gap and fresh config/
  keyframe. The receiver reported 93 access units including two config records,
  4 keyframes, 91 decoded NV12 frames, one decoder reset, zero decoder errors,
  and a one-access-unit receive buffer high-water mark. Expected rejection/idle
  shutdown events appeared as network errors. This does not validate Android
  sender behavior or physical LAN recovery.
- These local checks do not establish the physical acceptance or latency gate.
- An additional `:app:lintDebug` run could not complete: the pinned AndroidX
  lifecycle lint detector crashed with `IncompatibleClassChangeError` in the
  current Android Gradle/lint toolchain. Android compilation and JVM tests passed.
