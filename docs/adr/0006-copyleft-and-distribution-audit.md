---
status: accepted
---

# Use copyleft for Camylla and audit the distributed artifacts

Camylla selects GPL-3.0-or-later for its own source so distributed modifications remain open, preferring compatible permissive dependencies and avoiding paid/proprietary companion requirements. Exact runtime/package redistribution and license compatibility must be reviewed before releases: source repository licenses alone do not settle shipped Windows binaries.

## Considered options

A permissive project license would permit closed distributed forks, contrary to the accepted objective. GPL-2.0-only reference code cannot be assumed compatible with the selected project license; exact file notices/upgrade permissions are required before code reuse. Studying architecture does not by itself approve copying implementation.

## Consequences

Pin dependencies/artifacts and preserve required source/notices. Review Windows App SDK, .NET runtime/apphost, native runtime and installer contents separately. Signing eligibility is also package-dependent and not guaranteed. Change dependency packaging or revisit the ADR explicitly if a conflict appears; never quietly infer permission from an open-source label.
