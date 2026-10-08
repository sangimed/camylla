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
Tests use synthetic frames and isolated local trust/ports/pipes. No camera
capture or OBS session was started for this review.

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
