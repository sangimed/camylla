---
status: accepted
---

# Start camera sessions explicitly on the phone

Camylla remembers paired desktops, but trust alone never starts capture: the user opens the phone app and starts an active session. This trades unattended desktop reconnection/cold-start convenience for explicit consent and a lifecycle compatible with Android camera foreground-service restrictions. An already started session may continue during screen-off/app switching on tested devices; boot-start and unattended remote camera start are outside v1.

## Considered options

Automatic capture when a trusted desktop connects would shorten repeat setup but creates unclear consent and depends on restricted background camera starts. Requiring a new pairing for every session would add friction without improving clarity over explicit session start and revocable remembered trust.

## Consequences

Reconnection must distinguish an active interrupted session from a session the user stopped. It must never turn a deliberate stop into automatic capture.
