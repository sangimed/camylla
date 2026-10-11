# Numeric pairing code and local discovery

## Decision

The default pairing path is one visible eight-digit field. Android sets a
numeric-only input type and digit filter so the system offers its numeric
keypad. The code remains visible while entered because it is a short-lived
pairing token, not a reusable password. The PC's QR scan remains available as
the alternate path.

The phone discovers a PC only while its pairing window is open. Discovery
carries a nonce and a code-derived HMAC proof, never the raw code. Android checks
the proof before pinning TLS to the discovered desktop identity; it then sends
the code over that pinned connection. Every code pairing still needs approval
on the PC. An empty discovery result gives a direct next step, and multiple
results require choosing the PC by its display name.

## Source-backed UX notes

- Android documents that `EditText`'s input type controls the keyboard and
  accepted content; `TYPE_CLASS_NUMBER` is the numeric class. A number-password
  field also shows a numeric keyboard, but masks input, which is unnecessary
  for this temporary pairing code. [Android `EditText` reference](https://developer.android.com/reference/android/widget/EditText)
- Android's native `NsdManager` implements DNS-SD/mDNS and is the standard
  Android discovery API. The current Windows app has no DNS-SD advertiser, so
  this feature uses a small request/reply discovery listener on a dedicated
  local UDP port rather than adding a discovery dependency. Its response is
  bound to the one-time code and nonce, and is not accepted as authorization
  without its HMAC proof and pinned TLS exchange. [Android `NsdManager` reference](https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager)

## Verification still required

Automated platform tests cover numeric parsing, cross-platform PBKDF2/HMAC
proof compatibility, invitation expiry, approval, TLS commit, and replay
rejection. Confirm on the Xiaomi tablet that the keyboard is digits-only and
that discovery works on the actual Windows firewall/Wi-Fi network before
calling the physical pairing check complete.
