# Camylla video wire protocol v1 draft

Status: implementation draft. This document specifies the first testable part of
the protocol only. Pairing, TLS identity, capability negotiation, controls and
reconnection still require separate review before end-to-end use.

The video connection carries H.264 access units over an authenticated TLS/TCP
connection. TLS provides confidentiality and peer authentication; this framing
does not. Both connections must eventually be bound to the same authenticated
camera session. A receiver must not accept this format on a plaintext socket.

## Byte order and frame layout

All integers are unsigned and use network byte order (big endian). Every access
unit has one fixed 36-byte header followed immediately by `payload_length` bytes.

| Offset | Size | Field | Rule |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `CMYV` |
| 4 | 1 | major version | `1`; reject other values |
| 5 | 1 | minor version | `0`; reject values newer than the receiver |
| 6 | 2 | header length | `36` |
| 8 | 4 | payload length | 1 through 4,194,304 bytes |
| 12 | 8 | sequence number | Increases by one for each access unit in a connection |
| 20 | 4 | configuration generation | Changes when the selected encoded mode changes |
| 24 | 8 | capture timestamp | Monotonic phone clock, in microseconds; not a desktop wall clock |
| 32 | 2 | flags | Bit 0 key frame; bit 1 codec configuration; all other bits zero |
| 34 | 2 | reserved | Zero |
| 36 | variable | payload | One complete H.264 access unit |

The 4 MiB limit is an initial safety limit, not a bitrate recommendation. The
sender must include SPS/PPS before dependent pictures after connection or decoder
reset. The exact codec-configuration convention will be fixed with capability
negotiation; consumers must not yet infer one from the draft flag alone.

## Receiver rules

- Buffer no more than one declared frame while parsing. Validate the complete
  header before waiting for its payload.
- Treat bad magic, unsupported versions/header size, zero or oversized payloads,
  unknown flags and non-zero reserved bits as fatal to this video connection.
- Never scan for magic after malformed input. Close the connection, reset the
  decoder and recover only through the authenticated reconnection procedure.
- Reject access units from an old configuration generation rather than decoding
  them with the current configuration.
- Sequence gaps require decoder recovery when the next unit depends on missing
  data. Dropping an arbitrary dependent H.264 unit is not a recovery strategy.
- The phone timestamp orders and measures the capture pipeline on the phone. Do
  not subtract it from a desktop clock to report end-to-end latency.

The portable parser in `src/protocol` implements the framing and allocation
limits. Session semantics and decoder recovery remain responsibilities of the
eventual desktop receiver.
