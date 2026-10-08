# M4.7 quality state — implementation and validation

Issue [#28](https://github.com/sangimed/camylla/issues/28) tracks the quality
and performance work. This record states what the current implementation
reports and adapts; it is not a physical performance pass.

## Implemented

- Android publishes an authenticated `quality_state` record once per second
  for the active camera generation. It includes the selected and effective
  mode and bitrate, delivered encoded FPS, current network and thermal
  pressure, and one current reason.
- During network recovery or Android severe thermal status, the encoder asks
  MediaCodec to reduce bitrate to 75% of the selected profile. It restores the
  selected bitrate after the current pressure clears. Resolution, FPS target
  and generation remain stable for this bitrate-only adaptation.
- The Windows receiver validates every quality-state field with bounded
  strings, numeric ranges and the current authenticated session/generation.
  The desktop UI shows delivered FPS, selected/effective values and any
  current warning. Diagnostics counters remain separate from the primary
  status.
- A mode or lens change keeps the existing reconfiguration behavior: it
  creates a new generation, clears prior quality state, establishes a fresh
  video bind, and requires configuration plus a keyframe before publication.

## Automated coverage

- Android unit tests cover healthy quality, network pressure, severe thermal
  pressure, bitrate reduction and a single current reason independent of
  historical error totals.
- The receiver camera-control mutual-TLS self-test sends a quality record,
  confirms bounded state retention, and confirms a generation change clears
  stale quality information.

## Physical work remaining

- Measure sustained delivered FPS and bitrate on the S21 Ultra at the offered
  modes, including a deliberate Wi-Fi interruption.
- Induce or observe thermal pressure and confirm the visible effective bitrate
  and recovery to the selected bitrate.
- Decide from those measurements whether an additional resolution/FPS mode
  adaptation is required. The current bounded bitrate change is the only
  automatic adaptation implemented here.
