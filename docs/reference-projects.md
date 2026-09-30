# Reference-project source inspection

Inspected 2026-09-30. These are architectural references, not approved dependencies. Performance and compatibility require hands-on verification. Root licenses do not settle individual files, submodules or bundled binaries.

## scrcpy

Revision `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`; [Apache-2.0 license](https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/LICENSE).

[SurfaceEncoder.java](https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java) configures MediaCodec, creates an input Surface, and forwards encoded buffers. [demuxer.c](https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/app/src/demuxer.c) reads framed encoded packets with timestamp/flag/length metadata. Study codec changes and separation of media/control responsibilities. Its screen-capture lifecycle and ADB assumptions are not a drop-in consumer camera app. No code adoption approved; any reuse needs attribution and compatibility review.

## CatCam

Revision `f707f87365b442100247d31866fa101a337d4652`; [license](https://github.com/igorfyago/CatCam/blob/f707f87365b442100247d31866fa101a337d4652/LICENSE): repository GPL-2.0 designation; check file notices for upgrade permissions before reuse.

[StreamerService.kt](https://github.com/igorfyago/CatCam/blob/f707f87365b442100247d31866fa101a337d4652/android/app/src/main/java/com/catcam/app/StreamerService.kt) implements Camera2 to MediaCodec Surface, AudioRecord, plaintext TCP and discovery broadcasts. [CatCamHost.cpp](https://github.com/igorfyago/CatCam/blob/f707f87365b442100247d31866fa101a337d4652/windows/CatCamHost.cpp) integrates MF H.264 decode/virtual-camera registration. [FrameServer.cpp](https://github.com/igorfyago/CatCam/blob/f707f87365b442100247d31866fa101a337d4652/windows/FrameServer.cpp) and the host use global shared memory with NULL DACL. Do not inherit plaintext transport or all-user IPC access. Its proprietary audio companion is unsuitable for official Camylla. Useful functional reference, not approved code reuse.

## BestCam

Revision `30e3075c2a56aa49879b798bba802e5afa3fa0da`; [license](https://github.com/OneLimeStudio/BestCam/blob/30e3075c2a56aa49879b798bba802e5afa3fa0da/LICENSE): repository GPL-2.0 designation; verify file notices.

[BestCamHost.cpp](https://github.com/OneLimeStudio/BestCam/blob/30e3075c2a56aa49879b798bba802e5afa3fa0da/BestCamHost.cpp) and [FrameServer.cpp](https://github.com/OneLimeStudio/BestCam/blob/30e3075c2a56aa49879b798bba802e5afa3fa0da/FrameServer.cpp) illustrate MF host/source integration, including permissive global IPC that should not be copied. A minimal [Android app](https://github.com/OneLimeStudio/BestCam/tree/30e3075c2a56aa49879b798bba802e5afa3fa0da/android) exists although the README calls the complete phone app/GUI unpublished. Treat as incomplete product scaffolding and an API-mechanics reference.

## VCamdroid and Nexora

VCamdroid revision `f53d2f67691d468d89697cbc0e4178d3ed1082c4`; [source tree](https://github.com/darusc/VCamdroid/tree/f53d2f67691d468d89697cbc0e4178d3ed1082c4): repository MIT designation. Nexora revision `dd9c281a3922219fb5c5db42a1d1769c5abd1ea9`; [MIT license](https://github.com/akashlenvo/Nexora/blob/dd9c281a3922219fb5c5db42a1d1769c5abd1ea9/LICENSE).

They use Android RTSP and Windows FFmpeg/DirectShow, rather than the chosen MF camera source. Nexora's [Streamer.kt](https://github.com/akashlenvo/Nexora/blob/dd9c281a3922219fb5c5db42a1d1769c5abd1ea9/android/app/src/main/java/com/godoy/nexora/rtsp/Streamer.kt) wraps an external RTSP camera library; [.gitmodules](https://github.com/akashlenvo/Nexora/blob/dd9c281a3922219fb5c5db42a1d1769c5abd1ea9/.gitmodules) references Softcam. [ATTRIBUTION.md](https://github.com/akashlenvo/Nexora/blob/dd9c281a3922219fb5c5db42a1d1769c5abd1ea9/ATTRIBUTION.md) describes a fixed handshake mismatch/unchecked memory-copy failure: version/bounds checks deserve first-class tests. The [Android build](https://github.com/akashlenvo/Nexora/blob/dd9c281a3922219fb5c5db42a1d1769c5abd1ea9/android/app/build.gradle.kts) uses debug signing for release at this revision; do not copy this release practice. Study UX/failure handling, auditing transitive licenses separately before any reuse.

## DroidCam and Microsoft

[DroidCam source](https://github.com/dev47apps/droidcam-obs-client) confirms an OBS fork with Qt UI/libobs. [Official setup](https://droidcam.app/windows/) confirms a separately installed client and webcam output. Camylla takes workflow inspiration while excluding OBS engine/shell and composition features.

[Microsoft sample](https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera) is the preferred software-camera API reference; root source license is MIT. Pin its revision before reuse. Its COM source, user-process IPC and MSI/HKLM registration illustrate mechanics. Example format filtering is sample policy, not a platform FPS limit. The sample does not prove consuming-app compatibility or adminless installation.

## Selected-stack licensing leads

CameraX/AndroidX: [Apache-2.0 source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt). QR candidate ZXing: [Apache-2.0 source](https://github.com/zxing/zxing/blob/master/LICENSE). Windows App SDK: [MIT source](https://github.com/microsoft/WindowsAppSDK). .NET: [MIT source versus distinct Windows binary distribution terms](https://github.com/dotnet/core/blob/main/license-information.md). Audit exact NuGet/runtime artifacts and VC runtime redistribution before approving the installer; source license alone is insufficient.

[SignPath conditions](https://signpath.org/terms.html) include existing signable releases and component restrictions. Free signing is a lead, not secured funding or assured first-release eligibility. [Microsoft signing guidance](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options) distinguishes direct downloads from Store signing. No applications, purchases or external communications have been made.
