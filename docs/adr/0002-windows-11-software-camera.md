---
status: accepted
---

# Target the Windows 11 software virtual-camera path

Camylla targets Windows 11 and proposes Media Foundation's software virtual-camera API, conditional on proving frame delivery and format negotiation in Teams, Zoom, and OBS. Windows 10 support, DirectShow output, and kernel-camera-driver paths are deferred: broader compatibility would add integration and distribution obligations without a confirmed v1 requirement. This decision selects a proof target, not a claim that compatibility is already demonstrated.

## Considered options

DirectShow can be relevant for legacy consumers but would introduce another implementation path. A kernel camera driver may offer broader device integration but adds driver development, signing, installation, and support responsibilities. Neither is justified while the supported OS and target applications may work through the software-camera API.

## Consequences

Revisit if the target-app compatibility proof fails. Confirmed UX target: persistent current-user camera registration remains selectable when the receiver is unavailable and shows neutral offline output; camera activation never starts capture remotely on the phone. Verify the API lifetime/activation mechanics in the proof. COM media-source deployment, frame transport and application signing still require detailed design; software virtual-camera output does not automatically make installation privilege-free, and one explained installation elevation is accepted.
