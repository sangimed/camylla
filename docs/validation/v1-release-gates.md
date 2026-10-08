# v1 release validation gates

This record tracks the system-level gates in `docs/design.md` section 17. A
release claim requires versioned evidence from the actual tested configuration;
CI and earlier short physical checks do not substitute for these gates.

## Required gates

- **30 FPS baseline and endurance:** at least 60 continuous minutes in each
  supported launch mode, with bounded queues/memory and no decoder failure.
- **Camera-to-consumer latency:** p95 at or below 200 ms on a documented
  healthy LAN, measured from the physical camera input to the image received by
  the local consuming application. Phone and Windows monotonic clocks cannot
  be subtracted directly.
- **Consumer compatibility:** test Teams, Zoom and OBS one at a time, recording
  exact versions and the negotiated mode. Optional 60 FPS is not a release
  gate.
- **Recovery:** exercise packet loss, jitter, bandwidth reduction, Wi-Fi/IP
  change and a 20-second disconnect. Require neutral output with no stale frame,
  bounded backlog/memory, and recovery within five seconds after usable
  connectivity returns.
- **Distribution:** clean offline Windows installation with ordinary UAC,
  runtime/signature verification, upgrade, uninstall and camera/registry
  cleanup. Audit bundled dependencies, licenses and notices; produce signed
  release artifacts, checksums, build provenance and rollback instructions.

## Physical run — stopped early on user request

- Tablet: Xiaomi 2405CPCFBG, Android 16 / API 36, USB powered; screen configured
  to stay on while powered.
- PC: Windows 11 Pro build 26200, AMD Ryzen 7 3700X, NVIDIA GeForce RTX 3070.
- Camylla receiver: merge #39 (`e065525`), Debug desktop build; phone capture
  mode 1280x720@30.
- The baseline was scheduled for 2026-10-08 19:10:42–20:10:42 UTC. Its receiver
  CSV contains 709 samples from 19:10:42.194 to 20:10:38.071 UTC (59m56s),
  about every five seconds. It averaged 29.09 received and decoded frames/s,
  with zero decoder errors, preview queue high-water 1, and NV12 buffer
  high-water 6. Network errors increased from 2 to 13 (11 during the interval),
  and the log recorded six non-streaming
  samples across five reconnect episodes: two `Disconnected`, two
  `Negotiating`, and two `Recovering`. The longest sampled recovery span was
  about 24 seconds. The stream recovered
  automatically each time, but this is not a clean uninterrupted endurance
  pass and exceeds the separate five-second recovery target.
  [Full receiver samples](evidence/baseline-20261008-720p30.csv).
- OBS 32.2.2 opened the existing Camylla validation scene. Its log records the
  virtual camera at 1920x1080 NV12/30 FPS with buffering disabled. The source
  connected at 19:30:29 UTC. At the user's request, the run was stopped at
  20:15:44 UTC, after 45m15s. The last OBS monitor sample was 20:15:38; across
  89 samples, OBS kept one IPC client connected, the receiver reported zero
  decoder errors, queue high-water 1 and NV12 high-water 6. The counters ended
  at 13 network errors and 19 IPC errors. The monitor sampled `Streaming` 86
  times, `Disconnected` twice and `Recovering` once. The last reconnect was at
  19:50; no further error counters increased before stopping. This is useful
  compatibility evidence, but it is not the required one-hour OBS run.
  [Partial OBS samples](evidence/obs-20261008-32.2.2-partial.csv).
- The detailed baseline process monitor started at 19:27:59; the OBS process
  monitor started at 19:31:27. Camylla working set during those sampled periods
  ranged from 208 to 247 MiB, with preview queue high-water 1 and NV12
  high-water 6. The raw receiver diagnostic CSV under `%LOCALAPPDATA%\Camylla`
  covers the full baseline interval. Interim monitor samples are under the
  ignored `build/validation/release-gate-4` directory.

## Status and limits

- **Endurance:** the baseline ran for 59m56s and had five reconnect episodes;
  the uninterrupted gate is not passed. The OBS run stopped at 45m15s, short of
  its one-hour target. A clean baseline and full one-hour consumer run remain.
- **Throughput:** the baseline averaged 29.09 received/decoded FPS at 720p.
  During the partial OBS run, the registered virtual camera was requested at
  1080p30 while the phone supplied 720p30. The IPC live-response counter
  advanced at about 29 responses/s; no unique-frame or latency claim is made.
- **Latency:** not measured. Same-clock receive/decode timestamps are not
  camera-to-consumer latency. A defined optical test is still required to
  compare the clock visible at the camera input with that same clock in the
  consumer's received image and calculate p95.
- **Apps:** OBS 32.2.2 is installed. Teams and Zoom were not found on this PC;
  their compatibility remains untested.
- **Recovery:** short Xiaomi Wi-Fi interruptions and automatic recovery are
  recorded in [M4.7 validation](android-windows-m4-7.md). They do not cover the
  full impairment matrix or the five-second recovery target.
- **Packaging/signing:** no end-user installer, audited release bundle,
  maintained release signing workflow or release artifacts have been validated.
