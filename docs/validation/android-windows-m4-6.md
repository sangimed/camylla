# M4.6 recovery UX — implementation and validation

Issue [#27](https://github.com/sangimed/camylla/issues/27) tracks the product
recovery path. This record distinguishes automated checks from physical
observations.

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
- Recent connection loss, rejected authentication, busy session and malformed
  connection attempts have bounded, current-state guidance on Windows. A
  successful authenticated start clears the old issue.
- Window close hides the desktop in the notification area while a phone capture
  may still be active, including after unexpected control loss. The notification
  icon opens the window or exits. Explicit Exit requests Stop on the authenticated control
  session, waits up to two seconds for the phone's acknowledgement, then retires
  the desktop session and neutralizes output. The phone cancels retries and
  capture upon a valid Stop request even if its acknowledgement cannot be sent.

## Automated checks

On 2026-10-07, Android `:app:testDebugUnitTest :app:assembleDebug --offline`
passed, including manual retry wakeup and clearing a recovered current error
while preserving diagnostic totals. The Windows desktop Debug build passed
with zero warnings or errors. The receiver `--pairing-self-test` passed,
including blocked-listener restart and a mutual-TLS desktop Stop request,
acknowledgement, session retirement and neutral frame store. `git diff --check`
passed.

The Xiaomi 2405CPCFBG Android 16 tablet was connected over ADB. The updated
APK installed successfully, and the Windows 11 25H2 desktop process started
with both 58413/58414 listeners. The tablet's capture foreground-service start
count was zero at this checkpoint; no physical streaming or visual PASS is
inferred from an idle or black screen.

## Physical checks still required

- Exercise Wi-Fi interruption, receiver restart, revoked trust, blocked
  listener, Stop and consumer open during outage on a paired physical setup.
- Confirm visible guidance, neutral output, fresh-frame resumption and absence
  of duplicate sessions under those interruptions.
- Confirm the notification icon can reopen the hidden window and explicit Exit
  stops the tablet's foreground capture on the current device build.
- Confirm Stop during phone recovery cancels further connection attempts. The
  automated recovery tests already cover the worker's cancel/wakeup behavior.

No recovery-time claim is made by this checkpoint. If the phone is unreachable
when desktop Exit occurs, Windows can retire its own session and neutralize
output but cannot prove that the phone stopped; the user must stop it locally.
