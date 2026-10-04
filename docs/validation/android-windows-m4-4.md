# M4.4 pairing implementation — partial validation

Status: **in progress; issue #25 is not accepted yet**. Branch
`feat/m4-4-production-pairing` started from main `9354152` (PR #33 merged).

## 2026-10-04 hardware findings

- Windows 11 Pro, build 26200; LAN IPv4 `192.168.1.236`.
- Samsung S21 Ultra SM-G998B, Android 15 / API 35; authorized ADB connection.
- Android build `0.2-pairing`, version code 2. APK SHA-256:
  `11ed24a337324530f5226e802a2539ec99c7429cd6a1d8ac0ca694740fc68df2`.
- Control and video ports: 58413 and 58414.

The first Windows launch failed in WinUI (`0xc000027b`). Its event evidence was
preserved before correcting the missing application control resources and adding
unique startup failure files. Subsequent launches succeeded.

Initial S21 pairing reached `pair_challenge`, but mutual TLS failed. The automatic
hardware test reproduced the failure and Android Keystore reported
`INCOMPATIBLE_DIGEST`. Conscrypt requests a raw ECDSA signature after hashing the
TLS transcript, so the signing key needs both `DIGEST_NONE` and `DIGEST_SHA256`
authorization. The prototype key had only the latter. See the
[Android platform key protection documentation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-wear-wear-input-release/android/security/keystore/KeyProtection.java)
and [Conscrypt signing implementation](https://android.googlesource.com/platform/external/conscrypt/+/edf7055/src/main/java/org/conscrypt/CryptoUpcalls.java).

The corrected identity uses a new nonexportable Keystore alias; the old prototype
key was retained. At the time of this correction the phone had no trust store:
all earlier attempts had failed before committing phone trust. This is not a
general migration or rotation result.

## Evidence and results

| Check | Result | Evidence / scope |
| --- | --- | --- |
| Android build and JVM tests | PASS, automated | 25 tests, zero failures/errors |
| Desktop build | PASS, automated | Zero warnings/errors in final UI build |
| Windows pairing regression | PASS, automated | One-use/expiry, mutual TLS, repeat trust, stale/duplicate video binds, Stop, revocation, wrong identities |
| S21 pairing with Windows | PASS, automated hardware | `hardware-tls-third-test.txt`; authenticated commit; no camera capture |
| S21 control and video TLS 1.3 | PASS, automated hardware | `hardware-control-video-test.txt`, completed `2026-10-04T17:04:00Z`; both channels authenticated; video bind and Stop acknowledged; no camera capture |
| Private key exportability | PASS, automated hardware | `key_nonexportable=true`, provider `AndroidOpenSSL`; no exported key or development identity used |
| Minimal Android scanner content | PASS, automated runtime | `phone-minimal-screen-test-2.txt`, completed `2026-10-04T17:07:00Z`; scanner title present; no visible manual inputs or approval/Finish buttons in app content |
| Desktop QR dialog | Verified by UI Automation | Dialog content visible; connection options collapsed; does not depend on a dismissible button flyout |
| Close QR dialog after pairing | PASS, automated hardware/UI Automation | `qr-autoclose-hardware-test.txt` and `qr-autoclose-ui-confirmation.txt`; S21 pairing succeeded and the dialog disappeared after its close animation. Closure is tied to the displayed invitation's successful trust commit, not merely decoding a QR or finding an existing trusted phone. |
| User scan and resulting paired screen | **PENDING** | No user confirmation for the rebuilt UI yet |
| Camera stream / preview after v3 pairing | **PENDING** | Protocol-only hardware tests do not prove visual streaming |
| Restart, revoke, missing/expired client, wrong server on S21 | **PENDING** | Windows regression results do not replace the physical gate |
| Identity replacement and rotation transaction | **NOT IMPLEMENTED** | Required before issue #25 can pass |

Local evidence is preserved under
`D:\Repositories\camylla\validation-artifacts\m4-4`. Failure logs were not cleared
or overwritten by successful retries. Full logcat, UI dumps and invitation test
files remain local; they must not be published as validation attachments.

## Pairing UX revision requested by the user

Opening the PC QR and scanning it on the phone now authorize pairing. The phone
automatically completes the one-use capability challenge and the second pinned
mutual-TLS connection. There is no separate PC approval or phone Finish action.
Scanning never starts capture.

Android opens directly into its scanner. Manual entry is behind “Can't scan?”.
Camera settings and diagnostics are collapsed on the main screen, and capture
controls are hidden until a desktop is paired. Windows shows a persistent QR
dialog with connection details behind an expander. This revises the local approval
policy in the v3 design; the protocol bounds and certificate proof remain required.

## Reproduce the opt-in protocol hardware test

Stop the desktop receiver after preserving its logs. Build the receiver, Android
debug APK, and Android test APK; install both APKs without clearing app data.
The explicit receiver command `--android-pairing-self-test <fresh-local-file>`
creates a temporary invitation and listens for at most 120 seconds. Privately copy
that invitation into the app's `files/pairing-invitation-test` using ADB `run-as`,
then run only
`PairingHardwareTest#pairWithNonexportableKeystoreIdentity` via instrumentation.
The test consumes the file, pairs, authenticates both channels, binds video without
camera frames, and sends Stop. It does not validate the optical QR scan or preview.

The separate instrumentation test
`PairingHardwareTest#scannerHasNoVisibleManualOrApprovalSteps` checks the installed
app's scanner content. If keyguard covers the window, that result is a content
check, not visual confirmation that the user saw the scanner.
