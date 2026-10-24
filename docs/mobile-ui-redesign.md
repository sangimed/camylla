# Android camera-first UI design

Status: follow-up implementation specification for [#59](https://github.com/sangimed/camylla/issues/59), informed by direct inspection of the five user-supplied DroidCam OBS screenshots and the source audit in [the prior-redesign diagnosis](validation/android-ui-redesign-diagnosis-2026-10-22.md).

## Product intent

Make the Android app immediately read as a camera console: a live image occupies
the working area, supported camera actions sit at its edges, and connection and
output facts stay visible without obscuring the image. Use the reference's compact
camera-first hierarchy and flat preference screens while retaining Camylla's
name, semantic colors, security flows, and original vector artwork. Do not change
Windows UI.

## Camera workspace

Treat the camera workspace as a dedicated screen, not a dashboard with three
large destination buttons above the preview. Camera remains the default. Put a
compact Camylla identity and connection/session entry in a shallow top rail;
open Settings from a gear/action on that rail and Connections from the peer/state
control. Back from either destination returns to Camera. The top rail collapses
when height is constrained.

### Landscape camera console

- Use an edge-to-edge dark canvas. Allocate roughly 18–22% of available width to
  a narrow leading status rail and the remainder to the preview. Keep the live
  image full-height, aspect-correct, and visually dominant.
- The status rail shows explicit pairing/session wording, trusted PC name when
  paired, and only observed output/network values. Keep the single stateful
  Start/Stop action at its reachable lower edge.
- Place compact, transparent framing/preview-aid actions along the upper preview
  edge. Place supported camera actions along its lower edge: camera, focus/AF,
  white balance, exposure, torch, and zoom as available. No opaque panel crosses
  the image center.
- On the Xiaomi target, 800dp landscape width must be sufficient for this split
  composition when the available height permits it. Use actual window bounds and
  a 700dp split threshold, with a height fallback for short landscape windows.

### Portrait and constrained camera workspace

- Use a one-line top rail, a full-width preview stage taking about 48–58% of the
  remaining height, and one compact horizontal quick-control rail immediately
  below it. Keep the single Start/Stop action fixed at the bottom and visible
  without scrolling.
- Collapse connection details into one labeled status line; open full peer and
  transport information in Connections. Keep secondary camera controls in a
  scrollable Camera controls surface.
- For tablet portrait, center a bounded column while allowing the preview to
  grow vertically; keep the preview above controls and status. Do not stretch
  preference rows into cards.
- At short heights or increased font scales, remove low-frequency edge actions
  into overflow/advanced controls before reducing the preview or hiding
  Start/Stop. All essential actions remain at least 48dp and text uses `sp`.

The supplied camera screenshot shows a live, actively streaming landscape state
with **Stop**. It does not show the stopped/unpaired camera screen or portrait
camera geometry. Camylla's SurfaceView is currently produced by the authorized
capture service only. Keep the viewport large in every state, but when no camera
buffer exists show a truthful pairing/ready/start prompt rather than a fake
image. Do not add a second camera owner or start transport from navigation. A
preview-only camera lifecycle is out of scope unless implementation proves it is
the smallest safe change required for the requested preview behavior.

## State and interaction model

| State | Camera screen treatment | Primary action |
|---|---|---|
| Not paired | Empty preview viewport with a concise “Connect a PC” prompt; no peer or stream claims | Connect a PC; Start disabled until a trusted peer exists |
| Paired and ready | Show trusted PC as paired/idle and selected mode; never imply a live feed | Start |
| Connecting / binding | Text status and progress; retain visible controls and clear session intent | Stop |
| Streaming | Live preview dominates; show observed peer/output/network state | Stop |
| Recovering | Amber recovery text, distinguish local camera from remote feed, offer retry only when the service supports it | Stop; Retry when available |
| Disconnected | Explicitly show stopped plus last error or paired-but-offline | Start, or the relevant recovery action |
| Error | Red semantic status plus actual cause and corrective action | Contextual action; Stop if a session remains active |

Use service and transport state, not saved pairing, to derive status. Treat
selected, requested, applied, and observed values separately. Surface only
capability-backed actions; report unsupported controls with their device reason.
Do not claim 60 FPS from a selected 60 FPS mode when observed delivery differs.
Keep phone-side Start authorization explicit.

## Camera action placement

| DroidCam screenshot element | Camylla implementation | Required treatment |
|---|---|---|
| Live camera preview and framing grid | `MainActivity` `SurfaceView`/focus overlay; service attaches preview | Expand to the dominant workspace; preserve aspect, tap focus, pinch zoom, and rotation-safe reattachment |
| Camera switch at lower edge | Existing camera catalog and selector | Compact camera action; choose only catalogued lenses |
| Video quality / target FPS | `CaptureService.Mode`, `CaptureModeCapability`, selected and observed capture stats | Keep supported 720p30, 1080p30, and capability-gated 1080p60 choices; show selected/effective/delivered values separately |
| AWB / EV / AF-C / zoom controls | `CameraControlsPanel`, `CameraQuickControls`, `CaptureService` camera descriptors | Promote supported controls to quick rail; actual callbacks, values, pending feedback, and unavailable reasons remain authoritative |
| Tap focus / focus state marker | `MainActivity` gesture handling, `FocusTargetView`, `FocusFeedback`, `CaptureService.tapFocus` | Keep tap-to-focus feedback over the image, pinch zoom, autofocus, manual focus and focus/AE/WB locks when descriptors expose them |
| Exposure, white balance, and torch | `CameraControlsPanel` descriptor-backed options in `CaptureService` | Keep supported EV/ISO/shutter/anti-flicker, WB mode/temp/tint, torch/strength in grouped controls; show applied, pending, or unavailable explicitly |
| Saved camera parameters | `CameraSettings` presets plus `CaptureService.applyPreset/savePreset/resetSettings` | Retain preset save/apply/delete/reset in Camera controls |
| Grid/crop/framing tools | Existing preview aids and settings | Small transparent edge actions; do not cover center |
| Wi-Fi/LAN endpoint and video stats rail | `CaptureService.stats`, selected peer, network progress | Show peer/session state plus effective resolution, codec, delivered FPS, and transport state only when observed |
| Stop | Existing explicit capture Stop | One prominent Stop while starting, binding, capturing, or recovering |
| Settings gear | Existing Settings category index | Compact camera toolbar action; category page opens on demand |
| Connection/pairing entry | `Connections` destination, `PairActivity`, `ProductIdentity` trusted desktop and key-rotation flows | Reachable from camera state; keep code/QR pairing, trust/forget/revoke and pending key recovery without conflating trust with stream state |

## Settings presentation

Use a title/back bar and full-width category index with original line icons,
large labels, and thin separators. Each category opens a titled detail page with
flat preference rows: label, value or explanatory subtitle, and an aligned
choice/switch/chevron. Use the screenshot's dense preference hierarchy rather
than rounded dashboard cards.

Keep Camylla destinations and map only existing functions:

- **Video:** supported resolution/FPS choices, current/effective output, and
  existing encoder controls.
- **Camera:** lens/mode selection, focus, exposure, white balance, image and
  orientation controls, preview aids, and presets.
- **Security:** pairing/trusted PCs, key rotation, and recovery entry points.
- **Diagnostics:** existing service, camera, encoder, and transport diagnostics.
- **Connections:** its existing dedicated pairing/trusted-peer workflow remains
  a top-level destination reachable from the camera state control.

Omit DroidCam-only Audio, Remote Control, Get Pro, and unsupported Camera2
options. Settings rows must operate existing callbacks; there are no placeholders.

## Visual system and accessibility

Retain Camylla's palette and semantics: anthracite canvas, flat charcoal
surfaces, thin separators, white primary text, muted secondary text, blue action
and focus, green healthy state, amber recovery, and red Stop/critical error.
Use the native system font and existing original symbols. Prefer flat surfaces,
small radii, clear dividers, and a 4dp spacing rhythm. Make essential controls
at least 48dp, primary capture controls at least 52dp, icon glyphs about 24dp,
and support font scaling through wrapping/scrolling. Icon actions need accessible
names; pairing, Start/Stop, and destructive actions keep visible text. Selected,
pressed, disabled, pending, unavailable, and error states must be distinguishable
by text/shape as well as color.

## Orientation and lifecycle

Base the layout on current available bounds and font scale, with distinct phone
portrait, phone landscape, tablet portrait, and tablet landscape compositions.
During configuration changes restore the selected destination and reattach the
new preview SurfaceView to the same foreground service. Do not start another
camera, encoder, transport, or authorization when rotating. Preserve issue #70's
existing active-session lifecycle behavior.

## Validation contract

The physical Xiaomi is 1600×2560 at 320dpi. Validate both portrait (500×800dp)
and landscape (800×500dp) on Android user 10, the prior Security Space target.
Record source commit, APK hash, package/version/versionCode, `pm path`, current
user, launcher Activity, process, permissions, and visible UI. Compare the new
screens against both the alpha6 baseline and the supplied reference images.
Exercise camera, connections, settings categories, quick controls, Start/Stop,
and rotation without changing capture/session ownership. Keep captures local and
delete them after the authorized visual review.
