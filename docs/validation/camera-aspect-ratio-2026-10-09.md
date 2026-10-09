# Camera image distortion — #43, 2026-10-09

Tracking: [#43](https://github.com/sangimed/camylla/issues/43), created before
implementation. The user reports deformation predating the current #26 mobile
UX changes. This is an unmerged fix checkpoint. The later physical proportions
confirmation is recorded below; it does not complete the #26 hardware matrix.
The user confirmed that deformation is visible in the phone, Windows and OBS.
All existing local edits, including ROADMAP.md and the #26 work, were retained.

## Diagnosis and correction

The Android renderer used the SurfaceTexture producer matrix unchanged, then
calculated geometry from the raw 1280×720 or 1920×1080 stream dimensions plus
Camylla's own rotation. Camera2 can attach sensor rotation/front mirroring to
that producer matrix. A quarter-turn transposes the sampled content, but the
old geometry still fits a raw landscape aspect: a circle can become an ellipse
with an axis ratio of (16/9)², approximately 3.16.

This behavior is grounded in Android's
[Camera3Device producer transform setup](https://android.googlesource.com/platform/frameworks/av/+/android16-release/services/camera/libcameraservice/device3/Camera3Device.cpp)
and [SurfaceTexture transform composition](https://android.googlesource.com/platform/frameworks/native/+/master/libs/gui/GLConsumerUtils.cpp).
The connected Xiaomi's existing user-started capture reported camera 0,
1280×720, sensor orientation 90°, sensor crop 3264×2448 and rotate-and-crop NONE.
NONE does not remove the buffer producer's separate orientation transform.
The actual old producer matrix was not captured from the running process;
The later physical run captured the producer transform and confirmed natural
proportions, as recorded below.

CameraTextureTransform now removes producer quarter-turn/mirror presentation
while retaining the texture crop/filtering inset and canonical GL Y flip.
Only then does the renderer apply the existing independent preview/output
rotation and mirror settings. This keeps rendering aligned with the raw sensor
coordinate convention already used by tap focus. Saved settings are not reset.
The correction applies before both phone preview and clean encoder rendering;
preview aids still never enter the output.

Windows inspection found Uniform preview scaling, bitmap dimensions equal to
decoded dimensions, and NV12 consumer resizing restricted to the two supported
16:9 modes. No Windows aspect-ratio change is necessary for this identified
producer-transform defect. OBS scene-specific stretching remains a separate
possibility if distortion survives the corrected clean output.

## Verification

- Offline Android `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` passed, with existing deprecation warnings.
- 45 JVM tests passed. Four new texture-transform tests cover the old 3.16
  circle deformation, normalization of all eight producer rotation/mirror
  combinations, asymmetric crop retention, idempotence/Y flip and invalid input.
- Seven CameraProductTest instrumented tests passed on Xiaomi 2405CPCFBG /
  Android 16. The added real EGL test checks centred, round synthetic circles
  simultaneously on a portrait 90×160 preview and landscape 128×72 encoder
  surface across all rotations/mirrors. Existing focus-coordinate/quadrant and
  clean-output-with-aids tests pass. These tests never open a sensor.
- The corrected debug APK is installed. Installation ends the old camera
  session; no new capture was started by the agent. Original preferences and
  physical camera imagery remain private/local; raw debug artifacts are under
  ignored build/ and are not attached to the GitHub issue.
- No reconnection, endurance or general diagnostic campaign was added. No
  roadmap item was marked complete.

## Initial physical-check plan

On resuming work on 2026-10-09, the user confirmed that this physical check
has not yet been performed. Local review confirmed 45 JVM tests with zero
failures/errors in the saved XML reports and a clean `git diff --check`.

Explicitly tap Start on the phone and aim at a round object or square sheet.
Confirm natural proportions in phone portrait/landscape, Windows and OBS.
Check a tap still targets the intended point, then Stop. If only OBS remains
distorted, compare its source transform with the Windows preview before changing
the camera pipeline. The issue remains open until that visual confirmation and
review/merge are complete.

## Physical proportions confirmed on the final APK

During the 2026-10-09 follow-up, the user explicitly pressed Start on the phone.
The newly built Windows desktop was running; OBS 32.2.2 opened the existing
Camylla source at NV12 1920×1080 / 30 FPS while the phone sent 1280×720 / 30 FPS.
OBS's previous-unclean-shutdown dialogue was dismissed with Run in Normal Mode;
no recording or broadcast was started.

The user initially reported black phone and Windows previews with an uncovered
lens facing an illuminated scene. Camera frames and decoded frames were flowing,
and no rendering exception was recorded. A temporary diagnostic APK compared
the original producer transform with its canonical form. The actual Xiaomi
producer was `[0,-1,0,0,-1,0,0,0,0,0,1,0,1,1,0,1]`, confirming a quarter-turn.
First-frame mean luminance was approximately 0.565 / 0.562 out of 255 for
original/canonical sampling: both were nearly black. This does not establish
the cause of the initial black output or prove a repair for that symptom.

The user subsequently reported that the image was visible and confirmed natural
proportions on phone portrait/landscape, Windows and OBS. The temporary diagnostic
was then removed in full; the renderer source matches the PR implementation.
Android unit tests and debug builds passed again, and all seven CameraProductTest
tests passed on the Xiaomi. The final APK was installed and reopened without
starting capture. After another explicit phone Start, the user confirmed:
“Oui, image et proportions correctes partout”. Thus proportions are physically
confirmed for the final, non-diagnostic APK on this configuration.

Private camera screenshots and unsanitized logs remain in ignored local build
artifacts. Following final-APK proportions confirmation, the user also reported
“tout marche, meme les controles :)”, accepting the camera controls on this
configuration; see the [final acceptance record](camera-pr44-retest-2026-10-09.md).
No new per-control measurements or wider device matrix are claimed. Review/merge
is handled separately; roadmap items may only be completed after merger.
