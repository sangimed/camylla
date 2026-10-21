# DroidCam Android UI research — 2026-10-21

## Scope and sources

The reference is **DroidCam Webcam & OBS Camera**, package
`com.dev47apps.obsdroidcam`, by Dev47Apps. It is the OBS-integrated app and is
distinct from DroidCam Classic/DroidCamX. Google Play showed an update date of
2026-07-18 when checked; the supplied screenshots do not expose their app
version or capture date. The screenshots therefore define the target appearance
more directly than the current listing metadata.

- [Google Play listing (French)](https://play.google.com/store/apps/details?id=com.dev47apps.obsdroidcam&hl=fr)
- [Official DroidCam OBS product page](https://droidcam.app/obs/)
- [Official OBS usage guide](https://www.droidcam.app/obs/usage/)
- [Official help and FAQ](https://droidcam.app/help/)
- [Official Pro/features page](https://droidcam.app/pro-info.html), for capability naming
- [Public DroidCam OBS setup walkthrough](https://www.youtube.com/watch?v=oAE-P1zmrUA), published about a year before this review; it is secondary and mainly demonstrates setup, not the supplied current phone UI
- [Official product-page promotional images](https://files.dev47apps.net/img/droidcam_obs_plugin_list.png) and [OBS source-properties image](https://files.dev47apps.net/img/droidcam_obs_props.png); these depict desktop/OBS surfaces, so they do not inform Android layout
- Five user-provided screenshots, captured 2026-10-21: active camera console;
  Settings categories; Video settings; Camera settings; Power and Screen settings.

The listing and vendor guides describe connection and capability constraints.
The screenshots are direct evidence for visible layout and labels only. They do
not establish gestures, defaults, persistence, accessibility behavior, or the
effects of tapping individual controls.

The Play listing advertises exposure, white-balance, and focus features; the
official Pro page documents basic on-phone controls (zoom, EV, AWB) and optional
remote controls. Those pages support feature terminology only. Pro gating and
remote controls are not Camylla requirements and are not copied.

## Screen findings and screenshot mapping

| Reference image | Purpose and hierarchy | Components and visible states | Camylla response |
|---|---|---|---|
| `photo_1_2026-10-21_21-01-53.jpg` | Active landscape camera console. A wide live image dominates the center; narrow rails sit to its left, top and bottom. | Top rail has compact framing/overlay icons, Settings, Get Pro, Dim and Stop. Left rail shows Wi-Fi/LAN, endpoint, output dimensions, codec and AE FPS range. Bottom rail shows camera switch, AWB, EV, AF-C and zoom. Grid overlays cross the preview. | Preserve the existing `SurfaceView` and service attachment. Use a preview-led landscape canvas, small labeled connection/output band, compact quick controls and a single explicit Stop while active. Show effective Camylla camera/encoder/network values; retain accessible labels even where icons are used. A paired idle state must show Start, never imply an active feed. |
| `photo_5_2026-10-21_21-01-53.jpg` | Settings category index. A title row leads into a full-width, vertically divided list. | Video, Camera, Audio, Power and Screen, Remote Control, Other, and a Pro upsell. White line icons precede large labels; flat charcoal background and thin separators. | Keep Camylla's existing Camera, Connections and Settings navigation. Settings opens a category index; map existing controls into categories and omit unsupported audio/remote/pro-commerce functions. No dead rows. |
| `photo_2_2026-10-21_21-01-53.jpg` | Video configuration. Large title and sparse preference list. | Target Quality shows Normal; Manual / Advanced Options is unchecked; Encoder Options appears disabled. Rows show label and current value with a supporting subtitle where applicable. | Provide Camylla's actual resolution/FPS selector and advanced output/encoder controls, showing capability and effective/pending state. Do not invent a target-quality preset if none exists. |
| `photo_3_2026-10-21_21-01-53.jpg` | Camera configuration. | Camera2 API checked; remembered parameters appears locked; double-tap action is Change Camera; Target FPS is 60 FPS; Minimum FPS is Auto; Camera2 API Probe has a subtitle. | Group Camylla lens, frame-rate, focus, exposure and camera diagnostics settings by purpose. Display supported/applied values from CameraX/Camera2 descriptors. Do not introduce unsupported Camera2 toggles or a fake probe. |
| `photo_4_2026-10-21_21-01-53.jpg` | Power and screen behavior. | Keep Device Awake, battery optimization, Auto-Dim Screen Off, Lock Input, Background Streaming checked with notification subtitle, and Screen-Off Actions. | Retain only existing Camylla power/session options. Preserve foreground-service notification requirements and clear unavailable/permission explanations. Do not imply unsupported background modes. |

## Interaction evidence

The official OBS guide documents connecting a source with the phone-displayed
Wi-Fi address and activating/deactivating the OBS source. It documents USB
device discovery from OBS and notes that lens availability and camera controls
depend on Android/device support. It recommends lowering quality/FPS/output size
for Wi-Fi instability and notes battery restrictions can interrupt background
streaming. These are vendor-documented product facts, not observed tap flows in
the supplied screenshots.

No supplied reference demonstrates how the camera preview is opened, how the
settings category rows navigate, how zoom/focus/exposure widgets behave, how
overlays are hidden, or what reconnect/error screens look like. Keep these
behaviors grounded in Camylla's existing implementation and do not label them as
DroidCam behavior. The 60 FPS setting shown in the Camera screenshot is a
configured target; it does not prove that the active output achieves 60 FPS.

## Reusable component inventory

- Preview stage with restrained transparent controls and framing aids.
- Compact horizontal/vertical action rails with clear active and disabled states.
- Connection/output status block adjacent to, but not obscuring, the image.
- Preference-style category list with full-width rows, icon, title, value or
  explanatory subtitle, and fine separators.
- Explicit selected checkboxes, subdued unavailable rows, and a title/back
  affordance on category detail screens.

The reference uses a dark neutral background, white text/icons, muted gray
secondary text, thin gray row separators, dense spacing, and system-style
typography. Camylla should preserve its own semantic blue/green/amber/red accents
and original vector icons rather than reproducing DroidCam marks or artwork.

## Reference-to-Camylla inventory

| DroidCam element | Existing Camylla capability | UI action |
|---|---|---|
| Live preview and grid | `SurfaceView`, preview aids and touch focus | Expand preview prominence; retain rotation-safe service attachment |
| Camera switch | Lens selector and camera catalog | Place in camera rail; list actual supported lenses |
| Quality/FPS | Supported video modes and capture stats | Make selection easy to reach; distinguish selected mode from observed stream state |
| Zoom, focus, AF, EV, AWB | Camera control descriptors and tap/pinch gestures | Promote quick controls; expose the rest in camera settings with applied/pending/unavailable states |
| Start/Stop | Explicit phone-side capture authorization | One unambiguous action, Start only while ready and Stop during active/start/recovery states |
| Endpoint and stream stats | Pairing/network/capture status | Show trusted-peer identity and observed output/network state without treating remembered trust as streaming |
| Video/Camera/Power categories | Existing output/camera/security settings | Reorganize only existing capabilities; keep pairing/security and diagnostics accessible |
| Get Pro, audio and remote control | No corresponding Camylla capability | Omit |

## Design constraints

- Do not copy logos, illustrations, assets, or exact proprietary artwork.
- Keep Camylla's camera, encoder, transport, pairing security, Windows protocol,
  service lifecycle and phone-authorized Start unchanged.
- Landscape should use the reference's preview-led console. Portrait, tablets,
  short available height, and font scaling must reflow controls without overlap.
- Active rotation must preserve the service session and reattach the new preview
  surface, addressing issue #70.
- No reference screenshot is included in distributed app assets.
