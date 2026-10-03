# M3 secure Android-to-Windows transport validation

**Status:** physical 720p30 streaming, receiver process restart, and phone Wi-Fi
off/on recovery passed on a Samsung Galaxy S21 Ultra and Windows 11 PC over
local Wi-Fi. The first Wi-Fi recovery attempt failed; its cause and fixes are
preserved below.

## Setup

The phone used the rear camera at `1280x720@30`, CameraX, the
`c2.exynos.h264.encoder` MediaCodec H.264 encoder, and authenticated TLS/TCP
control and video connections. Windows used the inbox Media Foundation H.264
decoder with NV12 output at 1280×720, stride 1280. The operator entered the
development certificate pin and persistent development secret on the phone.
This is a feasibility bootstrap, not product pairing. The diagnostic receiver
ends at a decoded-frame callback; M4 will build preview and virtual-camera
publication from that seam.

## Automated and local evidence

- [PR #17](https://github.com/sangimed/camylla/pull/17) passed Android build/JVM
  tests, Windows CMake/CTest and receiver checks. A synthetic 720p decoder-file
  run decoded 90/90 access units. Synthetic TLS loopback authenticated separate
  control/video channels, rejected a mismatched video session, and recovered
  from a sequence gap: 93 received units, 91 decoded, one decoder reset, zero
  decoder errors, receiver queue high-water one.
- [PR #18](https://github.com/sangimed/camylla/pull/18) passed Android retry,
  stop, stale-queue and fresh-sync tests, Windows CMake/CTest, receiver build,
  wire/decoder checks and synthetic TLS loopback. That loopback retired an idle
  control session and rebound a fresh authenticated session.
- [PR #19](https://github.com/sangimed/camylla/pull/19) passed Android build/JVM
  tests covering heartbeat cadence, cancellation and timeout.

These are local checks, not physical Wi-Fi recovery evidence. The finalization
PR's automated results are listed in its PR description. Its code cleanup has
automated regression coverage but no separately claimed physical retest.

## Physical healthy stream

Android reported approximately 30 encoded FPS, zero timestamp gaps greater
than 50 ms during observed healthy runs, `Network: Streaming`, and thousands of
sent access units with bounded queues. Windows decoded continuously. One
recorded sample was:

```text
state=Streaming receivedAU=5426 keyframes=93 decoded=5422
decoderErrors=0 networkErrors=6 decoderResets=0 videoConnections=5
lastSeq=5449 nv12=1280x720/stride1280 queueDepth=0 queueHighWater=1
```

Successive five-second `receivedAU/decoded` samples were `5576/5572`,
`5725/5721`, `5876/5872`, `6026/6022`, `6175/6171`, `6325/6321`, and
`6476/6472`. Approximately 150 units arrived per five seconds, consistent
with 30 FPS. After decoder startup/configuration, decoded frames tracked
received units essentially 1:1. Decoder errors and resets remained zero,
Windows queue depth remained zero, and its high-water mark was one.

## Receiver process restart

With phone capture still active, the Windows receiver was stopped and
restarted. Android reauthenticated/reconnected and Windows decoding resumed.
This physical recovery test passed without phone Stop/Start.

## First Wi-Fi interruption: failed

The first phone Wi-Fi off/on attempt left Android encoding at about 30 FPS but
stopped transmission. `Sent` froze; Windows remained `Recovering` with frozen
received and decoded counts. Immediate `ENETUNREACH` failures and temporary
cellular routing to the private PC address could consume the old lifetime
20-attempt retry budget. An abandoned receiver control session could also
survive without liveness traffic. This attempt failed and is not counted as a
pass.

## Corrective PRs and successful Wi-Fi recovery

PR #18 removed the finite lifetime retry budget for an explicit capture,
capped backoff, made channel failure create a fresh authenticated logical
session, cleared stale video, required fresh codec config and a sync frame,
and added control liveness. PR #19 corrected heartbeat cadence so video-queue
notifications could not accelerate pings.

After those fixes, multiple physical phone Wi-Fi off/on events recovered
automatically. Android returned to `Streaming`, `Sent` resumed increasing,
consecutive failures returned to zero, and no manual Stop/Start was required.
The recorded phone sample after recovery was:

```text
Queued: 5629  Sent: 5030  Bytes: 62821631
Queue depth/high-water: 0/3
Resets: 313  Dropped: 1315  Network errors: 17
Connection attempts: 15  Consecutive failures: 0
Successful recoveries: 2  Last sequence: 5035
```

Windows logged `Video bound session=355AB19F932335ECAD29739939AB80F3`
and then the increasing received/decoded samples above, ending at 6476/6472.
Decoder errors and resets stayed zero, queue depth was zero, high-water was
one, and NV12 remained 1280×720/stride1280. Android queue high-water was
three. Physical Wi-Fi recovery is therefore **PASS** for this setup. No exact
stopwatch recovery duration was recorded.

The finalization audit traced the high `Resets` count to encoder output still
entering the small video queue while no usable transport existed. During an
outage, repeated queue age/depth resets discarded frames and requested sync
frames without a receiver. The queue remained bounded, but the churn was
unnecessary. The finalization cleanup discards output until a new bound video
session is ready and then requests one fresh sync point. It keeps historical
drop/error counters; these physical counts predate that cleanup.

## Timing and measurement limits

The wire carries Android presentation and send timestamps. Windows records
receive and decoded-frame `Stopwatch` timestamps. Android and Windows monotonic
clocks are unrelated; subtracting their absolute values cannot yield
Android-to-Windows latency. The receiver now expresses its latest same-clock
receive-to-decoder-callback interval in milliseconds with `Stopwatch.GetElapsedTime`.
A decoder output may correspond to an earlier submitted access unit, so this
local processing interval is not full camera-to-Windows or camera-to-consumer
latency. No exact recovery duration or cross-device end-to-end latency is
asserted.

Healthy LAN throughput sustained the 30 FPS baseline and the recorded samples
showed bounded queues without a growing video backlog. The eventual
camera-to-consumer p95 latency, five-second recovery target, 60-minute run,
and broader impaired-network profiles require a defined physical measurement
setup and later product integration.

## Remaining non-blocking work

- M4: implement production pairing and repeat-use trust UX, preview,
  virtual-camera publication integration, controls, settings, neutral
  disconnected output, and actionable error/degradation states.
- Later validation: quantify camera-to-consumer latency and exact recovery
  duration; exercise packet-loss, bandwidth and longer-disconnect profiles;
  collect sustained thermal/battery and broader hardware evidence.

The development secret/pin and this single physical setup do not establish
production trust UX or broad compatibility.
