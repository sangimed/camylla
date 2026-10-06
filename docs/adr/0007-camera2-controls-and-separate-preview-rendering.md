---
status: implemented, physical acceptance pending
---

# Camera2 controls and separate preview rendering

Issue #26 requires manual sensor controls, capture-result acknowledgements,
per-lens discovery, physical camera selection and independent preview/output
transforms with preview-only monitoring. The original CameraX direct encoder
surface proved baseline capture, but does not provide the full ownership and
result confirmation needed by this control path.

Use a Camera2 adapter with one SurfaceTexture camera target. An EGL renderer
feeds the MediaCodec surface and an independently transformed phone preview.
Sensor values are confirmed from CaptureResult; software values are confirmed
after rendering. The same inverse rotation/mirror/crop convention maps focus
coordinates. Overlays are drawn only in the preview pass.

## Alternatives

Keeping CameraX with interop would preserve its lifecycle abstractions but mix
request ownership with advanced physical-lens and capture-result handling.
Separate camera targets for preview and encoder would reduce GL work for a
simple preview but would not implement software image adjustments and output
transforms. Separate independent capture/processing pipelines would add sensor
bandwidth and inconsistent control state.

## Consequences

The adapter must own foreground-service lifecycle, errors, request ordering,
capability checks, session recreation and resource release. Device metadata is
insufficient evidence that a session combination works. Physical lens result
metadata, 30 FPS cadence, GPU cost, focus transforms and torch release need
hardware validation. The previous CameraX baseline evidence does not certify
this new path. See the [checkpoint and acceptance matrix](../validation/android-windows-m4-5.md).

The phone remains the capture authority. Saved parameters do not authorize
capture or restore light/locks. Source changes preserve authenticated control,
invalidate pending commands and cached frames, renew the video ticket and
require fresh codec configuration/keyframe.

Physical tablet tests found that the native IPC source rejected a capture size
different from the consumer's negotiated size. The source now resizes validated
NV12 Y and paired UV planes between the two baseline 16:9 modes, preserving
consumer dimensions and cadence through phone mode changes. Nearest-neighbour
sampling is deliberately simple and bounded; upscaling adds no captured detail.
Both resize directions, padding and bounds have tests, and OBS at fixed 1080p
received the real 720p phone stream after deployment. Device/visual acceptance
remains distinct from this engineering evidence.

Relevant platform contracts: [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics),
[CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest),
[CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult)
and [OutputConfiguration](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration).
