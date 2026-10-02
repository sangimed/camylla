# Camylla roadmap

This file tracks Camylla's project-level milestones and current direction.

Detailed, executable work belongs in [GitHub Issues](https://github.com/sangimed/camylla/issues). The roadmap intentionally stays higher level so it does not duplicate the issue tracker.

## Current focus

### M2 — Android feasibility

**Status:** current  
**Tracking:** [#13 — M2: validate Android CameraX to MediaCodec H.264 capture](https://github.com/sangimed/camylla/issues/13)

M1 established that the selected Windows 11 Media Foundation software-camera direction is viable: the development camera registers, activates through Windows Frame Server, delivers deterministic NV12 frames to real consumers, supports the baseline 720p30 and 1080p30 modes, survives repeated consumer open/close cycles, and removes cleanly through the development control tool.

M2 now isolates the Android side. The next proof is CameraX-first capture into a MediaCodec H.264 encoder surface on the primary physical phone, with a reliable 30 FPS baseline and lifecycle/thermal evidence before any production network transport is introduced.

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

**Status:** current

- [ ] Build the CameraX-first capture proof.
- [ ] Feed MediaCodec H.264 through a surface path.
- [ ] Validate front/rear cameras and baseline controls.
- [ ] Validate app switching, foreground-service lifecycle, and screen-off behavior.
- [ ] Sustain the 30 FPS baseline on the primary physical test phone.
- [ ] Measure thermal and battery behavior.
- [ ] Record optional 60 FPS results without making them a release blocker.

Tracking: [#13 — M2: validate Android CameraX to MediaCodec H.264 capture](https://github.com/sangimed/camylla/issues/13).

### M3 — Secure end-to-end transport

**Status:** planned

- [ ] Complete the versioned protocol beyond video framing.
- [ ] Define capability negotiation and selected-mode semantics.
- [ ] Define control messages, acknowledgements, errors, and reset/reconnect behavior.
- [ ] Specify and review pairing, remembered trust, revocation, and key rotation.
- [ ] Bind video and control connections to the same authenticated session.
- [ ] Implement the initial TLS/TCP pipeline.
- [ ] Measure latency, backlog behavior, and recovery under network impairment.
- [ ] Keep TLS/TCP only if it passes the project gates; otherwise revisit the transport ADR.

### M4 — Product alpha

**Status:** planned

- [ ] Build the focused Windows setup and preview experience.
- [ ] Add camera/mode controls and persistent settings.
- [ ] Add pairing and repeat-use workflows.
- [ ] Add actionable errors and recovery states.
- [ ] Add degradation reporting for network/thermal pressure.
- [ ] Ensure disconnected output is neutral rather than stale.

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

The detailed technical gates, rationale, and exit criteria remain in [docs/design.md](docs/design.md).
