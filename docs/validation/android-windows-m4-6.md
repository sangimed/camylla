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
with both 58413/58414 listeners. The initial idle observation had foreground
service start count zero. That observation alone is not a capture PASS.

## User-run tablet checks, 2026-10-07

The user performed the five guided checks on the Xiaomi tablet and reported
that **all five worked**:

1. Phone Start produced live preview on the tablet and Windows.
2. Closing the PC window with X kept the stream running in the notification
   area; the user reopened the window from the Camylla icon.
3. Turning tablet Wi-Fi off for about ten seconds and on again gave neutral
   PC output during loss and fresh video after recovery. The guide suggested
   **Retry now** only if it appeared; the user did not separately state whether
   it was used.
4. Phone Stop during a second Wi-Fi outage prevented capture from restarting
   when Wi-Fi returned.
5. After another phone Start, **Exit Camylla** on the PC stopped capture on
   the tablet.

This is the user's pass report, not independently inspected visual evidence.
No per-step state text, timing measurement or screenshots were supplied. The
running Windows build used for these checks had the tray and authenticated
Exit path. A later branch change retains tray behavior across an unexpected
control disconnect and is covered by the build and automated checks, but was
not in that running process.

## Physical coverage not yet recorded

- Receiver process termination/restart while the phone remains in a started
  session; the exact visible state sequence and duplicate-session check.
- Revoked/wrong trust and blocked listener/firewall guidance in the physical UI.
  Mutual-TLS rejection and blocked-listener restart pass in automated tests.
- Virtual-camera consumer open during an outage, including visual neutral
  output and fresh-frame resumption. The user checked the Windows preview,
  not a separate consumer in this report.
- The S21 Ultra-specific matrix requested by #27. This report covers Xiaomi
  2405CPCFBG and must not be presented as S21 validation.

Earlier physical records already cover receiver restart in
[M3](android-windows-m3.md), OBS neutral output and recovery during Wi-Fi loss
in [M4.2](android-windows-m4-2.md), and revoked/wrong trust admission in
[M4.4](android-windows-m4-4.md). Those records support the underlying transport,
publication and trust behavior; they do not establish the new M4.6 labels and
actions in those exact failure scenarios.

No recovery-time claim is made by this checkpoint. If the phone is unreachable
when desktop Exit occurs, Windows can retire its own session and neutralize
output but cannot prove that the phone stopped; the user must stop it locally.
