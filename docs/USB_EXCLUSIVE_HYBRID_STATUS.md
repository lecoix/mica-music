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
- A forced full Debug JVM run passes: 1,256 tests, zero failures/errors and nine skipped tests. The
  DSD decoder module currently has no JVM test sources.
- Debug, Perf and unsigned Release APK builds pass.
- The FFmpeg arm64 JNI library was rebuilt from the changed Java/JNI contract with PCM32 output.
- Final local artifact SHA-256 values:
  - Debug: `1FEEEA5FC1B2AE3EC947D8B3C07EA485D08E3F749FA81F6427ADD61E700655E3`
  - Perf: `5D3F8315EE61A708D0DE7181E75E7FB2C1A74CDB77A07988FF98663330DEB583`
  - unsigned Release: `FE15EA73374A7101C8F0F298C93265DB67D7F9376AC55F6996D62815348B2F35`

## Hybrid physical evidence obtained

Test device: Redmi `22081212C`, Android 12 / API 31, build
`Redmi/diting/diting:12/SKQ1.220303.001/V13.0.7.0.SLFCNXM:user/release-keys`. The connected target
was `Speed Dragon / Fosi Audio SK02`, USB address `/dev/bus/usb/002/002`, with no serial exposed.
The final run started at 77% battery and 37.8 C battery temperature.

- The final Debug APK was installed and read back from `/data/app`; its device SHA-256 exactly
  matched `1FEEEA...655E3` above.
- PCM short runs passed for 44.1 kHz/PCM16 and 48 kHz/PCM32, including pause/resume, seek, track
  change and manual Shared PCM <-> USB switching. Shared PCM regained the device after USB close.
- USB mode changes exposed and then validated a real retirement race. The fixed protocol releases
  the old Exo/USB stack before minting the replacement epoch, treats stale in-flight writes as
  retired rather than playback failures, and aborts rather than opening a new USB session if the
  bounded 15-second Media3 release fails. Native <-> DoP PCM32 short switches then completed with
  old transport close before the new request and no stale-write, transport-not-open,
  `PlaybackException` or `ExoTimeoutException`.
- DoP DSD64 opened the 176.4 kHz carrier and sustained eight pending URBs. Pause entered the DSD
  idle writer and resume continued playback without a media-session error. DSD -> PCM transition
  also completed without silent Shared PCM fallback.
- The registered DSF fixture is `06. A Christmas Wedding (Fiona Joy Hawkins).dsf.dsd`, SHA-256
  `C393038AD94EB0B50087786327022B19EC0D62C3D0F13BB10337CDA39A06AF99`, DSD64 stereo at
  2,822,400 samples/s.
- On the final installed APK, experimental Native opened interface 2, alt 4 RAW_DATA with the
  built-in `u32le` profile, 88.2 kHz frame rate and DSD64 input. A short run sustained eight pending
  URBs with no media-session error. Native pause started the idle writer, resume advanced position,
  and Native DSD -> PCM16/44.1 kHz -> Native DSD transitions each closed/reopened the appropriate
  transport without playback exceptions.
- Force-stopping the process during the USB test and starting a new process preserved a paused
  queue/position. The new process then reclaimed SK02 and sustained eight pending URBs without a
  media-session error. This proves that no leftover state prevented a subsequent exclusive open;
  it does not prove exact kernel-driver or prior-clock restoration because MIUI denied shell reads
  of `/proc/asound/cards` and `/proc/asound/card1/stream0`.

These are short functional runs, not stability qualification. RAW_DATA framing remains unproven,
the Native profile remains experimental, and these results do not authorize `signalExact=true`.

## Not yet accepted

The following remain PENDING and must not be described as PASS, release-ready, verified, or
signal-exact based on old branches:

- PCM 96 kHz and a new PCM32 source-specific fixture on the final installed APK;
- playing/paused target detach, reattach, reauthorization and explicit retry;
- DSD seek on hardware. Two attempted injected gestures did not move the UI progress bar and are
  classified as NOT EXECUTED, not PASS;
- byte-level DoP marker continuity and direct proof that idle stop/join completed before resumed
  content. Short behavior passed, but the log did not emit a stop/join milestone;
- DAC lock/rate indicator and listening observations for DoP and Native;
- 90-minute Continuous, Lifecycle, Shared PCM baseline and DoP stability runs;
- Native requalification, 90-minute Native stability and any promotion to `signalExact=true`;
- UAC descriptor corpus and malformed/lost/long-gap feedback matrix beyond the imported focused
  tests;
- direct process-kill kernel-driver/prior-clock restoration proof, plus FD/memory/temperature/
  underrun measurements and the listening matrix;
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
