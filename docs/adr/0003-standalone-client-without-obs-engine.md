---
status: accepted
---

# Build a standalone client without the OBS engine

Camylla will have its own Windows setup, preview, controls, and settings, without libobs, an OBS client shell, or a required OBS installation. Although modern DroidCam reuses OBS internally, Camylla's single-phone meeting scope excludes scenes, composition, recording, and broadcasting; those engine capabilities do not justify their integration and maintenance cost for v1.

## Considered options

An OBS fork could reuse mature media/UI functionality but creates substantial inherited product and upgrade surface. Embedding libobs would retain a separate shell while still introducing a general media engine and its dependency/license obligations. Qt alone is a separate UI choice and remains under consideration. An OBS source plugin would not satisfy the standalone webcam requirement.

## Consequences

OBS is a supported consuming application and architectural reference. Camylla must own its focused capture-to-webcam pipeline; adding composition or recording later may justify revisiting engine reuse.
