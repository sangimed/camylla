---
status: accepted
---

# Evaluate authenticated TLS/TCP before adopting a larger media stack

For one phone on a local network, Camylla will first evaluate separate authenticated TLS/TCP video and control connections with bounded buffering, versioned framing and fresh-keyframe recovery. This is a proof target, not a claim that reliable byte delivery meets real-time latency requirements. Adopt a mature real-time stack if packet-loss/recovery tests defeat the core 30-FPS latency/reliability gates.

## M3 physical decision

The [M3 validation record](../validation/android-windows-m3.md) reports physical
S21 Ultra rear 720p30 H.264 streaming over authenticated TLS/TCP to continuous
Windows Media Foundation NV12 decode. Received access units increased by about
150 every five seconds; decoder errors/resets remained zero in the recorded
healthy post-recovery sequence. Sender and receiver queues stayed bounded.
Receiver process restart and phone Wi-Fi off/on recovery passed after PR #18
corrected retry/session renewal and PR #19 corrected heartbeat cadence. The
first Wi-Fi attempt failed and remains documented.

TLS/TCP is accepted for continued Camylla development. This decision does not
claim a measured cross-device end-to-end latency, exact Wi-Fi recovery duration,
full impairment matrix, or release gate pass. Revisit the transport if a later
defined measurement exposes a concrete failure.

## Considered options

WebRTC supplies secured media and congestion/loss handling but adds native build, signaling and integration obligations. QUIC reliable streams do not eliminate delay within a lossy video stream; datagrams would still need media fragmentation/pacing/recovery. Unencrypted TCP/UDP fails the agreed LAN threat model. Camera discovery grants no trust; the phone scans a short-lived desktop pairing QR, and remembered identity does not start capture.

## Consequences

Separate sockets prevent video backlog from blocking control, but do not fix video head-of-line blocking. Bound queue age/bytes and measure actual freshness, resetting to a fresh codec configuration/keyframe when needed. Bind both sockets to one authenticated session. Detailed pairing/key rotation must be reviewed before implementation; do not invent cryptography. Optional 60 FPS must not force a larger stack if core 30-FPS behavior passes.
