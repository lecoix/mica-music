# USB Exclusive Hybrid status

Snapshot: 2026-08-21. This document distinguishes implemented software from new Hybrid evidence.
P1 and rewrite results are design input only and are never counted as Hybrid PASS.

## Implemented scope

- Default output remains Shared PCM.
- Selection is fail-closed to one Fosi Audio SK02 candidate with VID/PID `262a:0001`,
  `bcdDevice=0x0004`, product strings `Speed Dragon / Fosi Audio SK02`, and a descriptor
  digest that includes product strings and endpoint topology. A known Douk K5 identity collision
  is rejected. Multiple candidates are ambiguous and rejected.
- Permission, open, reconfigure, close, driver recovery, telemetry and facts publication are owned
  by one control executor. Mode change, retry, target detach, release and service recreation mint a
  request epoch; attach changes only discovery revision.
- Native submit/reap/resubmit/flush/telemetry/close require `(epoch, sessionId)`. A stale close can
  clean only its own session and cannot close a newer winner.
- PCM accepts only integer PCM16 or PCM32 with exact USB subslot width and bit resolution. Float,
  packed PCM24, 8-bit, SRC/DSP/ReplayGain/Sonic/skip-silence/software volume and speed other than
  1.0 are rejected. There is no float-to-PCM24 path.
- DSF has explicit DoP and explicit experimental Native modes. There is no DoP/Native fallback.
  Preparation stages at most one DSF block and USB writing arms only after renderer STARTED.
- RAW_DATA produces `FramingUnproven`; it never infers endian or slot framing. Experimental Native
  uses the rewrite-derived SK02 `u32le`, up-to-DSD128 built-in profile only after first-use warning.
  It always reports `signalExact=false` until a Hybrid qualification run promotes the profile.
- Runtime JSON quirks and persisted "remember DoP" overrides are disabled. Only reviewed APK assets
  are loaded.
- USB failure does not rebuild Shared PCM. Queue, item references, position, repeat, play intent and
  requested mode remain available for explicit retry or manual Shared PCM selection.

## Software evidence obtained in this worktree

- Focused Hybrid owner, identity, PCM policy/sink, settings, diagnostics, preroll and Native scope
  tests pass.
- Reference transport packetizer, DSF converter, DoP/Native encoder, quirk and Native-candidate
  tests pass.
- A 72-hour exact-cadence projection and 32 x 100,000-packet fixed-seed cadence stress pass.
- A forced full Debug JVM run passes for the application and reference transport; the DSD decoder
  module currently has no JVM test sources.
- Debug, Perf and unsigned Release APK builds pass.
- The FFmpeg arm64 JNI library was rebuilt from the changed Java/JNI contract with PCM32 output.
- Final local artifact SHA-256 values:
  - Debug: `83FA7984D47DCB6AE49BBFA150CF52B3E2FB7746AF07817D282BD1F0D0C7BFEC`
  - Perf: `7C7E7F3C6E8B818914D6F4B4423A0C5C66A5C67E8B47A3BEF48312D7EAF66BC6`
  - unsigned Release: `19A579CBCA543BD0EF6E16067CE3C44B6FE94EAD813F1F36F2350FAABFBE4489`

## Not yet accepted

The following remain PENDING and must not be described as PASS, release-ready, verified, or
signal-exact based on old branches:

- all SK02 Hybrid physical PCM, DoP and Native runs;
- APK-installed hash verification on the test phone;
- PCM 44.1/48/96 kHz and PCM16/PCM32 playback, pause/resume, seek, track transitions and detach;
- DoP and Native DSF transitions, idle/content single-writer behavior and DAC lock indicators;
- the standard DSD64 fixture (no Hybrid fixture has been registered yet);
- 90-minute Continuous, Lifecycle, Shared PCM baseline and DoP stability runs;
- Native requalification, 90-minute Native stability and any promotion to `signalExact=true`;
- UAC descriptor corpus and malformed/lost/long-gap feedback matrix beyond the imported focused
  tests;
- process-kill driver rebind, FD/memory/temperature/underrun measurements and listening matrix;
- 10,000-item on-device handoff measurement. The implementation copies `MediaItem` references and
  does not parse lyrics/artwork, but the <=5 MB target has not yet been measured.

## Known limitations and honest risk

- Kernel-driver recovery is best effort (`alt 0`, release interface, `USBDEVFS_CONNECT`, close fd).
  The current reference clock configurator does not retain a trustworthy prior SK02 clock value,
  so "clock restored to its exact prior value" is not proven.
- The owner control wait has a timeout that aborts switching rather than proceeding. There is no
  watchdog that crosses a never-returning `ExoPlayer.release()`; such a release can still leave the
  synchronous switch incomplete, as specified.
- Product strings are needed for the strict SK02 scope. If Android exposes them only after USB
  permission, the first result fails closed as target-changed and the user must use
  "授权并重试"; the second proof may then proceed.
- DoP appears in settings as "待实机验收". Native always appears as experimental.

## Physical acceptance recording template

For each run record APK SHA-256, media SHA-256, stable/runtime identity, descriptor digest, Android
build, battery/power condition, requested and active mode, epoch/session, negotiated format,
milestone logs, URB telemetry, DAC indication/listening observation, cleanup/driver state and final
PASS/FAIL/HARNESS classification. Save milestones incrementally rather than only collecting logcat
at the end.
