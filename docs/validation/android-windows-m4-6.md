# M4.6 recovery UX — first implementation checkpoint

Issue [#27](https://github.com/sangimed/camylla/issues/27) remains open. This
checkpoint improves the status and manual recovery path without claiming its
full physical acceptance matrix.

## Implemented

- The phone's main status follows the current connection attempt. It names
  Connecting, Authenticating, Negotiating, Streaming and Recovering, with a
  specific trust, listener/network or camera hint when available. Historical
  network-error totals remain in Diagnostics and do not drive the main banner.
- **Retry now** appears during a recoverable phone-started capture. It skips
  the current bounded backoff on the existing network worker. Stop still
  cancels the worker, clears its queue and capture authorization.
- The Windows preview names waiting for fresh video and video recovery, while
  keeping the preview neutral. A failed PC listener gets a specific banner and
  **Retry PC listener** action. Detailed counters move into Diagnostics.

## Automated checks

On 2026-10-07, Android `:app:testDebugUnitTest :app:assembleDebug --offline`
passed, including manual retry wakeup and clearing a recovered current error
while preserving diagnostic totals. The Windows desktop Debug build passed
with zero warnings or errors. `git diff --check` passed.

## Remaining #27 work

- Exercise Wi-Fi interruption, receiver restart, revoked trust, blocked
  listener, Stop and consumer open during outage on a paired physical setup.
- Confirm visible guidance, neutral output, fresh-frame resumption and absence
  of duplicate sessions under those interruptions.
- Implement the designed tray behavior for window close while streaming and an
  explicit Exit action that stops the session.
- Add focused transition coverage for desktop listener failure/retry and for
  Stop during phone recovery after the above flows are settled.

No physical validation or recovery-time claim is made by this checkpoint.
