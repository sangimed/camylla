# M4.2 local virtual-camera IPC v1

This contract connects `Camylla.Desktop` to the existing M1 Media Foundation camera source. Android capture remains explicitly started on the phone. The source DLL never receives network data or runs a decoder. The desktop's single receiver decoder publishes the same `OwnedNv12Frame` lease to independent preview and IPC readers.

## Primitive and access

The desktop creates up to four instances of the Windows byte-mode named pipe `\\.\pipe\CamyllaFrameV1`. The first instance uses `FILE_FLAG_FIRST_PIPE_INSTANCE`, so an already claimed pipe name causes publication to fail rather than joining an untrusted server. The source opens it as a client. Pipe instances use `PIPE_REJECT_REMOTE_CLIENTS`. The DACL grants access only to the interactive desktop process's user SID, SYSTEM, and the `NT SERVICE\FrameServer` service SID. There is no NULL DACL, Everyone, or broad LocalService ACE. The desktop never accepts a peer based on a client supplied name. The source checks the pipe server process token's user SID against the active local console user's SID before accepting a response; a server in another session or under another user fails neutral. The trust boundary is the active console user account, so another process under that same account is trusted. RDP and multiuser switching need separate validation before support is claimed. The current machine reports `FrameServer` runs as `NT AUTHORITY\LocalService` with an unrestricted service SID; source startup logs its actual process token user SID to the Windows debug output. Physical source activation must confirm the service SID is present in the token and the pipe open succeeds. A different host identity would require a deliberate ACL update and validation, not a fallback to a broad principal.

There is no daemon. The desktop is the sole pipe producer. The camera source may open before the desktop, disconnect, or reopen at any time. A source request does not start Android capture. Each camera source has at most one outstanding request; the pipe has no frame queue. A request is ASCII `CMY1` (4 bytes). Every response starts with a 72 byte, little-endian header. A live response is followed by exactly `byteLength` NV12 bytes. A neutral response has no body.

| Offset | Type | Field |
| --- | --- | --- |
| 0 | 4 bytes | `CMYF` magic |
| 4 | u16 | version, exactly 1 |
| 6 | u16 | status: 0 neutral, 1 live |
| 8 | u32 | width |
| 12 | u32 | height |
| 16 | u32 | source stride, even and at least width |
| 20 | u32 | NV12 byte length, exactly stride × height × 3 / 2 |
| 24 | 16 bytes | random generation, changed on desktop start or receiver session change |
| 40 | u64 | sequence, incremented for each owned decoded frame made current |
| 48 | i64 | phone media PTS in 100 ns units |
| 56 | u64 | monotonic capture time from Windows `GetTickCount64`, in milliseconds |
| 64 | 8 bytes | reserved zero |

Live frames accept only 1280×720 or 1920×1080 at the negotiated camera mode, even stride from width through 4096 bytes, and at most 6,635,520 bytes. The native consumer validates the header, version, dimensions, even stride, exact byte count, nonzero generation/sequence, and freshness **before** reading the body. It rejects timestamps more than 50 ms in the future or more than 500 ms old. It checks sequence order after draining a valid body; an older sequence yields neutral output. The desktop also checks decoded frame age before replying. The native source copies each source row into the tight NV12 layout it already advertises to Media Foundation; source stride can be padded. The source uses its M1 cadence and `MFGetSystemTime` for delivered sample timing while preserving phone PTS in the IPC header for diagnostics.

## Lifetime and failure

The receiver callback copies borrowed Media Foundation output once into the pooled, reference-counted owned frame. Preview and publisher hold independent leases. The publisher's reader has one pending frame; a background pump replaces its one current lease. A slow consumer does not delay decode or build a backlog. Each pipe response holds a temporary lease until its write completes or fails. On Stop, disconnect, session change, or desktop shutdown the publisher releases the current lease. The source paints neutral NV12 (Y=16, UV=128) whenever no fresh live response is available, the requested mode differs, a pipe operation fails, the producer disappears, or the producer has not yet started. A whole request, including its payload, has a 300 ms deadline; a broken connection closes and later requests reopen it. The camera source never retains a previous phone frame as fallback.

The producer has a new random generation at desktop start and on receiver session transition. Sequence restarts with that generation. The consumer accepts a new generation and rejects a sequence decrease within one generation. Pipe disconnect clears the consumer's generation state. These fields distinguish a restarted desktop from an older frame; wall-clock time is not used for freshness.

For deterministic offline M1 source bring-up, `CAMYLLA_SYNTHETIC=1` in the *camera source host process* selects the existing synthetic provider. Its core unit test remains unchanged. Normal connected operation selects IPC and neutral output when the desktop is absent.

## Validation limits

Native parser tests cover both supported sizes, padded strides, malformed version, invalid stride, oversized length, reserved bytes, and stale/future timestamps. The receiver self-test exercises a real local pipe: neutral before capture, full 720p NV12 transfer, Stop to neutral, and consumer reopen. Native and .NET builds, CTest, receiver self-test, decoder smoke, and WinUI build are automated checks. They are synthetic/local evidence. Physical Windows Frame Server activation, its actual service SID group, OBS/Discord image quality, S21 Ultra 720p/1080p cadence, Wi-Fi recovery, desktop restart, and unauthorized-peer rejection from a separate principal remain physical/integration gates. No physical pass is claimed here.
