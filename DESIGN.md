# Camylla product and interface design

This is the short, editable design baseline for Camylla. It captures the product
decisions and visual rules that should guide Android and Windows UI changes. It
is an index and design-system summary, not a replacement for the detailed
product, protocol, or validation documents linked below.

## Product

Camylla turns an Android phone into a webcam for Windows. The phone owns camera
capture and must explicitly start every capture session. The Windows desktop
app pairs with the phone, shows its preview and exposes the webcam to the target
consumer, with OBS as the release validation target. The interface should feel
like a focused camera tool: keep the shot and the current stream state easy to
read, keep frequent controls close, and progressively reveal specialist
settings.

Android phones and tablets and Windows x64 are the v1 surfaces. iPad and
professional camera apps are references for interaction and hierarchy only;
they are not supported product targets and their visual identity or assets must
not be copied.

## Experience principles

- **Simple first.** Android opens in Simple mode by default. A visible, explicit
  Simple/Pro switch remembers the user's choice. Pro reveals advanced camera
  controls without stopping or restarting capture.
- **Preview first.** The camera view gives the live preview the most space. Keep
  Start/Stop obvious and reachable while controls or diagnostics are open.
- **Separate destinations.** Camera is the frequent workspace; Connections owns
  QR pairing and trusted PCs; Settings & security owns keys and diagnostics.
- **Adapt the composition.** Portrait places the preview above controls.
  Landscape and wide tablet windows place the preview beside a supporting
  controls panel. Reflow content for available space and font scale; do not just
  stretch every control.
- **Make state explicit.** Distinguish idle, connecting, streaming, recovering,
  pending, applied, rejected, unavailable, and stopped states with readable text
  and persistent selection where appropriate. Do not rely on color alone.
- **Respect capture authority.** Pairing, presentation changes, and opening or
  closing control panels do not silently start or stop capture. Only explicit
  Start/Stop actions change capture ownership.

## Android visual system

Android uses a code-native dark palette defined in
[`MobileUi.kt`](src/android/app/src/main/java/org/camylla/proof/MobileUi.kt).
Use that object as the source of truth rather than Android theme defaults.

| Token | Value | Use |
| --- | --- | --- |
| Background | `#101319` | App canvas |
| Surface | `#1A2029` | Cards and contained areas |
| Raised surface | `#252D38` | Standard controls and raised elements |
| Primary text | `#F4F7FB` | Labels and values on dark surfaces |
| Muted text | `#B6C2D1` | Supporting text and secondary status |
| Action blue | `#59A6FF` | Primary action, focus, and active selection |
| Connected green | `#54D69A` | Positive connection state |
| Stop/error red | `#FF6E78` | Stop action and critical error |

Use `MobileUi.text` for every `TextView` placed on a custom dark surface,
`MobileUi.input` for text entry, `MobileUi.button` for product buttons, and
`MobileUi.seekBar` for sliders. Do not leave text color to the light Android
theme: its default dark text becomes unreadable over Camylla's charcoal
surfaces. Native platform dialogs may retain their platform surface and default
text together; do not mix dark text with a custom dark background.

Buttons have a 12 dp corner radius, at least a 48 dp touch height, visible
separation from neighboring buttons, and Android ripple press feedback. The
blue fill marks primary actions and selected destinations or toggles; raised
surfaces mark secondary actions. Toggle state remains visible after the press.
Sliders use the same blue action accent and show their readable applied value
and unit in a nearby high-contrast label.

Use dp for geometry and sp for text. Keep controls comfortably separated and
group related actions with spacing and section labels. Simple mode keeps text
labels alongside controls. Preserve screen-reader descriptions, visible focus
for keyboard/D-pad input, scrollability at large font scales, and accessible
touch targets.

## Windows visual direction

Windows uses native WinUI controls and a clean desktop hierarchy: application
state and recovery guidance above a large preview; camera controls and pairing
below or in expandable sections; security and diagnostics outside the primary
setup path. Keep the same terminology, capability reporting, and applied camera
state used on Android. Use WinUI theme resources for foreground/background
pairs instead of hard-coded text colors where a theme surface is involved.

## Interaction and status

- A press receives immediate visual feedback; asynchronous operations then show
  pending and confirmed/rejected results inline near the affected control.
- Primary navigation and mutually exclusive choices have one clear selected
  state. Toggles show both their state and action, such as On/Off or Lock/Unlock.
- Errors explain what failed and the next useful action. Reserve modal
  confirmation for destructive trust/key changes and other consequential
  decisions.
- Connection and camera capability text must reflect observed device state.
  Never imply a control is available or applied merely because a request was
  sent.

## Design references and source documents

- [Android UI redesign decisions](docs/mobile-ui-redesign.md) — screen model,
  Simple/Pro behavior, and adaptive layouts.
- [Camera controls and mobile UX](docs/camera-controls-ux.md) — camera control
  scope, capability/state behavior, and preview interactions.
- [Tablet UI/UX research](docs/research/android-professional-tablet-ui-ux.md) —
  primary-source Android/Apple guidance and professional video-app examples.
- [Product living design](docs/design.md) — product boundaries, architecture,
  protocol, and milestones.
- [Validation records](docs/validation/) — observed behavior and acceptance
  evidence. A design requirement is not considered validated until evidence is
  recorded for the relevant device and workflow.

When changing the visual system, update the relevant implementation and this
summary, then add implementation-specific details to the corresponding screen
or control document. Record what was actually tested separately from intended
design behavior.
