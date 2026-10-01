# Windows virtual-camera POC

This is M1's reproducible proof implementation. It is a native C++ Media Foundation software camera source using `MFCreateVirtualCamera`; it has no network listener, Android component, UI, OBS dependency, FFmpeg dependency, or IPC implementation.

> This is a development POC, not an end-user installer. The steps below are the supported developer workflow for building, registering, testing, and removing the camera on Windows 11.

## What it exposes

The camera's friendly name is **Camylla POC Windows Virtual Camera** (Windows appends `Windows Virtual Camera` to the requested friendly name). Its deliberately small advertised list is NV12 only:

| Resolution | Frame rate | Pixel format |
| --- | --- | --- |
| 1280×720 | 30 FPS | NV12 |
| 1920×1080 | 30 FPS | NV12 |

The source generates a moving NV12 colour-bar pattern with a `CAMYLLAPOC` glyph label, frame counter, dimensions, and FPS. It derives every frame from its sequence number, so the result is deterministic. There is no retained producer frame: this is also the neutral/offline output that a future source can select when the desktop receiver has no current frame.

`FrameProvider` is the future boundary. `SyntheticFrameProvider` is the only implementation in this POC. A desktop-to-camera provider can replace it later without placing a socket, retry loop, or producer wait inside the Media Foundation source. IPC design is intentionally deferred.

## Windows developer quick start

### 1. Prerequisites

Use a **Windows 11 x64** development machine, build 22000 or later.

Install Visual Studio 2022 or Visual Studio 2022 Build Tools with the C++ desktop toolchain. The environment must provide:

- MSVC x64 C++ compiler;
- Windows 11 SDK containing `mfvirtualcamera.h` (SDK 22000 or later);
- CMake 3.25 or later.

Git is required to clone the repository.

The canonical POC procedure below uses the **Visual Studio 17 2022** CMake generator. This is intentional: it avoids requiring a separate Ninja installation and gives a predictable `Release` output directory.

### 2. Clone and enter the repository

```powershell
git clone https://github.com/sangimed/camylla.git
cd camylla
git switch main
git pull
```

For development on an existing checkout, make sure `main` contains the Windows virtual-camera POC before continuing.

### 3. Configure the x64 build

Run from PowerShell, Developer PowerShell for Visual Studio, or another shell where CMake can find Visual Studio 2022:

```powershell
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
```

Configuration must finish without errors. A missing `mfvirtualcamera.h` or Windows SDK error means the required Windows SDK is not installed or not selected.

### 4. Build Release

```powershell
cmake --build build --config Release
```

The relevant POC artifacts should then be:

```text
build\src\windows\virtual-camera\Release\camylla_virtual_camera_source.dll
build\src\windows\virtual-camera\Release\camylla_virtual_camera_ctl.exe
```

Keep the DLL and control executable together for the default workflow.

### 5. Run the automated tests

```powershell
ctest --test-dir build -C Release --output-on-failure
```

At this stage the expected test executables include:

- `camylla_protocol_tests`;
- `camylla_virtual_camera_core_tests`.

Do not continue as if the POC is healthy if either test fails. Preserve the full failing output.

### 6. Register the camera for the current user

Open a **normal, non-elevated PowerShell** first. Current-user registration is the preferred M1 validation path.

```powershell
cd build\src\windows\virtual-camera\Release
.\camylla_virtual_camera_ctl.exe register --scope current-user
```

A successful command should report that the Camylla virtual camera was registered.

The tool:

1. registers the POC COM source under the current user's classes;
2. calls `MFCreateVirtualCamera`;
3. starts the system-lifetime virtual camera.

Do not manually edit the registry as part of the normal workflow.

### 7. Verify Windows can see the camera

Check:

**Settings → Bluetooth & devices → Cameras**

Look for:

**Camylla POC Windows Virtual Camera**

The camera should remain enumerable after `camylla_virtual_camera_ctl.exe` exits because the POC requests system lifetime. Whether this works as expected on the target machine is part of the M1 evidence and must be recorded rather than assumed.

### 8. Perform the first smoke test

For the first manual test, OBS Studio is a convenient consumer because it exposes capture-device configuration clearly.

In OBS:

1. add a **Video Capture Device** source;
2. select **Camylla POC Windows Virtual Camera**;
3. verify that the generated Camylla test pattern moves;
4. test 1280×720 at 30 FPS;
5. test 1920×1080 at 30 FPS if the consumer exposes the mode;
6. close the consumer and open the camera again.

The full M1 test matrix also includes Teams and Zoom. Record results in [the M1 Windows validation worksheet](validation/windows-virtual-camera-m1.md).

Do not report a consumer as supported solely because the camera appears in its device list.

### 9. Remove the development camera

When finished testing, use the same registration scope:

```powershell
.\camylla_virtual_camera_ctl.exe unregister --scope current-user
```

Then verify that the camera disappears from Windows and from consumers.

If the command fails, preserve the complete output and HRESULT before attempting manual cleanup.

## All-users experiment

All-users registration is **not** the default developer workflow.

Only test it when the current-user path has already been characterized. Use an elevated terminal:

```powershell
.\camylla_virtual_camera_ctl.exe register --scope all-users
.\camylla_virtual_camera_ctl.exe unregister --scope all-users
```

All-user registration writes the corresponding HKLM COM entry and uses `MFVirtualCameraAccess_AllUsers`. The elevation/UAC behavior observed on the test machine belongs in the M1 validation evidence.

## Alternative Ninja build

Contributors may use Ninja, but it is not the canonical Windows POC procedure.

For a Release build:

```powershell
cmake -S . -B build-ninja -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build-ninja
ctest --test-dir build-ninja --output-on-failure
```

Ninja is a single-config generator. The artifacts are therefore expected directly under:

```text
build-ninja\src\windows\virtual-camera\
```

There is normally no `Release\` subdirectory in this layout. This distinction is why the Visual Studio generator is used in the primary instructions.

## Registration and source lifetime

The current-user workflow writes the POC COM activation entry under:

```text
HKCU\Software\Classes\CLSID\{CA9F0B23-C7E0-42C7-94B4-2D6A5FAD544D}\InprocServer32
```

It then calls `MFCreateVirtualCamera` with system lifetime/current-user access and starts it. `unregister` calls `IMFVirtualCamera::Remove` before deleting that exact COM key.

For all-users scope, the corresponding class entry is under HKLM.

`MFVirtualCameraLifetime_System` is used so the camera is intended to persist across sessions and reboots. Reopening with identical API parameters is intended to reopen the same camera; `Remove` disables and unregisters it.

The source itself is activated by the Windows frame-server pipeline when a consumer opens the device rather than living in the registration process.

These are API/design expectations until they are observed on a physical Windows machine. Record actual behavior in the validation worksheet.

The DLL is a COM in-process server loaded by Windows camera infrastructure. This is why registering the virtual camera alone is insufficient: the matching DLL path and `Both` threading-model COM entry must remain available to the frame server. No kernel driver or Windows service is installed.

## Troubleshooting

### CMake cannot find a suitable Windows SDK

Symptoms can include a missing `mfvirtualcamera.h` or compile errors around `MFCreateVirtualCamera`.

Verify that a Windows 11 SDK 22000 or later is installed through Visual Studio Installer and re-run CMake configuration from a clean build directory.

### `camylla_virtual_camera_source.dll` is missing

Confirm that the Release build completed successfully:

```powershell
cmake --build build --config Release
```

With the canonical Visual Studio generator, look under:

```text
build\src\windows\virtual-camera\Release\
```

### Registration says the source DLL does not exist

Run the control program from the directory containing both the EXE and DLL, or pass the DLL explicitly:

```powershell
.\camylla_virtual_camera_ctl.exe register --scope current-user --source "C:\absolute\path\to\camylla_virtual_camera_source.dll"
```

### Registration fails with an HRESULT

Do not hide or normalize the failure. Capture:

- the complete command;
- the complete output;
- the HRESULT;
- Windows edition/version/build;
- registration scope;
- whether the terminal was elevated.

Add that evidence to the validation worksheet or the tracking issue.

### Registration succeeds but the camera is not visible

Record the result before making manual registry changes.

Check Windows Settings and at least one camera consumer. Then try a clean unregister/register cycle with the same scope.

If the camera still does not enumerate, treat it as an M1 failure to investigate rather than working around it silently.

### A previous POC camera remains installed

First use the control program with the same scope that created it:

```powershell
.\camylla_virtual_camera_ctl.exe unregister --scope current-user
```

or, from an elevated terminal when appropriate:

```powershell
.\camylla_virtual_camera_ctl.exe unregister --scope all-users
```

Manual registry cleanup should be a last-resort diagnostic step, not the documented uninstall procedure.

## Evidence boundaries

| Finding | Basis | POC observation |
| --- | --- | --- |
| Build 22000+, `MFCreateVirtualCamera`, system/current-user/all-user parameters, and all-user administrator requirement | Microsoft API documentation | Not tested on Windows yet |
| System lifetime persists across sessions/reboots; identical parameters reopen a camera; `Remove` invalidates active consumers | Microsoft API documentation | Not tested on Windows yet |
| Frame-server processes host the custom source during creation/use | Microsoft VirtualCamera sample documentation | Not tested on Windows yet |
| HKCU COM registration is sufficient for current-user source activation | POC hypothesis to validate | Not tested; do not treat as proven deployment behavior |
| Teams, Zoom, OBS compatibility; exact negotiated formats; repeat-open behavior | Required manual consumer testing | Not tested |

The implementation deliberately avoids treating sample behavior as product evidence. In particular, the current-user COM deployment path and reboot behavior must be recorded by the worksheet before an installer or a final registration-scope decision is made.

## Full M1 manual validation

After the smoke test succeeds, complete [the validation worksheet](validation/windows-virtual-camera-m1.md) on a physical Windows 11 x64 machine.

It requires recording:

- actual Windows build;
- CPU/GPU;
- MSVC/SDK/CMake versions;
- registration scope;
- registration/activation behavior;
- both advertised modes;
- repeated open/close/reopen results;
- reboot/system-lifetime behavior;
- current-user and, later, all-user removal behavior;
- exact Teams, Zoom, and OBS versions and results.

Preserve failures and HRESULTs. Do not replace `Not tested` entries with assumptions.

## Evidence status

The repository has tested the portable frame, layout, and cadence logic. It has **not** yet recorded Windows virtual-camera registration, frame-server activation, reboot persistence, UAC behavior, or target consumer results on a physical Windows host.

M1 therefore remains:

**M1 partially validated — additional Windows proof required.**

A successful Windows build is necessary but not sufficient; only the completed manual worksheet can establish the selected Media Foundation architecture's viability.

## Source and licensing note

The implementation was written for Camylla; it does not copy source from Microsoft's sample. It uses Microsoft's documented API contract and studied the [Windows Camera VirtualCamera sample](https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera) as an architectural reference. That repository's root `LICENSE` is MIT, as previously recorded in [reference-projects.md](reference-projects.md), but no sample code or assets are included here.
