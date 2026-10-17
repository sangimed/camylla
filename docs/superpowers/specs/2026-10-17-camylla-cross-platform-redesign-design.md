# Camylla cross-platform UI redesign

Status: approved design direction; implementation plan pending review.

## Purpose and scope

Camylla turns an Android phone into a webcam for Windows. This redesign makes the
existing working camera, pairing, transport, recovery, camera-control and virtual-
camera flows feel like one mature professional utility. It changes presentation and
interaction hierarchy only; it does not change capture authority, protocol, camera
capability discovery, pairing semantics, transport, or recovery architecture.

The root `DESIGN.md` remains the product design contract. The implementation must
update it with the accepted decisions in this document before or alongside platform
work, never afterwards as a retrospective.

## Audit of the current contract

### Keep

- Dark, restrained professional workspace; neutral-black video content; preview-first
  hierarchy.
- Structural tonal surfaces and borders instead of dashboard cards, shadows, glass,
  gradients, glow, or excessive radii.
- Shared semantics: blue action/focus/selection; green healthy connection; amber
  recovery; red Stop, destructive and critical failure.
- Android touch-first geometry and Windows compact preview-plus-inspector workspace.
- Android Simple-first presentation, progressive disclosure, explicit state language,
  and phone-only Start/Stop capture authority.

### Strengthen

- Treat shared color names as semantic API: `background`, `surface`,
  `surfaceRaised`, `border`, `textPrimary`, `textMuted`, `accent`, `success`,
  `warning`, and `danger`.
- Define interaction states, status anatomy, empty/recovery treatment, component
  grouping, typography, icon use, visible focus, scalable overlays, and platform
  responsive behavior.
- Make device state—not request submission—the source for selected/applied control
  presentation.

### Change

- Android composition responds to available content bounds rather than physical
  orientation alone.
- The stateful one-label Simple/Pro button becomes an explicit exclusive
  `Simple | Pro` selector.
- The Android unpaired overlay becomes responsive and uses the normal panel radius;
  it is not a 360dp, 20dp-radius floating card.
- The Windows 320px inspector becomes a bounded normal-size inspector with explicit
  compact and narrow window fallbacks.

### Missing from the existing contract

- Exact type hierarchy and non-color tokens.
- Default, hover, pressed, focus-visible, selected, disabled, pending, confirmed,
  unavailable, warning and error state rules.
- Android overlay and adaptive layout rules; Windows responsive, keyboard, DPI and
  text-scaling policy.
- Status/empty/recovery anatomy, icon rules, and accessibility acceptance checks.

## Shared visual system

Existing colors remain unchanged. `#59A6FF` uses dark `background` foreground in a
filled primary/selected treatment. Video itself remains black/untinted.

| Token | Android | Windows | Meaning |
| --- | ---: | ---: | --- |
| `space-1` | 4dp | 4px | Fine separation |
| `space-2` | 8dp | 8px | Internal gaps |
| `space-3` | 12dp | 12px | Control/panel padding |
| `space-4` | 16dp | 16px | Screen/group separation |
| `space-5` | 20dp | 20px | Major mobile separation |
| `radiusControl` | 8dp | 6px | Controls and inputs |
| `radiusPanel` | 10dp | 6px | Structural surfaces |
| `controlHeight` | 48dp minimum | 36px standard | Interactive controls |

Use the native system UI font. Product identity is compact (20sp Android / 16px
Windows); Android page titles are 24sp and Windows contextual titles are 17px;
section labels are 12sp Android or 11px tracked uppercase Windows; ordinary labels
are 16sp Android / 14px Windows; support text is 14–15sp Android / 12px Windows.
Only diagnostics may use monospace.

Every interactive component supports applicable default, hover (Windows), pressed,
focus-visible, selected, disabled, pending, confirmed, unavailable, warning and
error states. State uses text and/or icon as well as color. Pending retains the
requested value and says “Applying…” locally; confirmation is driven by observed
device state; unavailable has an adjacent reason.

Status has fixed anatomy: **semantic indicator + concise state phrase + optional
observed detail + contextual recovery action**. It occupies a reserved location so
the preview does not jump between idle, streaming and recovery.

Buttons have one local primary action at most. Secondary buttons use raised surface
and border. Stop can use filled danger; revoke/forget stays danger-outline until
confirmed. Icons are platform-native/vector, 20–24dp Android or 16–20px Windows,
with labels for meaningful actions and accessible names (and Windows tooltips for
icon-only controls). Emoji are prohibited.

## Android information architecture

Camera, Connections, and Settings remain the primary navigation. Camera is active
work, Connections owns pairing and trusted PCs, and Settings owns security and
technical diagnostics.

### Adaptive camera workspace

Measure available bounds after window/system insets. Use named constants:

- compact content: under 600dp;
- side-by-side: at least 840dp wide and 540dp high, with a 520dp minimum preview;
- side inspector: 280–320dp, capped at 320dp;
- wide content: 1000dp or more, with extra width going to preview rather than
  controls;
- narrow height: under 540dp.

Compact portrait places a dominant preview above scrollable controls. In usable
medium/wide layouts the preview and a 280–320dp supporting inspector sit side by
side. At narrow height, retain preview, state, capture action and only one quick
row; specialist controls open in a drawer/full page rather than consuming the shot.
Stop is visible in every active/recovery arrangement.

Camera order is stable status band; preview workspace (including only minimal focus,
histogram and focus/zoom overlays); camera/video selectors; explicit `Simple | Pro`
selection; quick controls; one capture-authority action. Simple exposes Zoom and
Torch. Pro adds supported focus controls and grouped disclosure sections: Focus,
Exposure and light, White balance, Image, and Output and monitoring. Compact layouts
allow one advanced group open at a time. Rows display label, observed value/unit,
pending state, and unavailable reason.

Inactive paired state exposes blue **Start camera**. Starting, binding, capturing,
or recovering exposes red **Stop camera** in the same location. Retry is a secondary
recovery action next to status or below Stop, never an equal sibling. Presentation,
pairing, and opening controls never change capture.

Connections uses a persistent status band, trusted-PC selection/state, pairing, then
the confirmed destructive forget action. Pairing uses the existing flow but its
contextual overlay/activity content must be scrollable, `min(available width -
32dp, 480dp)` wide, 16dp compact margins, and a 10dp panel radius. It never starts
capture. Settings puts security and key recovery first; diagnostics are a collapsed
technical section at the end.

## Windows information architecture

The desktop remains a professional workspace: compact chrome, stable status band,
neutral-black preview stage, compact inspector, and collapsed bottom diagnostics.
The preview grows before every panel.

At 960 effective pixels or more, use flexible preview plus a 304–336px inspector
(320px target). At 720–959 effective pixels, inspector is collapsed by default and
opened by an accessible Inspector command. Below 720 effective pixels, the inspector
is an overlay pane with explicit open/close, Escape closure, focus return, and a
single shared content host; it must not remain a squeezed permanent column. When
available height is under 620 effective pixels, diagnostics remain collapsed and
inspector controls scroll independently. Evaluate effective available bounds, not
physical screen resolution, to respect snapping, DPI and text scaling.

Inspector order is Camera (live state and grouped controls), Connection (pair,
trusted phone, revoke, help), then collapsed Security. Camera groups follow Lens and
framing, Focus and exposure, Image, and Output and monitoring where capability data
supports them. Pairing stays a native `ContentDialog`, with scrollable content and
all code, QR, close and regeneration actions reachable at high text scale.

Keyboard order is chrome, status recovery, preview, inspector, then diagnostics.
Enter/Space operate buttons and expanders; sliders/selectors retain native keyboard
operation; Escape closes non-destructive dialogs/overlay and restores focus. Custom
surface controls retain an intentional visible keyboard focus state.

## Accessibility and non-goals

Keep Android 48dp targets, readable status text, gesture alternatives, TalkBack
names and scrollability at 200% font scale. Windows supports system text/DPI scaling
to 200% without clipping essential action or inspector content; Narrator state,
logical focus and overlay focus management remain intact. Verify 4.5:1 normal-text
contrast on custom dark surfaces and selected/semantic combinations.

This work deliberately excludes backend/protocol/capability changes, a brand or
logo exercise, decorative imagery/effects, copied competitor layouts, and default
screenshot capture. Runtime visual evidence is gathered only with explicit user
authorization.

## Acceptance and validation

- Android uses available-space classes, preserves Stop in active/recovery states,
  never shows Start and Stop together, and preserves selected Simple/Pro state
  without capture restart.
- Android pairing is usable at narrow width and 200% text scale; Pro rows expose
  applied/pending/unavailable states.
- Windows keeps preview-first normal layout; compact/narrow widths avoid a cramped
  two-column inspector; pairing and keyboard focus remain usable at scaling.
- Both platforms use identical semantic token meanings and avoid prohibited generic
  UI patterns.
- Full existing Android and native Windows CI-equivalent checks pass. Focused OBS
  start/stream/stop/recovery validation follows product policy; no elapsed-time
  endurance test is required.
- Runtime visual validation, when explicitly authorized, covers the state/size
  matrix in the root contract. Code inspection alone is insufficient for that gate.
