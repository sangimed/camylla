# M1 Windows virtual-camera validation worksheet

Status: **M1 viable — Windows Media Foundation direction accepted.**

Physical Windows validation completed on 2026-10-02 for the selected current-user development path. The proof established registration/activation, real Frame Server sample delivery, baseline NV12 modes, consumer use, repeated lifecycle behavior, and clean removal. Reboot persistence, all-users scope, and additional target-application/version coverage remain useful later compatibility evidence but are not blockers for starting M2.

## Investigation and fixes

The first physical Windows registration reached `IMFVirtualCamera::Start()` and returned `0x80004002` (`E_NOINTERFACE`) after `MFCreateVirtualCamera()` succeeded. Direct COM diagnostics then reproduced missing inherited interfaces on the WRL activation/source objects. The source was corrected to expose the required interface chains, and the media-source COM registration was separated from virtual-camera access scope so the source is registered machine-wide under HKLM while the camera may remain current-user scoped.

After activation succeeded, OBS and Discord could enumerate/open the camera but initially received no usable image. The sample-delivery path was then aligned with the Windows Camera sample contract: the source advertises `MFSampleAllocatorUsage_UsesProvidedAllocator`, accepts the pipeline allocator, allocates samples through `IMFVideoSampleAllocator`, writes NV12 through `IMF2DBuffer2`, and timestamps samples using `MFGetSystemTime()`. The synthetic `CAMYLLAPOC` pattern then became visible in real consumers.

The final cleanup failure was `IMFVirtualCamera::Remove()` returning `0xC00D36B2` (`MF_E_INVALIDREQUEST`). The control lifecycle was corrected to reopen the system-lifetime camera with identical parameters, call `Stop()` before `Remove()`, then `Shutdown()`, while preserving the first failure and cleaning the COM registration after successful removal. The final register/use/unregister cycle passed.

## Environment

| Field | Value |
| --- | --- |
| Date | 2026-10-02 |
| Windows version/build | Windows 11 x64; CMake targeted Windows `10.0.26200` on the validation host |
| CPU / GPU | Not recorded |
| Visual Studio / MSVC version | Visual Studio 2022 Community / MSVC `19.44.35229.0` |
| Windows SDK version | `10.0.26100.0` |
| CMake version | Not recorded |
| POC revision | `main` after PR #12 (`Fix M1 virtual camera cleanup lifecycle`) |
| Registration scope | `current-user` |
| Terminal elevated? | Yes for machine-wide COM registration/removal |

## Physical validation results

| Check | Result | Notes / evidence |
| --- | --- | --- |
| Native Release build | Pass | `camylla_virtual_camera_source.dll` and `camylla_virtual_camera_ctl.exe` built successfully with MSVC. |
| Portable automated tests | Pass | `camylla_protocol_tests` and `camylla_virtual_camera_core_tests`: 2/2 passed. |
| Current-user registration succeeds | Pass | `Registered Camylla POC Windows Virtual Camera.` |
| HKLM x64 COM registration | Pass | `InprocServer32` points to the Release DLL; `ThreadingModel=Both`. |
| COM activation diagnostics | Pass | `IMFActivate`, `IMFAttributes`, `IMFMediaSource`, `IMFMediaSourceEx`, `IMFGetService`, `IKsControl`, `IMFSampleAllocatorControl`, `IMFMediaStream`, and `IMFMediaStream2` all queried successfully. |
| Camera enumerates after registering process exits | Pass | Camera is selectable as `Camylla POC (Windows Virtual Camera)` in real consumers. |
| Source activation occurs on consumer open | Pass | Real consumers open the registered virtual camera and receive the source output. |
| 720p30 NV12 delivers frames | Pass | Synthetic output validated through the Windows camera pipeline. |
| 1080p30 NV12 delivers frames | Pass | Synthetic output validated through the Windows camera pipeline. |
| Repeated open/close/reopen behavior | Pass | Repeated consumer lifecycle testing completed without the earlier activation failure. |
| Offline/test output is deterministic and non-stale | Pass | Animated synthetic `CAMYLLAPOC` pattern is visible to consumers. |
| Pipeline-provided sample allocator path | Pass | Final implementation uses the provided video sample allocator and real `MEMediaSample` delivery. |
| System-lifetime behavior after reboot | Not tested | Deferred; not required to start M2. Revisit for packaging/release validation. |
| Current-user removal succeeds | Pass | Final `Stop -> Remove -> Shutdown` lifecycle removes the virtual camera cleanly. |
| COM registration cleanup succeeds | Pass | Machine-wide source registration is removed after successful camera removal. |
| All-user registration/removal | Not tested | Current-user access is the selected v1 path; all-users behavior is deferred. |

## Consumer matrix

Exact application versions were not captured during this feasibility run. Do not turn these results into broad compatibility claims until versioned release testing is performed.

| Consumer | Version | Result | Notes |
| --- | --- | --- | --- |
| OBS Studio | Not recorded | Pass | Virtual camera opened successfully; synthetic output and baseline 720p30/1080p30 modes were exercised. |
| Discord | Not recorded | Pass | Virtual camera opened successfully and displayed the animated `CAMYLLAPOC` pattern. |
| Microsoft Teams | Not tested | Not tested | Explicitly untested in this M1 run; retain for later compatibility matrix. |
| Zoom | Not tested | Not tested | Explicitly untested in this M1 run; retain for later compatibility matrix. |

## Deployment findings

The selected development workflow separates two concerns:

- the media-source COM DLL is registered machine-wide under `HKLM\Software\Classes\CLSID\{CA9F0B23-C7E0-42C7-94B4-2D6A5FAD544D}\InprocServer32` so Windows Frame Server can activate it;
- the virtual camera itself uses `MFVirtualCameraAccess_CurrentUser`.

Because the development source registration writes HKLM, registration/removal requires an elevated terminal. This is sufficient evidence for the M1 architecture proof; installer UX and final distribution behavior remain later milestones.

## Conclusion

**M1 viable — continue with Media Foundation.**

The selected Windows 11 Media Foundation software-camera architecture is proven far enough to proceed: Windows can register and activate the custom source, real consumers receive deterministic NV12 frames at the baseline modes, repeated use works, and the current-user development camera can be removed cleanly.

M1 is therefore complete. Remaining reboot/all-users/application-version coverage belongs to later compatibility, packaging, and release validation rather than the Android feasibility gate.
