# Android mobile UI redesign

Status: implementation design for [#59](https://github.com/sangimed/camylla/issues/59).

## Evidence and intent

The existing product already has a working preview, explicit phone-authorized Start,
pairing, synchronized camera controls, presets and security-key rotation. The redesign
must preserve those behaviours while making the streaming path legible at a glance.

The interaction model takes inspiration from the *principles* documented by
[Blackmagic Camera](https://www.blackmagicdesign.com/products/blackmagiccamera):
important capture parameters are available from the camera view, tap-to-focus remains
direct, and detailed controls do not obscure the shot. Camylla uses original styling,
layout and vector symbols; it does not copy Blackmagic assets or screens. Material
accessibility guidance informs text labels, contrast, touch targets and responsive
reflow.

## Product decisions

| Decision | Design response |
| --- | --- |
| Avoid overwhelming a new user | **Simple** is the default, persisted separately from capture settings. It shows preview, PC state, Start/Stop, camera, video quality, zoom and torch. |
| Retain professional control | An explicit, persisted **Pro** mode reveals the current focus, exposure, white-balance, image, and monitoring groups plus presets. Switching presentation mode never interrupts capture. |
| Keep streaming immediate | Camera is a dedicated home screen. A large Start/Stop action is always reachable; the preview keeps touch-focus and pinch-zoom. |
| Separate infrequent work | Connections owns one-time code/QR pairing and trusted PCs. Settings & security owns key rotation, recovery and diagnostics. |
| Work across available space | Portrait stacks preview and controls; landscape and wide tablets place controls beside the preview. Pages scroll at larger font scales rather than overlap. |
| Communicate state quietly | Anthracite surfaces, electric-blue action/focus accents, green connected state, and red only for stop or critical failure. Short inline feedback replaces routine modal interruption. |
| Preserve accessibility | Text accompanies Simple-mode icons; controls are at least 48 dp high, descriptive, high-contrast, and retain all existing keyboard/screen-reader labels. |

## Validation record to complete

Before #59 can be accepted, record build/device details and captures of Simple and Pro
on a real device in portrait, landscape, tablet/wide layout and enlarged font scale.
Then run the focused OBS start, stream, stop and recovery checks and obtain the
user's visual acceptance. These are release-consumer checks for OBS, not a timed
endurance run.
