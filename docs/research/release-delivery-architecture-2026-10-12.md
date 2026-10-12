# Release and delivery architecture (2026-10-12)

## Decision

Ship a **signed alpha**, not a beta, after adding a deliberately small release
pipeline. The product is marked "early implementation" in the README, M4
product-alpha is still the active milestone, and the current evidence is a
focused OBS validation rather than a release installer/upgrade validation.
Calling the first public, opt-in build `v0.6.0-alpha.1` accurately sets
expectations while still giving real users a safe installation path. Promote a
later build to beta only after the release gates below have succeeded on the
actual signed artifacts, including install, upgrade, uninstall, virtual-camera
registration/removal, pairing, streaming, and focused recovery in OBS.

Use one protected `v*` release tag as the immutable release input. A release
workflow builds from that exact commit, produces all artifacts, verifies them,
then signs and publishes only after the protected `release` environment has
been approved. Do not build separately for the download page: the downloaded
files must be the ones tested, hashed, signed, attested, and published.

## Present repository constraints

- `.github/workflows/ci.yml` currently runs builds and automated tests on pull
  requests and `main`, with least-privilege `contents: read`; it has no
  release trigger, artifact retention/upload, signing, packaging, checksum,
  SBOM, provenance, or publish step.
- Android builds only `assembleDebug` and JVM tests. Its current identity is
  `org.camylla.proof`, version `0.5-simple-pairing`, and it has no `signingConfigs`
  or release build configuration (`src/android/app/build.gradle.kts`).
- The desktop project is x64, unpackaged (`WindowsPackageType=None`) and
  Windows App SDK self-contained. It is therefore a viable basis for a ZIP,
  but there is no reproducible `dotnet publish`/staging layout yet.
- The virtual-camera source is an in-process COM DLL. The supplied control tool
  writes `HKLM\\Software\\Classes\\CLSID` so Windows Frame Server can activate
  it; its documented register/unregister operations require elevation. Its DLL
  path must remain stable. See `docs/windows-virtual-camera-poc.md`.
- No installer, MSIX package project, release version source, root license
  file, or signed-artifact verification currently exists.

## Release outputs

Publish all outputs from the same tag, with a source archive, `SHA256SUMS`,
SBOMs, provenance, and concise release notes with known limitations.

| Output | Delivery choice | Notes |
| --- | --- | --- |
| `Camylla-<version>-android.apk` | Release-signed APK for alpha testers | Direct download initially; later publish the same release configuration as an AAB to Play testing. |
| `Camylla-<version>-windows-x64-setup.exe` | Bootstrapper/installer | Installs the desktop files to a fixed machine path, runs elevated virtual-camera registration, and has an uninstall path which removes the camera before deleting its DLL. |
| `Camylla-<version>-windows-x64-portable.zip` | Self-contained unpackaged publish directory | Contains desktop, receiver, control tool and camera DLL at a fixed extracted directory. Its first-run/documented `register` action still requires UAC elevation; moving/deleting the folder while the camera remains registered is unsupported. It is portable for the application, not a no-privilege virtual-camera install. |

The installer is the supported product path because the virtual camera requires
machine-wide COM registration. The ZIP is useful for advanced users who choose
not to install the application, but it cannot honestly promise a fully portable
camera integration under the current deployment model.

For a normal WinUI app, Microsoft maps an unpackaged, self-contained app to
Xcopy/ZIP distribution, while direct download with WiX/Inno uses unpackaged or
external-location packaging; self-contained deployment carries the Windows App
SDK dependencies. [Windows packaging and deployment options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/).

## Windows packaging and signing

Keep the first alpha on the unpackaged installer + ZIP route. It fits the
existing desktop project and the COM/Frame Server deployment requirement.
Evaluate MSIX as a *second* channel only after proving that its identity,
container and installer model support the privileged camera-registration
lifecycle. MSIX is excellent for clean install/update/uninstall, but it does
not by itself remove the existing machine-wide COM and elevation requirement.

Sign every shipped PE file, not just the outer installer: desktop/receiver/control
EXEs, native decoder DLL, virtual-camera COM DLL, setup/uninstall executables,
and any helper executable. Use SHA-256 plus an RFC 3161 timestamp, verify each
signature after signing, then produce ZIP/checksums. Windows recommends signing
all binaries for Smart App Control; it may block unknown unsigned code.
[Smart App Control](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/overview)
and [Authenticode timestamping](https://learn.microsoft.com/en-us/windows/win32/seccrypto/time-stamping-authenticode-signatures).

For public distribution, choose Microsoft Azure Artifact Signing (formerly
Trusted Signing) if the project can pass its identity requirements; Microsoft
recommends it for non-Store production distribution and it avoids exporting a
private signing key to CI. A conventional OV code-signing certificate in a
hardware/cloud HSM is the fallback. Self-signed certificates are only suitable
for closed testing because each tester must explicitly trust them. Do not pay
for EV solely to bypass SmartScreen: Microsoft says EV no longer does so.
[Microsoft's signing guide](https://learn.microsoft.com/en-us/windows/msix/package/sign-msix-package-guide)
and [SmartScreen reputation guidance](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation).

The first signed downloads can still receive a SmartScreen warning while
publisher/file reputation develops. Store delivery is the most reliable later
way to avoid those download warnings, but should not delay the alpha.

## Android signing and distribution

Add a deterministic `release` variant and version injection from the tag. For
direct alpha distribution, sign the APK with a stable, dedicated release key
kept outside the repository. Android requires APKs to be signed to install or
update. When Play testing is introduced, upload a signed AAB and opt in to Play
App Signing: CI holds only the upload key and Google holds the app-signing key.
If the same app must update both Play and non-Play installs, establish that key
ownership and migration plan before the first public release.
[Android app signing and Play App Signing](https://developer.android.com/studio/publish/app-signing).

Use a protected GitHub environment for the Android key material. A KMS/HSM
backed signing service is preferable to a base64 P12 in GitHub Secrets when it
becomes practical; never expose release secrets to pull-request workflows or
untrusted forks.

## GitHub Actions architecture

1. **PR CI (unprivileged):** retain current Android/Windows builds and tests;
   add formatting/static analysis, dependency/license review, and packaged
   release-layout smoke checks without signing credentials.
2. **Main CI (unprivileged):** same checks plus produce disposable candidate
   artifacts and exercise portable-layout launch/self-tests where possible.
3. **Release candidate:** protected tag triggers a build from the tag SHA,
   uploads immutable unsigned candidates, verifies version consistency and
   SHA-256, then waits at `release` environment approval. Limit deployment to
   protected tags/`main`, require a human reviewer, and serialize releases.
4. **Signing/publish (privileged):** download the exact candidate, sign and
   timestamp Windows binaries and sign Android release output, verify all
   signatures, generate final hashes/SBOM/provenance, attach assets to a draft
   GitHub Release, run the manual OBS gate, then publish it.

Use least-privilege job-specific permissions. Pin third-party actions by full
commit SHA rather than mutable tags. GitHub documents both practices.
[Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use).

For Azure signing, authenticate CI with GitHub OIDC and a narrowly scoped Azure
federated credential rather than a long-lived Azure client secret. The signing
job needs only `id-token: write` plus its ordinary read/publish permissions;
the Azure trust policy must bind repository, protected tag/ref, environment,
audience and, where supported, the reusable workflow identity. OIDC gives the
job a short-lived token and does not itself grant cloud-resource access.
[GitHub OIDC reference](https://docs.github.com/en/actions/reference/security/oidc).

Generate provenance and an SPDX or CycloneDX SBOM for each final downloadable
artifact, then use GitHub artifact attestations and document `gh attestation
verify` in the release notes. This requires `contents: read`, `id-token: write`
and `attestations: write` on the attestation job. [GitHub artifact
attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).

## Alpha and beta gates

| Gate | Alpha | Beta promotion |
| --- | --- | --- |
| Source | Protected tag, clean reproducible build, all current CI green | Same, including release dry-run on a second runner |
| Supply chain | Checksums, signed Windows binaries, signed Android APK, SBOM and provenance | Same, with `gh attestation verify` recorded and signing failure/retry exercise |
| Windows | Clean install/uninstall; elevation, virtual camera register/remove; launch and rollback on supported Windows 11 x64 | Upgrade from prior alpha, portable ZIP extraction/removal path and signed binary verification |
| Android | Fresh install, pairing and basic camera start/stop on declared device support | Upgrade from alpha and release-key continuity check |
| Product | Focused pair/stream/stop/recovery validation in OBS, per project policy | Repeat focused OBS functional/recovery checks on the signed release artifacts |
| Operations | Draft release, known-issues/support/rollback instructions | Publish only after beta sign-off; no fixed-duration endurance session required |

The existing project policy is correctly reflected here: consumer validation
targets OBS and should be focused functional/recovery evidence, not an
arbitrary elapsed-time interactive test. Keep results in the validation record,
not in visual captures unless explicitly authorized.

## Implementation order

1. Add a release-version source and reproducible Android release build plus
   Windows `publish` staging scripts; make the ZIP contents deterministic.
2. Add installer/uninstaller design and validate the current virtual-camera
   registration lifecycle from an installed fixed path.
3. Add unprivileged release-candidate CI, hashes, SBOM and provenance.
4. Set up protected GitHub environments and OIDC-backed Artifact Signing;
   implement sign/verify/publish as a separately permissioned workflow.
5. Run an internal signed dry run, then publish `v0.6.0-alpha.1`; do not update
   the roadmap until a milestone-level release change is merged and verified.
