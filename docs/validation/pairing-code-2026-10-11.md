# Simplified numeric pairing validation

Status: automated and physical pairing validation passed.

## Behavior

- Windows generates an eight-digit, one-use code with a 120-second lifetime.
- Android defaults to one digits-only code field with the numeric keypad; QR
  scanning remains an alternate path.
- Android discovers the ready PC on the local network. Discovery authenticates
  the response with a code-derived HMAC before the app sends the code over
  pinned TLS. The phone asks for no PC address or fingerprint.
- Windows requires explicit approval for each code pairing. Pairing never
  starts capture. When multiple PCs are ready, Android asks the user to choose
  by PC name.

## Automated checks

- Android debug APK, JVM tests, and instrumented-test Kotlin compilation passed.
  JVM tests include a cross-platform PBKDF2/HMAC discovery proof vector and
  numeric code validation.
- Windows pairing self-test passed, including eight-digit code generation,
  discovery proof, PC approval, mutual-TLS commit, and replay rejection.
- Windows desktop build passed with zero warnings and errors.
- `git diff --check` passed.

## Physical check

- Installed `0.5-simple-pairing` (version code 5) on Xiaomi tablet
  `2405CPCFBG`, Android 16. APK SHA-256:
  `a6d69145174a68bb99de74b0251fcd905450a182dd7048c0ad9084e4de25c3ac`.
- Verified the Android pairing screen exposes only one code field and a QR
  alternative. Focusing the field opened Gboard's numeric keypad.
- Entered the eight-digit code without typing a PC address or fingerprint.
  Android discovered `DESKTOP-17OM3UK`; Windows showed the approval prompt. After
  approval, Android returned to Connections and displayed “Paired with
  DESKTOP-17OM3UK. Ready to start.”
- Capture remained stopped. Windows UI Automation confirmed the normal pairing
  dialog shows the code and QR without address or fingerprint fields.
- Confirmed the Windows UDP discovery listener bound successfully to port
  41415; the prior candidate port 58415 is excluded on this Windows host.
