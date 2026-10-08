# Camylla roadmap

This file tracks Camylla's project-level milestones and current direction.

Detailed, executable work belongs in [GitHub Issues](https://github.com/sangimed/camylla/issues). The roadmap intentionally stays higher level so it does not duplicate the issue tracker.

## Current focus

### M4 — Product alpha

**Status:** current  
**Tracking:** [#20 — M4: Product alpha](https://github.com/sangimed/camylla/issues/20)

M1 established the Windows Media Foundation virtual-camera path, M2 validated physical S21 Ultra CameraX/MediaCodec capture, and M3 validated authenticated H.264 transport to continuous Windows NV12 decoding at approximately 30 FPS. Receiver restart and phone Wi-Fi interruption recovered, with bounded queues. M4 now builds the focused desktop preview, pairing, controls, settings, and virtual-camera integration from those seams. Exact end-to-end latency and broad compatibility remain later measured gates.

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
- [ ] Record detailed thermal and battery measurements in a longer follow-up run.
- [ ] Record optional 60 FPS results without making them a release blocker.

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
- [ ] Add camera/mode controls and persistent settings. Implementation is merged; the remaining physical camera matrix stays in [#26](https://github.com/sangimed/camylla/issues/26).
- [ ] Deliver the expanded [#26 camera experience](https://github.com/sangimed/camylla/issues/26): mobile preview, focus/locks, torch, exposure and white-balance controls, supported advanced settings, framing aids and presets; see [UX scope and research](docs/camera-controls-ux.md).
- [x] Add pairing and repeat-use workflows.
- [x] Add actionable errors and recovery states. The M4.6 implementation and Xiaomi user-run checks merged in PR #37; additional S21/OBS edge-case evidence is tracked in [#38](https://github.com/sangimed/camylla/issues/38).
- [x] Implement delivered-quality reporting and bounded network/severe-thermal bitrate adaptation. M4.7 merged in PR #39; automated checks pass, and Xiaomi 720p30/Wi-Fi recovery evidence is recorded in [the M4.7 validation record](docs/validation/android-windows-m4-7.md).
- [ ] Complete M4.7 physical quality validation on S21 Ultra: healthy 720p30/1080p30, observe effective bitrate reduction under controlled network pressure, and record a safely observable thermal case. The Xiaomi run confirmed recovery but did not observe the bitrate reduction; thermal remains untested.
- [x] Ensure disconnected output is neutral rather than stale.

### M5 — Wider alpha / beta

**Status:** planned

- [ ] Add a second Android phone to the compatibility matrix.
- [ ] Add a Windows integrated-GPU test configuration.
- [ ] Complete 60-minute stability runs.
- [ ] Expand parser/security/state-machine testing and fuzzing where appropriate.
- [ ] Audit bundled dependencies, runtimes, licenses, and notices.
- [ ] Validate ordinary offline install, upgrade, uninstall, and cleanup.
- [ ] Establish the release-signing path.

### M6 — v1.0

**Status:** planned

- [ ] Pass all required 30 FPS release gates.
- [ ] Produce signed Windows and Android release artifacts.
- [ ] Publish checksums, notices, supported configurations, and known limitations.
- [ ] Document reinstall/rollback and recovery procedures.
- [ ] Record build provenance and release validation evidence.

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
