# Android camera-first UI design

Status: implementation specification for [#59](https://github.com/sangimed/camylla/issues/59).
Reference findings are recorded in [DroidCam Android UI research](research/droidcam-android-ui-2026-10-21.md).

## Product intent

Give Camylla a camera-first experience that feels familiar to users of the current
DroidCam OBS Android app: a large live image, dense camera-context controls, clear
connection/output information, and direct access to grouped settings. The supplied
screenshots determine the reference layout. Camylla retains its own identity and
original icons. The existing Camera, Connections, and Settings destinations remain.

## Camera workspace

- Keep the active preview as the largest element. Landscape uses a preview-led
  console with compact connection/output details at the leading edge, selectors and
  high-use controls near the preview, and the capture action at a fixed reachable
  edge. Do not cover the center of the image with decorative panels.
- Portrait stacks the preview over a compact, horizontally scrollable quick-control
  strip. Connection details can collapse; Start/Stop stays visible without scrolling.
- Use available width and height, not orientation alone, to choose stacked, split, or
  compact layouts. On tablets, extra width expands the preview before control rails.
  Scroll low-priority controls at short heights and large font scales.
- Keep Camera, Connections, Settings navigation compact in the camera context; use
  preference-style full-width rows and a category index for settings.
- Camera/lens, video mode, zoom, focus, autofocus/lock, torch, exposure, white balance,
  image, orientation/mirroring, preview aids, and presets remain capability-aware.
  Frequent actions stay on Camera; specialist controls are grouped under Camera
  controls or the relevant settings category.

## State and lifecycle

The visible capture action is a single stateful action: **Start** while stopped and
ready, **Stop** while starting, binding, capturing, or recovering. Pairing trust is
not a live session. Starting, streaming, recovery, stopped, and error states come
from the existing service and transport state, with words/icons as well as color.
Only observed camera values are presented as applied; pending and unavailable states
retain their explanation.

Activity recreation for rotation rebuilds the presentation against current available
bounds and reattaches the preview to the existing foreground service. It must not
start another camera, encoder, transport, or authorized session. Pairing, security,
camera lifecycle, authentication, and Windows protocol behavior stay behind their
existing contracts.

## Visual system and components

Retain Camylla's `MobileUi` palette and semantic meanings: anthracite backgrounds,
flat dark surfaces, fine separators, white primary text, muted support text, blue
actions/focus, green healthy state, amber recovery, and red Stop/critical errors.
Use the native system font and scalable `sp`, 4dp-based spacing, 48dp minimum hit
targets, original vector symbols, and restrained corner radii. Avoid dashboard cards,
decorative gradients, copied third-party assets, and unlabeled essential actions.

Settings use DroidCam's category and preference-row organization: icon/title, current
value or subtitle, trailing switch/chevron, and thin dividers. Map only existing
Camylla capabilities; omit Audio, Remote Control, Pro commerce, and unsupported
Camera2 options. Pairing/security stays in Connections and Settings as already
implemented. No row is a placeholder.

Control rows show label, input, camera-confirmed value, unit, pending state, and
device-provided unavailable reason. Choice dialogs, sliders and toggles preserve
existing callbacks and descriptor validation. Start/Stop and pairing/destructive
actions keep visible text.

## Existing capability mapping

| Current Camylla behavior | Redesigned location |
|---|---|
| `SurfaceView` preview and focus markers | Dominant Camera workspace; preserve safe inset and aspect behavior |
| Camera/lens and video mode | Compact selectors next to the preview |
| Zoom, torch, focus centre/autofocus/lock | Camera quick controls |
| Focus distance, exposure/light, white balance, image, orientation, mirroring, preview aids | Grouped Camera controls/settings, based on camera descriptors |
| Preset save/apply/delete and reset | Advanced Camera controls |
| Pairing code/QR, trusted desktop, forget/revoke | Connections, preserving existing confirmation/security flows |
| Key rotation, recovery and diagnostics | Settings, in clear Security and Diagnostics groups |
| Explicit phone-authorized capture | One prominent Start/Stop action |

## Acceptance

- Landscape active camera is visibly preview-dominant with connection/output context
  and compact reachable camera controls.
- Portrait, narrow landscape, tablet/wide window, and increased font scale reflow
  without overlap or hidden Start/Stop.
- Paired-ready, starting, streaming, recovering, disconnected and error are never
  conflated; capture never starts from navigation, pairing, settings, or rotation.
- Existing capabilities and feedback remain functional; unsupported values are not
  simulated.
- Repeated rotation preserves the active service session and returns the preview.
- Settings use category and flat preference rows without removing security, pairing,
  diagnostic, preset, or advanced camera functions.
- Runtime visual evidence is captured only with explicit user authorization. OBS is
  the release consumer for focused streaming checks; no timed endurance requirement.
