# Camera controls and mobile UX — expanded #26 scope

Status: research and implementation brief, 2026-10-05. No camera-control
implementation or new hardware PASS is claimed. At the user's request, #26 now
covers a full camera-control experience for webcam use, including features that
the previous design deferred: tap focus, focus lock/manual focus, torch and
advanced exposure controls. The mobile interface is the primary camera surface;
Windows offers the same supported controls during a phone-authorized session.

## Research observations

These are documented product behaviors, not a measured ranking or hands-on
comparison. Camylla will use its own UI, code and assets.

| Reference | Observed pattern | Camylla design choice |
| --- | --- | --- |
| [DroidCam controls](https://droidcam.app/help/) and [feature matrix](https://droidcam.app/pro-info.html) | Camera controls, extra available lenses, saved camera parameters and desktop remote controls | Keep camera adjustments available locally and remotely; reflect actual device capabilities and remember valid settings |
| [Camo settings](https://camo.com/support/camo/adjusting-video-settings) | Device/lens controls, autofocus/manual focus slider, organized image-adjustment panels | Group advanced controls by function, with Auto/Manual and current applied values visible |
| [Blackmagic Camera](https://www.blackmagicdesign.com/products/blackmagiccamera) | Interactive preview HUD, tap focus, hold focus/exposure lock, hideable overlays and adjustment palettes | Large preview, direct manipulation and a compact toolbar; hide advanced panels while retaining Stop |
| [Pixel Camera help](https://support.google.com/pixelcamera/answer/14106982?hl=en) | Focus target and lock, quick zoom choices, Pro sliders | Visible focus feedback, reachable quick zoom, explicit lock/unlock and reversible adjustments |

The following layout and interactions are our proposed synthesis, not claims
that one competitor has the objectively best interface.

## Mobile layout

- The live preview dominates the screen during capture. Opening the app, showing
  saved preferences or reconnecting without a current phone Start never starts
  camera capture or transmission. An idle screen can show the saved setup.
- Persistent, unmistakable Start/Stop and current connection/capture state;
  streaming, recovery and stopped appearances are distinct. Never present a
  stale frozen image as current. Diagnostics stay behind a separate panel.
- Quick toolbar: lens/camera, zoom, focus Auto/Lock, torch, video mode. Labels,
  numeric values and active/locked states accompany icons; no icon-only guessing.
- A tap on the picture sets a supported focus/metering target. Show acquisition,
  achieved/failed focus and lock state. Long press may lock focus/exposure, with
  an equally accessible explicit Lock/Unlock button; no gesture-only feature.
- Pinch zoom and a visible slider/quick lens choices use real supported ranges.
  Do not label digital zoom as an optical camera switch.
- A bottom sheet in portrait and a side panel in landscape exposes Focus,
  Exposure, White balance, Image and Output groups. At most one adjustment panel
  obscures the preview at a time; advanced controls are progressively disclosed.
- Pro mode is a presentation toggle, not a premium tier. Sliders show applied
  values/units, Auto/Manual mode, reset and pending/error state.
- Large accessible touch targets, readable contrast, screen-reader labels and
  keyboard-accessible Windows equivalents. Locks and errors use text/icons as
  well as color. Portrait/landscape and tablet layouts must remain usable.
- Pairing remains scan-and-complete; camera features add no pairing approval
  steps. Stop stays reachable while controls, recovery or an error are displayed.

## Required control families

1. **Cameras and modes:** front/rear and additional physical/logical lenses
   actually exposed by Android, including ultrawide/telephoto when selectable;
   negotiated 720p30/1080p30, zoom range/presets and per-lens/mode capabilities.
2. **Focus:** continuous autofocus, tap focus, visible AF result, lock/unlock and
   manual focus distance when supported, with return to Auto. A fixed-focus lens
   is labelled as such. Do not claim a held metering target proves lens lock.
3. **Light:** continuous torch on/off, plus brightness only when the platform
   exposes it. This is the useful video equivalent of the user's requested
   flash; no still-photo strobe is required. Confirm the real applied state.
4. **Exposure:** automatic exposure, EV compensation with actual range/step,
   exposure lock/unlock, manual ISO and exposure time when exposed; linked Auto
   controls make mutually exclusive settings clear. Display cadence constraints
   when shutter duration conflicts with the selected FPS; no silent FPS promise.
5. **White balance:** Auto, supported presets, lock and manual temperature/tint
   or gains where available, with reset. A color-processing emulation must be
   explicitly distinguished from a sensor setting and must not pretend hardware
   supports manual WB.
6. **Image:** brightness/contrast/saturation and reset, explicitly distinguishing
   software adjustments from native sensor controls; supported stabilization and
   anti-flicker settings, with real effects/capability constraints disclosed.
7. **Output and framing:** portrait/landscape rotation/orientation lock and
   independent preview/output mirroring; optional hideable grid, histogram,
   overexposure zebras and focus-peaking aids. These aids are part of the expanded
   task and are preview-only by default, never burned into the OBS/webcam image.
8. **Preferences:** preferred trusted device, selected lens/mode and valid camera
   and transform settings; named reusable presets, reset-to-default and visible
   revalidation on device/lens/mode change. Never save a live capture authorization.

All families belong to #26; staged PRs do not drop advanced controls from the
acceptance scope. A hardware-dependent control may be unavailable on a particular
device only after capability/runtime validation, with an explicit reason.

## Capability and state contract

[CameraX configuration](https://developer.android.com/media/camera/camerax/configuration)
documents focus/metering, zoom, exposure compensation and torch APIs, including
failed torch requests on cameras without a flash unit. Its best-effort metering
behavior means Camylla must verify support/result instead of treating every
requested AF/AE/AWB action as fully applied. Camera state can reset on close, so
saved values must be revalidated and applied intentionally.

[CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
and [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
provide per-camera capability, range, request and result information for manual
sensor/focus controls, AE/AWB locks and related features. Availability is not
uniform across front/rear lenses or vendors. Use CameraX where supported and
reviewed Camera2 interop or a camera adapter for remaining native controls;
do not promise a capability based solely on phone model or API level.

- Negotiate capabilities and current applied settings over the authenticated
  channel, bound to peer/session and camera generation. Track requested,
  pending, applied, rejected and unavailable states.
- Use bounded request IDs and acknowledged commands. Acknowledgement means the
  camera applied the setting or explicitly rejected it, not merely that the PC
  received a packet. Asynchronous AF completion is distinct from request receipt.
- Android owns canonical camera state; local and desktop changes converge to the
  same applied state. Define ordering/coalescing, bounded slider update traffic,
  timeout/cancellation and rejection of obsolete-session/generation commands.
- Map tap coordinates through preview crop, rotation and mirroring into sensor
  coordinates. Letterbox/pillarbox areas are not camera targets. Test front-camera
  mirror and different mobile/Windows preview transforms.
- Camera/mode reconfiguration creates a new generation, clears pending frames and
  applies fresh codec configuration/keyframe before preview/webcam publication.
  Invalid locks/manual values are visibly revalidated, never silently carried
  into an incompatible lens.
- Phone Stop cancels commands/recovery, releases the camera and turns torch off.
  No automatic torch reactivation after app restart or camera change. Reconnect
  during an authorized capture may restore compatible image preferences; a new
  session still requires phone Start and a fresh explicit torch-on action.

## Delivery order and validation

1. Capability/state contract and camera adapter, with a mobile preview/toolbar
   prototype and a control-state map before broad implementation.
2. Acknowledged lens/mode/zoom changes and generation-safe reconfiguration.
3. Focus, locks, torch and exposure/WB/manual controls; one consistent UI/state
   path for mobile and authenticated Windows commands.
4. Image adjustments, preview aids, transforms, presets and persistence.
5. Focused parser/state/coordinate tests and physical capability matrix.

Use the S21 Ultra when available for variable-focus, flash and multiple-lens
validation; the Xiaomi tablet is an additional real limited-capability case.
Record hardware/Android/Windows/build versions, actual capabilities, requested
and delivered mode/FPS, applied controls, unsupported reasons and errors.
Verify near/far focus and lock stability, torch physical on/off, exposure/WB
effects, lens/mode switching, transformed tap targeting, OBS output without
preview overlays, preset/restart revalidation and Stop authority. Preserve
failures; visual checks require user confirmation. #34 owns full device reboot.

## Boundaries

This expansion does not add recording/photos, RAW export, audio, cloud, USB,
multi-camera composition or unattended start. It does not make 4K/60 FPS a gate.
Device-specific LUT pipelines, rack-focus automation and scene/background effects
are separate proposals; the required camera controls above remain in scope.
