# Camylla

Camylla aims to turn an Android phone into a webcam for Windows video meetings, with a standalone desktop app and a local Wi-Fi connection.

**Status: early implementation.** M1 Windows virtual-camera, M2 S21 Ultra CameraX/MediaCodec, and M3 physical authenticated transport/decode feasibility proofs are recorded. M4 product alpha is current. There is no polished desktop application, installer, or release.

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
| Target application | OBS; compatibility must be tested |
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
- [M3 transport protocol v2](docs/protocol-v2.md): authenticated session binding, negotiation, framing, limits, and recovery.
- [Windows virtual-camera POC](docs/windows-virtual-camera-poc.md): prerequisites, build, registration, smoke test, removal, troubleshooting, architecture, and M1 evidence limits.
- [Android M2 S21 Ultra result](docs/validation/android-m2-s21-ultra.md): physical producer evidence and remaining coverage limits.
- [M3 Android-to-Windows validation](docs/validation/android-windows-m3.md): physical 720p30 decode and recovery evidence, including the initial failed Wi-Fi attempt.
- [M4.1 Windows preview validation](docs/validation/android-windows-m4-1.md): physical S21 Ultra preview, measured cadence/memory, recovery, lifecycle, and CLI regression results.

## Build and test

For the Windows virtual-camera POC, follow the complete [Windows developer quick start](docs/windows-virtual-camera-poc.md#windows-developer-quick-start). It covers installing the prerequisites from the versioned [`.vsconfig`](.vsconfig), Visual Studio 2022 x64 configuration, build, tests, current-user registration, the first OBS smoke test, troubleshooting, and clean removal.

For the Android proof, open `src/android` in Android Studio or follow the [S21 Ultra build and validation guide](docs/validation/android-m2-s21-ultra.md).

For the M4.1 Windows preview shell, build the native decoder and the unpackaged WinUI app on Windows 11 x64:

```powershell
cmake -S . -B build -A x64
cmake --build build --config Debug
dotnet build src/windows/desktop/Camylla.Desktop.csproj -p:Platform=x64
```

Run the resulting `Camylla.Desktop.exe` from `src/windows/desktop/bin/x64/Debug/net10.0-windows10.0.19041.0/win-x64`. The M3 development pin and secret appear in the setup panel; the Android proof app still starts capture explicitly.

The initial dependency-free C++20 protocol library remains portable so its behavior can also be checked on non-Windows development hosts:

```sh
cmake -S . -B build -G Ninja
cmake --build build
ctest --test-dir build --output-on-failure
```

## Roadmap

The current focus is **M4: product alpha**. Track it in [issue #20](https://github.com/sangimed/camylla/issues/20) and see [ROADMAP.md](ROADMAP.md) for milestone status and the planned path to v1.0.

The detailed technical gates and exit criteria remain in the [living design](docs/design.md#21-milestones-from-proof-of-concept-to-v10). Dates are deliberately unset.

## License direction

GPL-3.0-or-later is the selected license for Camylla's own source, as recorded in the [licensing decision](docs/adr/0006-copyleft-and-distribution-audit.md). A root license file has not been added yet; dependency and distributed-artifact reviews remain part of the release gates.
