# Windows virtual-camera POC

This is M1's reproducible proof implementation. It is a native C++ Media Foundation software camera source using `MFCreateVirtualCamera`; it has no network listener, Android component, UI, OBS dependency, FFmpeg dependency, or IPC implementation.

## What it exposes

The camera's friendly name is **Camylla POC Windows Virtual Camera** (Windows appends `Windows Virtual Camera` to the requested friendly name). Its deliberately small advertised list is NV12 only:

| Resolution | Frame rate | Pixel format |
| --- | --- | --- |
| 1280×720 | 30 FPS | NV12 |
| 1920×1080 | 30 FPS | NV12 |

The source generates a moving NV12 colour-bar pattern with a `CAMYLLAPOC` glyph label, frame counter, dimensions, and FPS. It derives every frame from its sequence number, so the result is deterministic. There is no retained producer frame: this is also the neutral/offline output that a future source can select when the desktop receiver has no current frame.

`FrameProvider` is the future boundary. `SyntheticFrameProvider` is the only implementation in this POC. A desktop-to-camera provider can replace it later without placing a socket, retry loop, or producer wait inside the Media Foundation source. IPC design is intentionally deferred.

## Build

Build on Windows 11 x64, build 22000 or later, with a Windows SDK that contains `mfvirtualcamera.h` (22000 or later), Visual Studio 2022 MSVC x64, and CMake 3.25 or later. The Windows targets use Media Foundation system libraries only.

```powershell
cmake -S . -B build -G Ninja
cmake --build build --config Release
ctest --test-dir build --output-on-failure -C Release
```

The resulting files are `build/src/windows/virtual-camera/Release/camylla_virtual_camera_source.dll` and `camylla_virtual_camera_ctl.exe` for a multi-config generator. Keep them in the same directory, or pass the DLL's absolute path with `--source`.

The portable core and its tests deliberately build on non-Windows hosts. The DLL and control program are guarded by `WIN32`, so Linux/macOS configuration does not require Windows headers or SDK libraries.

## Registration and removal

Use a normal terminal for the recommended current-user development registration:

```powershell
./camylla_virtual_camera_ctl.exe register --scope current-user
./camylla_virtual_camera_ctl.exe unregister --scope current-user
```

The tool writes the POC COM activation entry under `HKCU\Software\Classes\CLSID\{CA9F0B23-C7E0-42C7-94B4-2D6A5FAD544D}\InprocServer32`, then calls `MFCreateVirtualCamera` with system lifetime/current-user access and starts it. `unregister` calls `IMFVirtualCamera::Remove` before deleting that exact COM key. It reports every failed operation and HRESULT; it does not silently edit unrelated registry locations.

For an all-user experiment, use an elevated terminal:

```powershell
./camylla_virtual_camera_ctl.exe register --scope all-users
./camylla_virtual_camera_ctl.exe unregister --scope all-users
```

All-user registration writes the corresponding HKLM key and Microsoft documents administrator permission as required for `MFVirtualCameraAccess_AllUsers`; removal of a system-wide camera can also return `E_ACCESSDENIED` without it. Current-user virtual-camera creation has no documented administrator requirement, and its COM entry is intentionally in HKCU. This is a development workflow, not an installer claim.

`MFVirtualCameraLifetime_System` is used so Microsoft documents the camera as persisting across sessions and reboots. Reopening with exactly the same API parameters reopens the same camera; `Remove` disables and unregisters it. The source itself is activated by the Windows frame-server pipeline when a consumer opens the device, rather than living in the registration process. Microsoft documents `IMFVirtualCamera::Start` callbacks for source initialize/start/stop/uninitialize and pipeline-shutdown events. This POC has not observed those behaviors on a Windows machine yet; the manual procedure below records that distinction.

The DLL is a COM in-process server loaded by Windows camera infrastructure. This is why registering the virtual camera alone is insufficient: the matching DLL path and `Both` threading-model COM entry must be available to the frame server. No kernel driver or Windows service is installed.

## Evidence boundaries

| Finding | Basis | POC observation |
| --- | --- | --- |
| Build 22000+, `MFCreateVirtualCamera`, system/current-user/all-user parameters, and all-user administrator requirement | Microsoft API documentation | Not tested on Windows yet |
| System lifetime persists across sessions/reboots; identical parameters reopen a camera; `Remove` invalidates active consumers | Microsoft API documentation | Not tested on Windows yet |
| Frame-server processes host the custom source during creation/use | Microsoft VirtualCamera sample documentation | Not tested on Windows yet |
| HKCU COM registration is sufficient for current-user source activation | POC hypothesis to validate | Not tested; do not treat as proven deployment behavior |
| Teams, Zoom, OBS compatibility; exact negotiated formats; repeat-open behavior | Required manual consumer testing | Not tested |

The implementation deliberately avoids treating sample behavior as product evidence. In particular, the current-user COM deployment path and reboot behavior must be recorded by the worksheet before an installer or a final registration-scope decision is made.

## Manual validation

Run [the validation worksheet](validation/windows-virtual-camera-m1.md) on a physical Windows 11 x64 machine. It requires recording the actual Windows build, MSVC/SDK/CMake versions, registration scope, activation/reopen results, and exact Teams, Zoom, and OBS versions. Do not replace its `Not tested` entries with assumptions.

## Evidence status

The repository has tested the portable frame, layout, and cadence logic. It has **not** tested virtual-camera registration, frame-server activation, reboot persistence, UAC behavior, or consumer applications because this implementation was created on a Linux host. M1 is therefore **M1 partially validated — additional Windows proof required**. A successful Windows build is necessary but not sufficient; only the completed manual worksheet can establish the selected Media Foundation architecture's viability.

## Source and licensing note

The implementation was written for Camylla; it does not copy source from Microsoft's sample. It uses Microsoft's documented API contract and studied the [Windows Camera VirtualCamera sample](https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera) as an architectural reference. That repository's root `LICENSE` is MIT, as previously recorded in [reference-projects.md](reference-projects.md), but no sample code or assets are included here.
