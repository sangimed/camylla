---
status: accepted
---

# Use WinUI for the desktop and keep native media behind a narrow boundary

Camylla proposes WinUI 3/C# for its standalone Windows interface and C++ for the Media Foundation/D3D/virtual-camera integration, conditional on deployment and preview proofs. This prioritizes Windows-native UI conventions and focused media ownership over a cross-platform framework requirement that v1 does not have.

## Considered options

WPF offers mature tooling and fewer Windows App SDK deployment dependencies. Qt/C++ reduces language diversity but introduces Qt distribution and Windows styling work. WinUI/C++/WinRT avoids a managed/native boundary at the cost of putting UI development into the more complex C++/WinRT/XAML stack. None is inherently a performance substitute for a measured media pipeline.

## Consequences

Interop and runtime packaging are explicit risks; prove preview and clean-machine installation early. One user process owns UI and receiving/decoding. The necessary camera source runs within Windows camera infrastructure through bounded access-controlled IPC; no additional Camylla daemon/service. Revisit if the proof reveals unacceptable maintenance/deployment cost.
