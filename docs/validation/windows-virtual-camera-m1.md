# M1 Windows virtual-camera validation worksheet

Status: **Not tested in this repository's Linux implementation environment.** Complete this worksheet on the Windows machine that runs the POC. Preserve failed results and HRESULTs.

## Environment

| Field | Value |
| --- | --- |
| Date | Not tested |
| Windows edition/version/build | Not tested |
| CPU / GPU | Not tested |
| Visual Studio / MSVC version | Not tested |
| Windows SDK version | Not tested |
| CMake version | Not tested |
| POC commit | Not tested |
| Registration scope | Not tested |
| Terminal elevated? | Not tested |

## Procedure

1. Build with the commands in [the POC guide](../windows-virtual-camera-poc.md#build), then run `ctest`.
2. From the output directory, run `camylla_virtual_camera_ctl.exe register --scope current-user`. Record its full result and HRESULT if it fails.
3. Verify that **Camylla POC Windows Virtual Camera** appears in Windows Settings → Bluetooth & devices → Cameras and in a camera enumerator. Record whether it remains enumerable after the control program exits.
4. Open the camera in an application, select 1280×720 at 30 FPS if selectable, and confirm moving pattern, label, counter, dimensions, and FPS. Close it, reopen it five times, and record whether every activation is clean.
5. Repeat for 1920×1080 at 30 FPS. Record the selected format as reported by the application, not merely requested.
6. While a consumer is open, confirm that the source delivers the generated neutral test output. This proves the future receiver's absence cannot leave a stale user image or block the consumer.
7. Reboot, then check enumeration and activation before running the control program again. Record the actual result. Run `unregister` and confirm removal in Settings and the application.
8. Repeat registration/removal as `--scope all-users` only from an elevated terminal. Record the exact UAC/administrator behavior. Remove it before ending the test.

## Lifecycle results

| Check | Result | Notes / evidence |
| --- | --- | --- |
| Current-user registration succeeds | Not tested | |
| Camera enumerates after registering process exits | Not tested | |
| Source activation occurs on consumer open | Not tested | |
| 720p30 NV12 delivers frames | Not tested | |
| 1080p30 NV12 delivers frames | Not tested | |
| Five open/close/reopen cycles | Not tested | |
| Offline output is neutral and moves | Not tested | |
| System-lifetime behavior after reboot | Not tested | |
| Current-user removal succeeds | Not tested | |
| All-user registration/removal and UAC behavior | Not tested | |

## Consumer matrix

| Consumer | Version | Result | Notes |
| --- | --- | --- | --- |
| Microsoft Teams | Not tested | Not tested | Select both advertised modes; record video and reopening behavior. |
| Zoom | Not tested | Not tested | Select both advertised modes; record video and reopening behavior. |
| OBS Studio | Not tested | Not tested | Add Video Capture Device; record video and reopening behavior. |

## Conclusion

Choose only after completing the matrix:

- **M1 viable — continue with Media Foundation**: target consumers pass at one or both required modes, lifecycle/deployment evidence is workable, and offline output is correct.
- **M1 partially validated — additional Windows proof required**: a required observation remains missing or has a contained failure needing follow-up.
- **M1 failed — architecture decision must be revisited**: baseline consumers cannot use the source or a practical registration/lifecycle model fails.
