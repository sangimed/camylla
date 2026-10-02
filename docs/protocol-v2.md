# M3 transport protocol v2

Status: implementation proof, pending physical Android-to-Windows validation. This
extends the portable `CMYV` framing from [v1](protocol-v1.md); v1 remains historical
and is rejected by the v2 receiver. All connections are TLS/TCP. The Windows user
process owns both listeners, session state and decoding. The virtual-camera DLL has
no network responsibilities.

## Development authentication

The receiver generates a self-signed development server certificate in the current
user's LocalAppData `Camylla/m3-proof` directory and displays its SHA-256 certificate
pin. It also generates a 256-bit development secret in that user directory. The
phone operator explicitly enters the Windows LAN address, pin and secret. The Android
TLS trust manager checks the exact leaf certificate pin and validity; there is no
plaintext fallback or process-wide trust bypass. This pin is the explicit trust
bootstrap for the proof. The secret is sent only inside TLS and persists across
receiver restarts so an active phone session can reauthenticate. The certificate
private key and secret stay in the user profile and are not committed to the repository. The
certificate is regenerated near expiry and its new pin must be entered on the phone.
Deleting the development secret file while the receiver is stopped rotates that
secret. This is a temporary developer flow, not the future QR pairing/rotation/
revocation design.

Control and video are separate TLS 1.2/1.3 connections to ports 58413 and 58414.
The control `hello` proves knowledge of the development secret and creates a random
128-bit session ID. The video `bind` must prove knowledge of the same secret and
present that ID while the control channel remains active. Only one video channel can
bind at a time. Every video frame repeats the ID. A mismatch is rejected. Closing
the control channel cancels its video session; opening the receiver never starts the
phone camera. Android explicitly starts its foreground camera service.

## Control records

Each control record has a four-byte unsigned big-endian length followed by UTF-8
JSON. Length must be 1–4096 bytes; lengths are checked before allocation. Reads
continue until the declared length is complete. Unknown message types fail closed.

Phone `hello`:

```json
{"type":"hello","version":2,"codec":"h264","modes":["1280x720@30","1920x1080@30"],"mode":"1280x720@30","lens":"rear","secret":"<64 hex characters>"}
```

Receiver `welcome` contains `type`, `version`, `session` (32 hex characters),
`codec`, `mode`, and `fps`. The receiver accepts only protocol 2, H.264, one of the
two explicit 30 FPS modes, and `rear` or `front`. The selected mode must be in the
advertised list. Unsupported selections return an `error` record and close. There
is no silent fallback. Changing mode or lens requires an explicit phone Stop/Start
for this proof.

Phone video `bind` contains `type:"bind"`, `version:2`, `session`, and `secret`.
The receiver replies `ready` with the matching session ID. The receiver can send
`sync` on the control channel to request a new codec configuration/keyframe. The
phone requests an encoder sync frame via `MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`.
`ping`/`pong` are available for control liveness. Closing the phone session closes
both channels; an optional `stop` record also ends the control session.

## Video access units

All integers are unsigned big-endian. Each access unit has one 64-byte header and
exactly `payload length` bytes. The 4 MiB payload ceiling applies before allocation.
Header offsets are:

| Offset | Bytes | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII `CMYV` |
| 4 | 1 | major version `2` |
| 5 | 1 | minor version `0` |
| 6 | 2 | header length `64` |
| 8 | 4 | payload length, 1–4,194,304 |
| 12 | 8 | sequence number, increasing across the phone session |
| 20 | 4 | codec configuration generation |
| 24 | 8 | `MediaCodec.BufferInfo.presentationTimeUs` |
| 32 | 2 | exactly one flag: key `1`, codec config `2`, normal `4` |
| 34 | 2 | message type `1` (H.264 access unit) |
| 36 | 16 | authenticated logical session ID |
| 52 | 8 | Android `elapsedRealtimeNanos / 1000` immediately before write |
| 60 | 4 | reserved zero |

Payloads are Annex B H.264. The encoder emits SPS/PPS from MediaCodec output
format (`csd-0`/`csd-1`) or its config buffer. After connection/reset the phone
sends a `codec config` access unit immediately before a fresh keyframe. The receiver
stores that config and prefixes it to the first keyframe submitted to a fresh Media
Foundation H.264 decoder. Normal dependent pictures are ignored until that sync
point. Sequence gaps or configuration changes flush decoder state and trigger a new
sync request. Malformed framing closes the video connection; there is no magic-byte
resynchronization inside an unauthenticated byte stream.

## Bounds and freshness

The Android sender queue holds at most four access units and 8 MiB of payloads;
the oldest pending item may be at most 250 ms old. Overflow or stale age clears the
queue, closes the current video socket, requests a keyframe and reconnects. The
receiver decodes directly while reading, retaining at most one video access unit
in its parser. Control records are processed one at a time. The Android control
reconnect loop is limited to 20 attempts with 250 ms between attempts; TCP connect
and TLS handshake have a 1.5 s timeout. The sender does not replay an old queue
after reconnection. TLS/TCP may still have bytes in flight; network impairment must
be measured physically before claiming the roughly five-second recovery target.

The phone PTS and send timestamps are from Android clocks. Windows logs receive
and decoded-frame `Stopwatch` ticks. Android and Windows monotonic clocks are not
synchronized; subtracting their absolute values is not end-to-end latency. Local
queue age and Windows receive-to-decode time can be measured separately. A future
latency study needs a stated clock-offset or external measurement method.

## Decoded-frame seam

The native `camylla_mf_decoder` component submits H.264 to the inbox Media
Foundation decoder in low-latency mode. It requests NV12 output and invokes a synchronous callback
with decoded bytes, byte count, width, height, stride and a sample timestamp in 100 ns
units (from the Android PTS). The byte pointer is borrowed only until the callback
returns. Any future WinUI preview or virtual-camera publisher must copy the bytes
or establish a separately owned bounded frame buffer. This first proof uses a CPU
output path; D3D11 acceleration is deferred until the baseline works. No virtual
camera publication or GPU IPC is implied by increasing decoded-frame statistics.
