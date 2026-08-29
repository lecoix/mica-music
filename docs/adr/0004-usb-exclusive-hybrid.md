---
status: accepted
accepted: 2026-08-20
---

# USB Exclusive Hybrid uses one fail-closed session owner

## Decision

Hybrid starts from `0b6e982a` and implements a second real output path without importing P1's occurrence/permit/retirement/shadow coordinators. A single control executor owns permission, open, reconfiguration, close and facts publication. Request epoch validity and facts publication form one serialized protocol. Native I/O additionally requires the current `(epoch, sessionId)` at every submit/reap/resubmit boundary.

Hybrid selects any single attached USB Audio device that exposes an isochronous Audio OUT endpoint. Multiple USB Audio output devices are ambiguous and fail closed rather than choosing an arbitrary first device. Stable identity uses VID/PID, bcdDevice/version and endpoint topology; permission-gated product strings are diagnostic metadata, not identity authority.

Exact PCM is capability-driven for integer PCM16/PCM24/PCM32. The imported reference packetizer may losslessly widen samples into a wider USB subslot/resolution, but precision reduction, float PCM, SRC/DSP and silent Shared PCM fallback are rejected. DoP is descriptor-driven. Native DSD remains explicit and follows the imported reference rule: an explicit reviewed quirk wins; otherwise one unambiguous UAC2 RAW_DATA subslot maps to `u8`/`u16le`/`u32le`. Ambiguous or unsupported RAW_DATA widths remain `FramingUnproven`, and vendor/chip identity is never used to guess framing.

Output failures stop and report while preserving queue, position and requested mode. Recovery requires an explicit user retry or manual Shared PCM selection. Stack switching is synchronous break-before-make; a stuck old `release()` prevents a new USB open.

Playback presentation consumes a coordinator-owned projection rather than the reducer phase itself. `SharedActive` and `ExclusiveActive` project to `STABLE`; quiesce/prepare/open/shared-route recovery project to `SWITCHING`; permission, device wait, reconnect-required and failure remain distinct. The process monitor owns a monotonic publication revision and a unique publisher identity per coordinator, so a retired coordinator cannot publish over its successor. This output state remains orthogonal to Media3 execution state and the frozen semantic play intent.

`exclusive`, `transportExact` and `signalExact` are separate facts. Descriptor-inferred or quirk-provided Native framing does not by itself prove audible/signal exactness; each active device/path remains experimental until Hybrid qualification evidence exists and must not imply `signalExact` merely because framing resolved.

## Consequences

- This ADR supersedes ADR-0001's statement that Shared PCM is the only production path, but retains its stable-identity and exactness distinctions.
- ADR-0002's large P1 playback-protocol authority is not adopted by Hybrid.
- Existing P1/rewrite validation records guide tests but do not count as Hybrid PASS evidence.
- Every asynchronous side effect must revalidate the request epoch after waits and execute through the owner seam.
