# Camylla product and interface design

This file is the visual and interaction source of truth for Camylla. It is written
for both humans and coding agents: new UI should reuse these tokens and patterns
instead of inventing a new visual language per feature.

Camylla turns an Android phone into a webcam for Windows. The phone owns camera
capture and must explicitly start every capture session. The Windows app pairs
with the phone, shows the live preview, exposes camera controls and publishes the
virtual webcam. The interface should feel like a focused professional camera
utility: dark, compact, calm and precise rather than like a web dashboard.

## Design direction

Camylla uses a **dark professional workspace** language inspired by mature
creative tools and compact technical utilities:

- the live image is the visual focus;
- panels are structural surfaces, not oversized cards;
- information density is intentionally higher on desktop;
- Android keeps large touch targets while reducing decorative chrome;
- borders and tonal surface changes create hierarchy instead of shadows;
- blue is the Camylla action accent, not a decorative gradient;
- red is reserved for stop, destructive actions and errors;
- green communicates a healthy connected/streaming state;
- amber communicates warning/recovery state;
- diagnostics may use monospace text, ordinary product UI should not;
- avoid glassmorphism, neon glow, large gradients and excessive rounding.

The Windows and Android clients share the same visual identity but do not force
identical geometry. Desktop is a dense workspace; mobile is a touch-first camera
controller.

## Shared color tokens

These values must stay aligned between Android `MobileUi.kt`, Windows `App.xaml`
and this file.

| Token | Value | Use |
| --- | --- | --- |
| Background | `#18181B` | Main application canvas |
| Surface | `#222225` | Panels and contained sections |
| Raised | `#2B2B2F` | Buttons, inputs and elevated controls |
| Border | `#3A3A3F` | Dividers and panel outlines |
| Primary text | `#F2F2F3` | Primary labels and values |
| Muted text | `#A1A1AA` | Help, metadata and secondary status |
| Action blue | `#59A6FF` | Primary actions, focus and selected state |
| Connected green | `#4DD49B` | Healthy connected/streaming state |
| Warning amber | `#F3A94B` | Recovering, degraded or attention state |
| Stop/error red | `#FF6B75` | Stop, destructive actions and critical error |

The camera preview itself stays neutral black. Do not tint video content with the
application palette.

## Experience principles

- **Preview first.** The camera view receives the most space on both platforms.
  Keep Start/Stop and connection state reachable without hiding the shot.
- **Simple first on Android.** Android opens in Simple mode by default. A visible
  Simple/Pro switch remembers the user's choice. Pro reveals specialist camera
  controls without restarting capture.
- **Inspector, not dashboard, on Windows.** Camera controls, pairing and security
  live in a compact right-hand inspector beside the preview. Do not turn each
  value into an independent card.
- **Progressive disclosure.** Frequent controls stay visible; specialist controls,
  security and diagnostics use expandable sections.
- **Make state explicit.** Distinguish idle, connecting, streaming, recovering,
  pending, applied, rejected, unavailable and stopped with readable text. Never
  rely on color alone.
- **Respect capture authority.** Pairing, presentation changes and opening or
  closing control panels never silently start or stop capture. Only explicit
  Start/Stop actions change capture ownership.
- **Prefer stable geometry.** Status changes should not make the main preview jump
  around. Reserve space for important state and recovery guidance.

## Android

Android uses the code-native design system in
[`MobileUi.kt`](src/android/app/src/main/java/org/camylla/proof/MobileUi.kt).
Use that object instead of ad-hoc Android theme colors for product surfaces.

### Geometry

- minimum touch target: **48 dp**;
- control radius: **8 dp**;
- panel radius: approximately **10 dp**;
- use a 4 dp spacing rhythm, commonly 4 / 8 / 12 / 16 / 20 dp;
- use dp for geometry and sp for text;
- avoid large floating cards around every group of controls.

Buttons use Android ripple feedback. Primary and selected actions use the action
blue. Secondary actions use the raised surface plus a subtle border. Destructive
controls use red. Toggle state must remain visually obvious after the press.

`MobileUi.text` should be used for text placed on Camylla surfaces,
`MobileUi.input` for text entry, `MobileUi.button` for product buttons and
`MobileUi.seekBar` for sliders. Native platform dialogs may keep their platform
surface and foreground together; do not mix a light dialog surface with forced
white Camylla text.

### Composition

Portrait places the live preview above the controls. Landscape and wide tablet
layouts place the preview beside a supporting control panel. Reflow based on
available space rather than stretching controls across the screen.

The primary mobile navigation remains Camera / Connections / Settings. Camera is
for live work, Connections owns pairing and trusted PCs, Settings owns security
and diagnostics.

## Windows

Windows uses WinUI and the shared resources declared in
[`App.xaml`](src/windows/desktop/App.xaml). The application intentionally requests
a dark theme so dynamically-created camera controls remain coherent with the
workspace.

The main desktop composition in
[`MainWindow.xaml`](src/windows/desktop/MainWindow.xaml) is:

1. **compact application chrome** — Camylla identity and the separated Exit action;
2. **preview workspace** — stream state, recovery guidance and a large neutral
   camera preview;
3. **right inspector** — live camera controls, pairing and expandable security;
4. **bottom diagnostics** — available on demand without competing with the shot.

The default inspector width is approximately **320 px**. Desktop controls are
compact rather than touch-sized: standard buttons are approximately **36 px**
high with **6 px** corners. Panels use thin borders and small radii. Section
labels are small uppercase labels with muted text; content labels remain normal
sentence case.

The preview should grow before the inspector grows. When adding a desktop feature,
prefer another row in the relevant inspector section or an expander over adding a
new top-level card.

Use the built-in system UI font for regular interface text. Diagnostics may use
`Consolas`/monospace because alignment and technical scanning are useful there.

## Interaction and status

- A press receives immediate visual feedback; asynchronous operations then show
  pending and confirmed/rejected results near the affected control.
- Primary navigation and mutually exclusive choices have one clear selected state.
- Errors explain what failed and the next useful action.
- Reserve modal confirmation for destructive trust/key changes and other
  consequential decisions.
- Connection and camera capability text must reflect observed device state. Never
  imply a control is available or applied merely because a request was sent.
- Pairing opens on an eight-digit, one-use code; scanning the QR remains an
  alternative. The PC user explicitly approves each pairing. Pairing never starts
  capture.

### Semantic color rules

Color supports text; it never replaces it.

- **Blue:** action, focus, selection.
- **Green:** connected/healthy only.
- **Amber:** recovery/degraded attention states.
- **Red:** stop, destructive action, critical failure.

Do not use red as the Camylla brand accent because it must remain immediately
recognizable for Stop and Error.

## Accessibility

- Keep Android touch targets at least 48 dp.
- Preserve visible keyboard/D-pad focus and screen-reader descriptions.
- Maintain scrollability at large font scales.
- Do not communicate status through color alone.
- Keep high contrast between text and all custom dark surfaces.
- Preserve the camera preview's automation/accessibility description and alternate
  controls for gestures such as focus and zoom.

## Design references and source documents

- [Android UI redesign decisions](docs/mobile-ui-redesign.md) — screen model,
  Simple/Pro behavior and adaptive layouts.
- [Camera controls and mobile UX](docs/camera-controls-ux.md) — camera-control
  scope, capability/state behavior and preview interactions.
- [Tablet UI/UX research](docs/research/android-professional-tablet-ui-ux.md) —
  platform guidance and professional video-app references.
- [Product living design](docs/design.md) — product boundaries, architecture,
  protocol and milestones.
- [Validation records](docs/validation/) — observed behavior and acceptance
  evidence.

When changing the visual system, update the platform token implementation and
this file together. Visual changes must not silently alter capture ownership,
pairing semantics, transport behavior or recovery behavior.
