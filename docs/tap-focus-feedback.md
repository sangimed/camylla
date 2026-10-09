# Tap-to-focus feedback — issue #60

Design interview started 2026-10-09 using grill-with-docs. The user confirmed the complete simple interaction with “Go”; implementation is authorized.

## Confirmed decisions

- Add a focus target indicator to the mobile preview only, preserving the existing focus interaction.
- Keep the indicator out of the video delivered to Windows and OBS.
- Show a small square immediately at the touched image location, animate acquisition, and distinguish actual camera success from failure. The user wants behavior familiar from conventional photo/video apps.
- Command acceptance alone is not focus acquisition success.
- After success, dismiss the indicator after approximately one second; after failure, approximately two seconds. Keep a discreet marker while focus is locked.
- A subsequent tap immediately replaces the previous target; obsolete acquisition results must not update the new indicator.
- Keep the interaction simple and familiar, in the spirit of an Android phone camera app. Do not add settings or buttons for this feature.

## Existing implementation facts

- MainActivity routes preview taps through CaptureService to CameraEngine; long press requests focus lock and pinch controls zoom.
- Preview currently has a textual focus badge, but no target square.
- CameraEngine and FocusCoordinates handle image letterboxing, quarter-turn rotation, mirroring and the sensor/stream crop; out-of-image taps are rejected.
- Camera2 autofocus metadata reports acquisition, success and failure. Current CameraStatus has no target geometry or acquisition result correlated with an individual tap; this must be addressed before reporting that the latest target is focused.

These reversible interaction choices do not currently warrant a separate ADR. Record settled decisions here as the interview progresses.

## Confirmed final interaction details

- Use a thin, discreet square with a short acquisition animation and a brief success/failure distinction; keep the distinction understandable beyond color alone.
- Preserve existing long-press focus lock. Do not add exposure gestures or new camera controls.
- When autofocus is unavailable, do not show false acquisition/success; use clear unavailable feedback. Preserve the existing tap autofocus behavior rather than introducing a new focus-mode policy.
- Taps outside the displayed image do nothing. Pinch-to-zoom must not create a focus target.
- Clear obsolete target feedback on Stop, lens/mode changes, preview recreation or geometry changes; never preserve an old target as a new focus success.
- Verify visible target placement, genuine autofocus feedback, successive taps, lock/unlock, transformed previews and absence from OBS output with focused checks.
