# Camylla roadmap

This file tracks Camylla's project-level milestones and current direction.

Detailed, executable work belongs in [GitHub Issues](https://github.com/sangimed/camylla/issues). The roadmap intentionally stays higher level so it does not duplicate the issue tracker.

## Current focus

### M4 — Product alpha

**Status:** current  
**Tracking:** [#20 — M4: Product alpha](https://github.com/sangimed/camylla/issues/20)

M1 established the Windows Media Foundation virtual-camera path, M2 validated physical S21 Ultra CameraX/MediaCodec capture, and M3 validated authenticated H.264 transport to continuous Windows NV12 decoding at approximately 30 FPS. Receiver restart and phone Wi-Fi interruption recovered, with bounded queues. M4 now builds the focused desktop preview, pairing, controls, settings, and virtual-camera integration from those seams. Exact end-to-end latency and broad compatibility remain later measured gates.

Product refinement priorities: [#59 — mobile UI/UX redesign](https://github.com/sangimed/camylla/issues/59) and [#61 — responsive Windows UI/UX redesign](https://github.com/sangimed/camylla/issues/61). The tap-to-focus feedback enhancement [#60](https://github.com/sangimed/camylla/issues/60) is tested, user-accepted and merged in PR #62. Additional validation and release preparation remain tracked below.

## Milestones

### M0 — Design baseline

**Status:** complete

- [x] Define the v1 product scope and non-goals.
- [x] Record the initial architecture and major trade-offs.
- [x] Document architecture decisions.
- [x] Inspect relevant reference projects.
- [x] Define the first bounded/versioned H.264 frame format.
- [x] Implement and test the portable C++ video-frame parser/serializer.

The design remains a living document. Completing M0 does not mean every implementation detail is fixed.

### M1 — Windows feasibility

**Status:** complete

- [x] Implement the Windows Media Foundation virtual-camera POC and development registration tool.
- [x] Implement and test deterministic synthetic offline/test frames.
- [x] Prove Media Foundation virtual-camera registration and activation on Windows 11.
- [x] Validate 720p30 and 1080p30 through the real Windows camera pipeline.
- [x] Validate repeated consumer open/close/reopen behavior.
- [x] Establish the development registration/deployment model for the selected current-user camera path.
- [x] Determine the observed elevation requirement for machine-wide COM source deployment.
- [x] Record consumer results, including explicitly untested targets where versioned evidence was not collected.
- [x] Validate clean current-user removal and COM cleanup.
- [x] Keep the Media Foundation direction after the completed feasibility proof.

The M1 evidence is recorded in [`docs/validation/windows-virtual-camera-m1.md`](docs/validation/windows-virtual-camera-m1.md). Reboot persistence, all-users scope, and additional application/version coverage remain useful later compatibility/release evidence rather than blockers for starting M2.

### M2 — Android feasibility

**Status:** complete

- [x] Build the CameraX-first capture proof.
- [x] Feed MediaCodec H.264 through a surface path.
- [x] Manually exercise camera switching and baseline controls.
- [x] Manually validate app switching, foreground-service lifecycle, and screen-off behavior.
- [x] Measure about 29.9 encoded FPS at rear 720p30 on the primary physical phone.
- [ ] Record additional thermal and battery measurements through focused checks: [#46](https://github.com/sangimed/camylla/issues/46).
- [ ] Record optional 60 FPS results and 30 FPS fallback, without making them a release blocker: [#47](https://github.com/sangimed/camylla/issues/47).

The physical evidence and limits of this feasibility result are preserved in [the S21 Ultra validation record](docs/validation/android-m2-s21-ultra.md). Tracking: [#13 — M2: validate Android CameraX to MediaCodec H.264 capture](https://github.com/sangimed/camylla/issues/13).

### M3 — Secure end-to-end transport

**Status:** complete

Tracking: [#16 — M3: implement secure Android-to-Windows H.264 transport](https://github.com/sangimed/camylla/issues/16).

- [x] Complete the bounded versioned protocol beyond video framing.
- [x] Define capability negotiation and selected-mode semantics.
- [x] Define control messages, responses, errors, and reset/reconnect behavior.
- [x] Specify the production direction for explicit pairing, remembered trust, revocation, and rotation; defer UX and concrete credential protocol to M4.
- [x] Bind video and control connections to the same authenticated session.
- [x] Implement the initial authenticated TLS/TCP pipeline.
- [x] Record healthy-LAN cadence, bounded backlog, receiver-restart and Wi-Fi recovery; expose same-clock receive-to-decode timing. Exact cross-device latency and recovery duration were not recorded.
- [x] Accept TLS/TCP for continued development against the available M3 evidence.

The [physical M3 record](docs/validation/android-windows-m3.md) preserves the first failed Wi-Fi attempt, PR #18/#19 corrections, and successful retest. Rear 720p30 received about 150 access units per five seconds with continuous NV12 decode, zero recorded decoder errors/resets, Windows queue high-water one, and Android post-recovery high-water three. This accepts the M3 feasibility direction without claiming the later camera-to-consumer latency or release stability gates. Development pin/secret entry remains a proof bootstrap.

### M4 — Product alpha

**Status:** current

Tracking: [#20 — M4: Product alpha](https://github.com/sangimed/camylla/issues/20).

- [x] Build the focused Windows setup and preview experience.
- [x] Add camera/mode controls and persistent settings. Implementation and user acceptance on the Xiaomi/Windows/OBS configuration are merged; see [#26](https://github.com/sangimed/camylla/issues/26).
- [x] Deliver the expanded [#26 camera experience](https://github.com/sangimed/camylla/issues/26): mobile preview, focus/locks, torch, exposure and white-balance controls, supported advanced settings, framing aids and presets. PR #44 is merged and tested; the user confirmed natural phone/Windows/OBS proportions and working controls on the final APK. See [camera validation](docs/validation/camera-pr44-retest-2026-10-09.md) and [UX scope](docs/camera-controls-ux.md); additional hardware coverage remains in [#48](https://github.com/sangimed/camylla/issues/48).
- [x] Add pairing and repeat-use workflows. The default eight-digit, one-use
  code flow—with authenticated local discovery, explicit PC approval, QR as an
  alternative, and no capture start—has passed automated and Xiaomi/Windows
  physical validation; see [pairing-code validation](docs/validation/pairing-code-2026-10-11.md).
- [x] Add actionable errors and recovery states. The M4.6 implementation and Xiaomi user-run checks merged in PR #37. PR #41 adds tested recovery diagnostics and user-confirmed Xiaomi/OBS live output, source reopen, neutral outage output and automatic recovery.
- [x] Implement delivered-quality reporting and bounded network/severe-thermal bitrate adaptation. M4.7 merged in PR #39; automated checks pass, and Xiaomi 720p30/Wi-Fi recovery evidence is recorded in [the M4.7 validation record](docs/validation/android-windows-m4-7.md).
- [ ] Complete M4.7 physical quality validation on a representative Android phone: healthy 720p30/1080p30, observe effective bitrate reduction under controlled network pressure, and record a safely observable thermal case. The Xiaomi run confirmed recovery but did not observe the bitrate reduction; thermal remains untested. Tracking: [#40](https://github.com/sangimed/camylla/issues/40).
- [x] Ensure disconnected output is neutral rather than stale.
- [x] Add mobile preview-only tap-to-focus target and actual acquisition feedback. PR #62 is merged, Android/Windows CI and focused tests pass, and the user accepted the Xiaomi/Windows result. See [validation and acceptance](docs/validation/tap-focus-feedback-2026-10-09.md); additional physical scenarios are not inferred from that acceptance.

Additional physical acceptance remains tracked in [#38 — recovery UI matrix](https://github.com/sangimed/camylla/issues/38) and [#40 — quality/adaptation](https://github.com/sangimed/camylla/issues/40). The low-priority full Android reboot/trust check is [#34](https://github.com/sangimed/camylla/issues/34). These are validation follow-ups, not reported product bugs.

### M5 — Wider alpha / beta

**Status:** planned

- [ ] Establish the signed alpha delivery path: a protected release tag must
  build reproducible Android and Windows candidates, produce a portable Windows
  ZIP and a supported installer, sign and verify every shipped binary, and
  publish hashes, SBOM and provenance. Validate fresh install, upgrade,
  uninstall and virtual-camera registration/removal on the signed artifacts
  before promoting beyond alpha. Tracking: [#64](https://github.com/sangimed/camylla/issues/64).
  See the [release delivery architecture](docs/research/release-delivery-architecture-2026-10-12.md).
- [ ] Add a second Android device to the compatibility matrix: [#48](https://github.com/sangimed/camylla/issues/48).
- [ ] Add a Windows integrated-GPU test configuration: [#49](https://github.com/sangimed/camylla/issues/49).
- [x] Improve transport/IPC diagnostics and record focused streaming, bounded-resource and OBS lifecycle/recovery evidence on the Xiaomi configuration. PR #41 is merged and tested; see [the recovery validation record](docs/validation/recovery-diagnostics-2026-10-08.md).
- [ ] Expand parser/security/state-machine testing and fuzzing where appropriate: [#50](https://github.com/sangimed/camylla/issues/50).
- [ ] Audit bundled dependencies, runtimes, licenses, and notices: [#51](https://github.com/sangimed/camylla/issues/51).
- [ ] Prepare and validate ordinary offline install, upgrade, uninstall, and cleanup: [#52](https://github.com/sangimed/camylla/issues/52).
- [ ] Establish the release-signing path: [#53](https://github.com/sangimed/camylla/issues/53).

### M6 — v1.0

**Status:** planned

- [ ] Pass all required 30 FPS release gates: [#56 — baseline/OBS](https://github.com/sangimed/camylla/issues/56), [#54 — camera-to-OBS latency](https://github.com/sangimed/camylla/issues/54), [#55 — impairment/recovery](https://github.com/sangimed/camylla/issues/55), and distribution checks [#51–#53](https://github.com/sangimed/camylla/issues/51).
- [ ] Produce signed Windows and Android release artifacts: [#53](https://github.com/sangimed/camylla/issues/53).
- [ ] Publish checksums, notices, supported configurations, and known limitations: [#57](https://github.com/sangimed/camylla/issues/57).
- [ ] Document reinstall/rollback and recovery procedures: [#58](https://github.com/sangimed/camylla/issues/58).
- [ ] Record build provenance and release validation evidence: [#57](https://github.com/sangimed/camylla/issues/57).

## Deferred from v1

These are intentionally not part of the current roadmap unless a later design decision promotes them:

- audio / virtual microphone;
- USB transport;
- Windows 10;
- Windows ARM64;
- multiple-phone composition;
- recording;
- HEVC / AV1;
- 4K as a release requirement;
- OBS plugin or libobs integration;
- automatic updater;
- Store / Play / F-Droid publication.

## Tracking convention

Use this file for milestone-level direction.

Use GitHub Issues for concrete tasks that can be implemented, reviewed, and closed. Pull requests should reference the issue they advance when practical.

After a tested pull request merges into `main`, update this roadmap in the
follow-up change for every completed milestone-level item. Keep unfinished
physical matrices and release evidence linked from their GitHub issues.

The detailed technical gates, rationale, and exit criteria remain in [docs/design.md](docs/design.md).
