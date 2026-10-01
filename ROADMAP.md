# Camylla roadmap

This file tracks Camylla's project-level milestones and current direction.

Detailed, executable work belongs in [GitHub Issues](https://github.com/sangimed/camylla/issues). The roadmap intentionally stays higher level so it does not duplicate the issue tracker.

## Current focus

### M1 — Windows virtual-camera feasibility

**Status:** partially validated — Windows runtime evidence pending  
**Tracking:** [#2 — M1: validate Windows virtual camera feasibility](https://github.com/sangimed/camylla/issues/2)  
**POC implementation:** [#4 — poc: validate Windows virtual camera feasibility](https://github.com/sangimed/camylla/pull/4)

The first Windows virtual-camera implementation now exists: a native Media Foundation source, a registration/removal control tool, deterministic NV12 synthetic frames, and portable tests for frame layout and cadence.

The central feasibility gate is still open because the POC has not yet been built and exercised on Windows 11. M1 still needs evidence for registration/activation, source lifetime, 720p30/1080p30 delivery through the Windows camera pipeline, UAC/deployment behavior, and Teams/Zoom/OBS compatibility.

M1 is complete only when there is recorded Windows evidence that the selected Media Foundation approach is viable for Camylla's intended desktop experience.

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

**Status:** current — partially validated

- [x] Implement the Windows Media Foundation virtual-camera POC and development registration tool.
- [x] Implement and test deterministic synthetic offline/test frames.
- [ ] Prove Media Foundation virtual-camera registration and activation on Windows 11.
- [ ] Validate 720p30 and 1080p30 through the real Windows camera pipeline.
- [ ] Validate repeated consumer open/close/reopen behavior.
- [ ] Determine source lifetime and registration model from observed Windows behavior.
- [ ] Determine actual UAC/elevation/deployment requirements.
- [ ] Test Teams, Zoom, and OBS with recorded versions.
- [ ] Decide whether the current Media Foundation direction survives the completed proof.

### M2 — Android feasibility

**Status:** planned

- [ ] Build the CameraX-first capture proof.
- [ ] Feed MediaCodec H.264 through a surface path.
- [ ] Validate front/rear cameras and baseline controls.
- [ ] Validate app switching, foreground-service lifecycle, and screen-off behavior.
- [ ] Sustain the 30 FPS baseline on the primary physical test phone.
- [ ] Measure thermal and battery behavior.
- [ ] Record optional 60 FPS results without making them a release blocker.

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
