---
status: accepted
accepted: 2026-08-20
---

# USB Exclusive Hybrid uses one fail-closed session owner

## Decision

Hybrid starts from `0b6e982a` and implements a second real output path without importing P1's occurrence/permit/retirement/shadow coordinators. A single control executor owns permission, open, reconfiguration, close and facts publication. Request epoch validity and facts publication form one serialized protocol. Native I/O additionally requires the current `(epoch, sessionId)` at every submit/reap/resubmit boundary.

V1 is deliberately limited to the built-in Fosi SK02 profile, integer PCM16/PCM32, explicit DoP and explicitly experimental Native DSD for DSF. Unknown DACs, PCM24-only targets, float PCM, runtime quirk import, automatic recovery and silent Shared PCM fallback are rejected.

Output failures stop and report while preserving queue, position and requested mode. Recovery requires an explicit user retry or manual Shared PCM selection. Stack switching is synchronous break-before-make; a stuck old `release()` prevents a new USB open.

`exclusive`, `transportExact` and `signalExact` are separate facts. Native RAW_DATA descriptors only prove `FramingUnproven`; the SK02 Native profile remains experimental and cannot report `signalExact` until Hybrid-specific qualification evidence exists.

## Consequences

- This ADR supersedes ADR-0001's statement that Shared PCM is the only production path, but retains its stable-identity and exactness distinctions.
- ADR-0002's large P1 playback-protocol authority is not adopted by Hybrid.
- Existing P1/rewrite validation records guide tests but do not count as Hybrid PASS evidence.
- Every asynchronous side effect must revalidate the request epoch after waits and execute through the owner seam.
