# SylvaKru USB transport (vendored and adapted)

This module contains USB-exclusive playback code copied from, extracted from,
or behaviorally adapted from the SylvaKru USB-exclusive implementation. It is
distributed under the Apache License 2.0.

## Provenance

- Audited reference repository: `https://github.com/huya688zdx/sylvakru.git`
- Reference snapshot used by Mica: commit
  `3f2578692499e403d7eddc6fdbe52d1b6a1b2206`
- The reference fork states that it is based on the original
  `AfalpHy/sylvakru` project.
- Reference license: Apache License 2.0.
- The reference `LICENSE` identifies `Copyright 2025-2026 AfalpHy`.
- Initial Mica import: 2026-08-20; subsequent adaptation/audit continued
  through 2026-08-25.

The exact Apache-2.0 license text shipped by the audited reference is retained
as [`LICENSE`](LICENSE). The reference snapshot contains no separate upstream
`NOTICE` file. Mica's attribution and modification notice is in [`NOTICE`](NOTICE).

## What is copied and what is Mica-specific

The current implementation intentionally does **not** claim that the whole
module is byte-for-byte upstream code.

Reference-derived areas include:

- DSD parsing/packetization rules (`UsbDsd.kt`);
- USB diagnostics and DAC quirk handling;
- stream-transition/fade/pre-roll rules;
- USB volume protocol and hardware-volume primitives;
- standard UAC and iBasso/Macaron hardware-volume behavior;
- endpoint/clock/format target resolution;
- PCM ISO packetization and parts of the native USB submission engine;
- session telemetry, hot-reuse and related transport behavior.

Some files keep the reference implementation body nearly or completely intact
while adding only Mica attribution or small compatibility extensions. Other
reference engine functions were split and reorganized into Mica transport
classes to fit Media3.

Mica-specific areas include, among other things:

- Media3 `AudioSink` / DSD renderer integration;
- the Hybrid session owner and output state machine;
- epoch/session ownership fencing and stricter stale-session rejection;
- the newer libusb stream service/FIFO/source-clock infrastructure;
- Shared PCM return/quiescence logic and Mica settings/UI;
- physically qualified Mica-only device extensions such as the SK02 Native
  DSD256 cold-entry prime and later streaming reset-alt handling.

The function-level audit is maintained in
`docs/USB_REFERENCE_FUNCTION_AUDIT.md`. At the 2026-08-26 provenance review,
all 142 unique reference function names (144 declarations) were accounted for.
The same review measured roughly 44.7% verbatim effective-line overlap inside
the low-level transport module, while the complete Mica USB-exclusive feature
including Media3/state/UI integration measured roughly 33.2%. These percentages
are engineering provenance metrics, **not** license boundaries.

## Modification notice

Files derived from the reference implementation are modified and/or adapted
for Mica unless a specific audit row says that the implementation body is
reference-identical. Reference-derived source files carry an attribution/
modification header where the file format permits comments. Non-commentable
data such as `src/main/assets/usb_dac_quirks.json` is covered by this README and
the module `NOTICE`.

Do not remove the module `LICENSE`, `NOTICE`, source attribution headers, or the
project-level `docs/OPEN_SOURCE_NOTICES.md` entry when redistributing Mica.
