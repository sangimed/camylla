# Camylla

Camylla aims to turn an Android phone into a webcam for Windows video meetings, with a standalone desktop app and a local Wi-Fi connection.

**Status: early implementation.** The repository contains the portable protocol component, a validated Windows virtual-camera POC, and an Android capture/encoder feasibility proof. The Android proof still requires physical S21 Ultra validation; there is no desktop application, installer, or release.

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

- [Project roadmap](ROADMAP.md): current milestone, future phases, and tracking convention.
- [Living design](docs/design.md): product scope, architecture, validation gates, and milestones.
- [Glossary](GLOSSARY.md): shared domain terminology.
- [Architecture decisions](docs/adr/): accepted decisions and their trade-offs.
- [Reference-project inspection](docs/reference-projects.md): source research and dependency-review notes.
- [Video wire protocol v1 draft](docs/protocol-v1.md): bounded framing for H.264 access units.
- [Windows virtual-camera POC](docs/windows-virtual-camera-poc.md): prerequisites, build, registration, smoke test, removal, troubleshooting, architecture, and M1 evidence limits.
- [Android M2 proof and S21 Ultra checklist](docs/validation/android-m2-s21-ultra.md): build/install, local H.264 diagnostics, and pending physical validation.

## Build and test

For the Windows virtual-camera POC, follow the complete [Windows developer quick start](docs/windows-virtual-camera-poc.md#windows-developer-quick-start). It covers installing the prerequisites from the versioned [`.vsconfig`](.vsconfig), Visual Studio 2022 x64 configuration, build, tests, current-user registration, the first OBS smoke test, troubleshooting, and clean removal.

For the Android proof, open `src/android` in Android Studio or follow the [S21 Ultra build and validation guide](docs/validation/android-m2-s21-ultra.md).

The initial dependency-free C++20 protocol library remains portable so its behavior can also be checked on non-Windows development hosts:

```sh
cmake -S . -B build -G Ninja
cmake --build build
ctest --test-dir build --output-on-failure
```

## Roadmap

The current focus is **M2: Android feasibility**. Track the proof and physical validation in [issue #13](https://github.com/sangimed/camylla/issues/13) and see [ROADMAP.md](ROADMAP.md) for milestone status and the planned path to v1.0.

The detailed technical gates and exit criteria remain in the [living design](docs/design.md#21-milestones-from-proof-of-concept-to-v10). Dates are deliberately unset.

## License direction

GPL-3.0-or-later is the selected license for Camylla's own source, as recorded in the [licensing decision](docs/adr/0006-copyleft-and-distribution-audit.md). A root license file has not been added yet; dependency and distributed-artifact reviews remain part of the release gates.
