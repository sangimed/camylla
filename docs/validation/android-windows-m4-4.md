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
| User scan and resulting paired screen | PASS, user confirmed | User confirmed the revised pairing flow with “c'est bon c'est mieux !” after the automatic QR dialog closure change. |
| Camera stream / preview after v3 pairing | PASS, user confirmed for OBS | User stated that streaming with OBS works and requested skipping a repeat of that test. No new counters, performance measurements or restart/revocation results are inferred from this confirmation. |
| Trust after desktop/Android process restart | PASS, automated hardware | `trust-after-restart-hardware-test-2.txt`; existing Keystore/DPAPI trust authenticated control and video without any invitation or new pairing |
| Revoke active control and video | PASS, automated hardware/UI Automation | `active-revocation-hardware-test-2.txt` and `active-revocation-desktop-action-2.txt`; desktop Revoke action closed both connections while control heartbeat and video configuration keepalive were active; no camera frames |
| Revoked peer reconnect and persistence | PASS, automated hardware | `revoked-reconnect-hardware-test.txt` and `revoked-after-desktop-restart-test.txt`; control/video admission refused, including after receiver restart |
| Missing/expired client and wrong server pin on S21 | PASS, automated hardware | `invalid-identities-hardware-test.txt`, three tests; both ports checked, including a genuinely expired certificate on a temporary nonexportable Keystore key |
| Fresh re-pair after revocation | PASS, automated hardware | `repair-after-revocation-test.txt` and `trust-restored-final-test.txt`; fresh one-use invitations restored trust through certificate proof; test authorization supplied privately over ADB, not an optical scan |
| Immediate bind/Stop acknowledgement | PASS after correction, automated hardware | Three subsequent successful runs in `quick-stop-regression-1.txt` through `quick-stop-regression-3.txt`; final receiver snapshot Disconnected, queue/buffers zero, decoder/network errors zero |
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

## Restart and revocation test findings

The first restart test authenticated successfully but hit an intermittent
`DECRYPTION_FAILED_OR_BAD_RECORD_MAC` while reading the Stop acknowledgement.
Evidence remains in `trust-after-restart-hardware-test.txt`. The video handler
could still be sending its control-channel sync when Stop cancelled that write.
Stop now acquires the session's control write gate, finishes any in-flight record,
cancels the session, and writes its acknowledgement under the same gate. Expected
transport interruption during session shutdown is not counted as a new network
failure. The original failed result is superseded only by the successful named
retries above; it has not been removed.

The first active-revocation test's video connection timed out because it sent no
media. Its result therefore did not prove revocation of a live video connection.
The second test keeps that connection alive with configuration records until the
desktop revokes it. No camera is opened and no frames are decoded. The successful
result is limited to authenticated session termination, not OBS visual behavior.

After the tests, fresh pairing restored the S21's trust. Capture remains stopped;
the next Start must still be performed by the phone user. No automatic capture,
identity rotation, upgrade migration, or full #25 acceptance is claimed here.

## Rotation implementation checkpoint (2026-10-04)

Windows and Android rotation and interrupted-rotation repair are implemented
on the feature branch, but are **not physically validated** yet.

- Windows desktop build: zero warnings/errors (`desktop-repair-build.txt`).
- Android debug APK, instrumentation APK and JVM tests build successfully
  (`android-rotation-repair-final-build.txt`).
- Windows TLS pairing tests pass, including changed-identity approval and
  atomic old-identity revocation/persistence (`windows-replacement-test.txt`).
- Windows real TLS rotation tests pass for phone and desktop identities,
  approval, proof-port media rejection, single-use proof, reconnect, lost
  acknowledgement, protected persistence, old-key rejection and expiry
  (`windows-repair-rotation-test.txt`).
- CI now runs the rotation self-test.
- S21 isolated Keystore rotation test is prepared but **not run**. ADB reports
  no attached device. New APKs have not been installed. Desktop remains closed.
- Full device reboot persistence, physical rotation confirmation and complete
  #25 acceptance remain pending. Existing OBS visual confirmation stands;
  these protocol tests do not establish new visual or streaming performance results.

No PR merge or full acceptance is claimed by this checkpoint.

## Xiaomi tablet rotation hardware result (2026-10-05)

Device: Xiaomi 2405CPCFBG, Android 16 / API 36, ADB serial d3ec6d70.
LAN: desktop 192.168.1.236, tablet Wi-Fi 192.168.1.193.
Both debug and instrumentation APKs installed successfully without clearing data.

The first attempt failed before pairing: traffic originated from VPN interface
10.111.3.51 and timed out. Its instrumentation, Windows and logcat evidence are
retained. After the user disabled the VPN, the same still-valid isolated receiver
invitation was used without restarting the receiver or clearing logs.

`tablet-rotation-instrumentation-no-vpn-20261005.txt` reports **OK (1 test)**.
The opt-in isolated test passed Android Keystore phone-key rotation, lost
`rotate_ok` recovery after protected-state reload, retirement of the old test key,
Windows server-key rotation, old server-pin rejection, and control/video binding
and Stop with both new identities. The real app identity remained unchanged.
Windows evidence: `tablet-rotation-windows-20261005.txt`; Android logcat evidence:
`tablet-logcat-rotation-no-vpn-20261005.txt`.

This is tablet protocol evidence, not an S21 rotation result. The harness explicitly
approved isolated test proposals; normal product confirmation UI, optical QR scan,
full device reboot persistence and complete #25 acceptance remain pending.
No camera or OBS visual PASS is inferred from this test.

The user confirmed optical QR pairing on the tablet. Automatic QR-dialog closure
has not yet been explicitly visually confirmed. Normal product trust committed
in `receiver-v3-20261005-184344-33f168ef.log`. The follow-up instrumentation test
`rememberedTrustConnectsWithoutInvitation` passed **OK (1 test)** in
`tablet-remembered-connection-20261005.txt`: nonexportable Android key,
TLS 1.3 control/video authentication, one-use video binding and Stop without
opening the camera. This does not claim a new visual preview or frame-rate result.

## Normal tablet phone-key rotation and app restart (2026-10-05)

The user reported confirming the desktop key-change dialog after initiating the
phone key change. Product receiver evidence records mutual-TLS proof of new
phone identity `f80d710ccfbaf723aafcf9f3e83709f6e26a3061f71316475aa7c3fd72781593`
and durable commit/previous-identity retirement at 18:46:06 UTC. Previous ID:
`3c87f8fb0fbdf0a4b69b1efb77c9902e644e018ca57d9b686f3ae0711d049605`.
Evidence preserved in `tablet-ui-rotation-receiver-20261005.txt` and associated CSV.

`tablet-after-ui-rotation-20261005.txt` passed **OK (1 test)** for remembered
control/video authentication and Stop with the new identity. After graceful
Windows desktop close/reopen and Android app force-stop, the same test passed
again (**OK (1 test)**, `tablet-ui-rotation-app-restart-20261005.txt`). Android
process restart and desktop restart preserve the changed identity and trust.
A full tablet reboot and normal desktop-key-change confirmation remain pending.
No actual camera preview is inferred; capture stayed stopped throughout.

## Deferred full Android reboot validation

At the user's request on 2026-10-05, full Android device reboot and first-unlock
persistence was removed from the blocking acceptance scope of #25 and moved to
[follow-up #34](https://github.com/sangimed/camylla/issues/34), with low priority.
#25 now requires app-process and Windows receiver restart persistence. Existing
references above to a pending full reboot remain historical test status: the check
is still **not tested**, but no longer blocks #25. Other #25 acceptance criteria
are unchanged, and this scope change does not itself establish full acceptance.

## Normal desktop-key rotation with tablet confirmation (2026-10-05)

The user confirmed the key change on the tablet. Windows retired desktop identity
`d59ab0121924c0a7046412d68215daa527347eea0b23feb73914dfbfa249a335`
and committed `1f1dcb011a7742b41170f277d055edb4e6ab068de118cdef2a1b87ae7f62c110`
at 18:52:24 UTC, after mutual-TLS proof. Evidence:
`tablet-desktop-key-rotation-receiver-20261005.txt`.

Remembered control/video connection and Stop passed **OK (1 test)** immediately
(`tablet-after-desktop-key-rotation-20261005.txt`) and after graceful desktop
close/reopen plus Android app-process restart (`tablet-both-keys-restart-20261005.txt`).
The tablet is paired under both new identities. The S21's previous desktop trust
now requires fresh pairing, as disclosed before confirmation.

The idle desktop label was clarified to `Phone paired · Capture stopped` and
verified through UI Automation; its build had zero warnings/errors. This indicates
saved trust and stopped capture, not continuous network presence. The tablet UI
reported readiness to start. User visual confirmation of automatic QR-dialog
closure remains unrecorded; no additional OBS preview claim is made.
