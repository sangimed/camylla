# Camylla

Camylla aims to turn an Android phone into a webcam for Windows video meetings, with a standalone desktop app and a local Wi-Fi connection.

**Status: early implementation.** The repository now contains the first portable protocol component alongside the product design, glossary, and architecture decisions. There is still no application, virtual-camera proof, installer, or release. The proposed stack and compatibility targets still require validation.

## Planned experience

- Pair your phone with a trusted desktop on your local network.
- Start the camera session explicitly from the phone.
- Preview the image and adjust camera selection, orientation, zoom, and mirroring in the desktop app.
- Select the Camylla webcam in your meeting application.
- Stop capture from the phone, with a visible notification while a session is active.

The goal is a free, fully open-source webcam without accounts, ads, telemetry, watermarks, premium tiers, or a runtime cloud dependency.

## Initial scope

| Area | Planned v1 target |
| --- | --- |
| Desktop | Windows 11 x64 |
| Phone | Android 10+ as a provisional baseline |
| Connection | Local Wi-Fi with authenticated, encrypted transport |
| Usage | One phone and one consuming application at a time |
| Target applications | Teams, Zoom, and OBS; compatibility must be tested |
| Frame rate | Reliable 30 FPS; optional 60 FPS when supported and sustainable |

Audio, USB connections, Windows 10, Windows ARM64, recording, and multiple-phone composition are deferred. Camylla is intended to work as a standalone Windows webcam without requiring an OBS installation.

## Proposed architecture

The current design uses a Kotlin Android app with CameraX evaluated first and MediaCodec H.264 encoding. A WinUI 3/C# desktop app owns receiving, decoding, preview, and settings, with narrow C++ components for Media Foundation, D3D, and the Windows software virtual camera.

Separate authenticated TLS/TCP connections for video and controls are the initial transport candidate. Capture, transport, native integration, and deployment choices remain conditional on feasibility and performance checks.

## Documentation

- [Living design](docs/design.md): product scope, architecture, validation gates, and milestones.
- [Glossary](GLOSSARY.md): shared domain terminology.
- [Architecture decisions](docs/adr/): accepted decisions and their trade-offs.
- [Reference-project inspection](docs/reference-projects.md): source research and dependency-review notes.
- [Video wire protocol v1 draft](docs/protocol-v1.md): bounded framing for H.264 access units.

## Build the first component

The initial dependency-free C++20 library implements and tests bounded video
frame serialization/parsing. It is portable so its behavior can be checked on
non-Windows development hosts; this does not constitute the Windows virtual
camera feasibility proof.

```sh
cmake -S . -B build -G Ninja
cmake --build build
ctest --test-dir build --output-on-failure
```

## Roadmap

1. Validate the Windows virtual camera, target applications, preview, and deployment.
2. Validate Android capture, controls, screen-off behavior, and a stable 30-FPS baseline.
3. Prove secure end-to-end transport, latency, and recovery under network impairment.
4. Build the setup, preview, settings, and recovery experience for an alpha.
5. Expand device testing and complete security, licensing, packaging, and release checks.

See the [design milestones](docs/design.md#21-milestones-from-proof-of-concept-to-v10) for exit criteria. Dates are not set, and there are no build or installation instructions yet.

## License direction

GPL-3.0-or-later is the selected license for Camylla's own source, as recorded in the [licensing decision](docs/adr/0006-copyleft-and-distribution-audit.md). A root license file has not been added yet; dependency and distributed-artifact reviews remain part of the release gates.
