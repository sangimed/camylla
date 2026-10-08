# Recovery diagnostics review, 2026-10-08

This review investigates the reconnect and IPC observations in
[the v1 release record](v1-release-gates.md). It does not claim that the physical
failure is fixed, nor that the remaining #26, #38 or #40 matrices pass.

## Existing physical evidence

The original receiver event log and preview CSV share the run stamp
`20261008-190430-53df8914`. The baseline interval is
19:10:42.194–20:10:38.071 UTC. Inspecting event timestamps, rather than only the
five-second state samples, finds:

| Event | Count in the baseline interval |
| --- | ---: |
| Control EOF (`EndOfStreamException`) | 31 |
| New authenticated control session | 31 |
| Video bound | 42 |
| Video I/O read failure | 7 |
| Video I/O aborted | 4 |

The [sanitized event timeline](evidence/recovery-20261008-events.csv) contains
only timestamps and event categories, with no peer addresses, identities,
session IDs, credentials or camera images. EOFs span 19:34:24–19:50:23 UTC.
The video error counter increases 11 times in the interval, from 2 to 13.
The two earlier video timeouts occurred before the baseline interval.

Five sampled reconnect episodes are therefore a lower bound on the disruptions,
not the complete event count. Many control losses recovered before the next
sample. EOF shows that the control stream ended; it does not establish why the
phone or network ended it. The available Android logcat buffer no longer
contains the relevant interval, so the original Android-side cause is unknown.

IPC errors rose from 0 to 19 between the samples at 19:48:45.533 and
19:50:13.357 UTC. They overlap the later reconnect cluster. The original pipe
server counted normal EOF at a request boundary, truncated requests and failed
response writes together and did not log the exception. This evidence cannot
determine how many of the 19 were consumer closes versus failed transfers.
Correlation with reconnects alone does not prove causation.

## Changes

- Accepted production-control sessions now count unexpected EOF, idle timeout
  and other losses in `ControlDisconnects` and `NetworkErrors`. Rejected starts,
  pairing/rotation-only connections, explicit Stop and receiver shutdown are
  excluded. The event log records a bounded reason without message contents.
- IPC normal EOF between complete requests increments `ClientDisconnects`
  separately. Truncated/invalid requests and failed responses remain errors.
  Errors record request/header/frame/accept stage, exception type and HRESULT
  in the receiver event log and `LastError`.
- Desktop diagnostics and CSV append `controlDisconnects`,
  `ipcClientDisconnects` and `videoConnections`; existing column names remain.
  CLI diagnostics also expose control losses.
- Android reports failed camera-control writes before retiring the channel,
  and logs control establishment, video readiness and queue-pressure/expired-unit
  resets without message contents or session credentials. A failed camera-write
  path previously closed the control socket silently. This is instrumentation,
  not proof that it caused the original 31 EOFs.

These are diagnostic corrections. Transport recovery policy, consumer deadlines
and capture authorization are unchanged.

## Automated validation

Run on Windows in this workspace on 2026-10-08:

- Receiver Debug build: passed, zero warnings/errors.
- WinUI desktop Debug/x64 build: passed, zero warnings/errors.
- Receiver `--self-test`: passed. New real named-pipe tests distinguish a normal
  close, truncated request and aborted live-frame write, then reopen the
  consumer successfully. Existing bounded-buffer, slow-client, access-control,
  neutral-frame and shutdown checks also pass.
- Receiver `--pairing-self-test`: passed. New mutual-TLS cases prove that an
  accepted control EOF increments the loss/error counts once, the actual
  eight-second idle deadline reports `idle_timeout`, a new session is accepted,
  and subsequent explicit Stop adds no loss. Existing pairing, stale/duplicate
  binds, phone/desktop Stop and revoked/untrusted identity checks pass.
- Receiver `--camera-controls-self-test` and `--rotation-self-test`: passed.

The timeout case exercises a protocol deadline; it is not an endurance test.
Tests use synthetic frames and isolated local trust/ports/pipes. The initial
automated review did not start camera capture or OBS. The later physical check
below reused an existing phone-authorized capture.

The Android logging follow-up passed offline `:app:testDebugUnitTest
:app:assembleDebug --no-daemon`: 37 JVM tests and APK build successful. This
logging-only APK was not installed over the active camera session; the physical
observations below used the previously installed Android build.

## Focused physical follow-up

Same Xiaomi 2405CPCFBG, Android 16, Windows 11 25H2 and OBS 32.2.2 /
WebSocket 5.7.4 as the earlier configuration. Android installed version
`0.2-pairing`, versionCode 2, last update 2026-10-08 21:03:58 device local time;
desktop diagnostic implementation commit `0ff99fc`, Debug/x64. Phone rear
1280x720@30, OBS source fixed at 1920x1080 NV12/30 with buffering disabled.
The phone's foreground camera capture was still active from its prior explicit
Start. Relaunching the desktop allowed that authorization to reconnect; no
new capture was started by the agent.

The user confirmed a moving live image in OBS. Deactivating and reactivating
the actual OBS camera source at 21:07:12.605 and 21:07:16.070 UTC restored its
live image. The next sampled diagnostics counted one normal IPC client close,
zero IPC errors, and no network/control loss for that consumer action.

Wi-Fi disable/enable command markers were 21:07:52.735 and 21:08:12.104 UTC,
19.37 seconds apart. The source screenshot during the outage was neutral black;
the user confirmed black output followed by automatic moving-video recovery
without another Start. Receiver events distinguish the video read timeout at
21:07:54.958 from the control idle timeout at 21:08:00.805. Android logs show
control-read connection abort, closed video socket and network-unreachable
connection attempts during this deliberate impairment.

The new control authenticated at 21:08:17.322 and video bound at
21:08:17.487 UTC; the first sampled resumed decoding was 21:08:21.077 UTC.
A ping checked later succeeded and a recovered source screenshot was inspected.
This does **not** establish recovery within five seconds of usable connectivity:
Wi-Fi enable is not network readiness, and the first checked successful ping
was after video had already resumed. The 19.37-second marker interval is not
claimed as the exact 20-second release impairment case.

[Receiver samples](evidence/recovery-20261008-focused.csv) cover startup, healthy
capture, consumer reopen, the outage and restored capture. Thirty-seven adjacent
Streaming intervals with unchanged network-error counters averaged 29.98 decoded
FPS (29.62–30.18). Zero decoder/IPC errors, preview and IPC queue high-water one,
and NV12 buffer high-water four were recorded. The final counters were two
network errors (one video timeout and one control loss), one control disconnect,
one normal IPC client disconnect and two video connections.
[Action/Android events](evidence/recovery-20261008-focused-events.csv) retain
only categories and timestamps; the clock column distinguishes Windows and
Android timestamps. These clocks were not synchronized for latency measurement.
Camera screenshots and full unsanitized logs stay in ignored local artifacts.

No spontaneous disruption reproduced in these focused checks. The original
31 EOFs and 19 IPC errors therefore remain unattributed. No recording or
broadcast was started. The temporary authenticated OBS WebSocket setting was
restored after the check, and OBS reopened with the camera scene.

## Remaining physical check

Use the updated desktop with a phone-user-started session and OBS. Record the
app/OS/OBS versions and both Android and receiver event logs. Check live output,
consumer close/reopen, neutral output during a focused Wi-Fi interruption, and
recovery after usable connectivity returns. Correlate exact event timestamps
with the new control-loss and IPC-close/error counters. Any failed IPC response
must now include its stage and exception. Obtain direct visual confirmation;
do not infer it from counters. No minimum run duration applies.

The reconnect root cause and original IPC error classification remain open
until those correlated observations are available. #26 already has an integrated
camera implementation on main; its physical/visual acceptance remains open.
