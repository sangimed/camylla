# Android UI redesign validation — 2026-10-21

## Scope

Issue [#59](https://github.com/sangimed/camylla/issues/59) was implemented in
[PR #83](https://github.com/sangimed/camylla/pull/83), merged into `main` as
`05f78835f0323e2adedbbacc0e1dfafaa1c4c793`. The redesign changes Android views
and presentation state; pairing identity/security, capture service, CameraX,
encoder, network transport, and Windows UI/protocol were not modified.

The research and reference mapping are in
[`docs/research/droidcam-android-ui-2026-10-21.md`](../research/droidcam-android-ui-2026-10-21.md).
Safe unpaired-state emulator captures are
[`portrait`](evidence/issue-59-emulator-portrait.png) and
[`landscape`](evidence/issue-59-emulator-landscape.png). No camera imagery was
captured.

## Validation results

| Check | Result | Evidence |
|---|---|---|
| Android debug assembly | PASS | Local `:app:assembleDebug` |
| JVM unit tests | PASS | Local `:app:testDebugUnitTest` |
| Android instrumentation APK assembly | PASS | Local `:app:assembleDebugAndroidTest` |
| Android lint | PASS | Local `:app:lintDebug` |
| Focused emulator navigation/orientation test | PASS | `mobileNavigationKeepsCaptureActionsVisibleAndSeparatesInfrequentWork`; visits Camera, Connections and Settings, exercises advanced controls/presets/video selection, checks capture action visibility and portrait↔landscape adaptation |
| GitHub Android build and JVM tests | PASS | [CI run](https://github.com/sangimed/camylla/actions/runs/39664628316) |
| GitHub Windows native and receiver checks | PASS | [CI run](https://github.com/sangimed/camylla/actions/runs/39664628316) |
| Remaining `CameraProductTest` renderer cases | NOT PASS on emulator | Three renderer tests fail because this API 35 emulator rejects producer buffer format `0x2` for an `ImageReader` configured as `0x1`; not presented as passing evidence |
| Physical-device camera, pairing, live stream/OBS, and active-stream rotation | NOT TESTED | No physical Android device was connected for this work |

The emulator navigation test verifies UI destinations and responsive transitions;
it does not establish camera or transport behavior. The landscape screenshot
uses an unpaired state, so its preview is intentionally unavailable. Portrait
and landscape compositions, control reachability, and pairing entry are
visually inspectable in the captures.

## Review limitations

UX research, architecture, design, and implementation agents were started with
an explicit `gpt-6-luna` override. Their effective runtime could not be
independently confirmed, so the mandated independent finish-gate and reality
checker agents were not spawned. Those independent reviews remain outstanding.

## Release status

This record documents the merged UI work; it does not claim physical-device
acceptance or mark the broader M4 product milestone complete.
