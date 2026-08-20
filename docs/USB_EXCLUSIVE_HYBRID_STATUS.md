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
- A forced full Debug JVM run passes: 1,258 tests, zero failures/errors and nine skipped tests. The
  DSD decoder module currently has no JVM test sources.
- Debug, Perf and unsigned Release APK builds pass.
- The FFmpeg arm64 JNI library was rebuilt from the changed Java/JNI contract with PCM32 output.
- Final local artifact SHA-256 values:
  - Debug: `0481C2E5B41DF1B955CE454E0FCE274C897CABE468D74E7F92781A82567972EC`
  - Perf: `6A4182DFCC1E94D4267C50F1C4B8B325EA6B3436BD9A0069404DD84CB8967F37`
  - unsigned Release: `9D6F8041E2C0679110A1C00DA165A6117F2E9AA87962091405925D06F4EF70EE`

## Hybrid physical evidence obtained

Test device: Redmi `22081212C`, Android 12 / API 31, build
`Redmi/diting/diting:12/SKQ1.220303.001/V13.0.7.0.SLFCNXM:user/release-keys`. The connected target
was `Speed Dragon / Fosi Audio SK02`, USB address `/dev/bus/usb/002/002`, with no serial exposed.
The final Native stability attempt started at 33% battery and 38.3 C battery temperature.

- The final Debug APK was installed and read back from `/data/app`; its device SHA-256 exactly
  matched `0481C2...72EC` above.
- PCM short runs passed for 44.1 kHz/PCM16 and 48 kHz/PCM32, including pause/resume, seek, track
  change and manual Shared PCM <-> USB switching. Shared PCM regained the device after USB close.
- A 96 kHz/24-bit FLAC source (`01.HALO.flac`) was decoded to integer PCM32 and opened the exact
  96 kHz/32-bit SK02 alternate setting. Native write telemetry increased by 768,000 bytes/second,
  exactly 96,000 frames x two channels x four bytes, with eight pending URBs and no USB error.
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
- Media3 controller seeks on the Native DSF moved playback approximately +30 seconds and -30
  seconds; position then continued advancing with no media-session error.
- Repeating Native DSF -> ALAC PCM exposed a deterministic natural-transition failure: the PCM
  renderer could already be STARTED before its sink was configured, but `configure()` cleared the
  sink's playing state. The first PCM buffer then remained paused and Media3's stuck-player guard
  fired after ten seconds. The sink now preserves an already-STARTED state across configure and,
  when a same-epoch DSD session has retired its old PCM session id, reopens PCM once before retrying
  the first write. Deterministic tests cover both interleavings and forbid reopening after a newer
  epoch. On the final installed APK, the formerly failing DSD -> PCM transition closed Native,
  opened PCM32/44.1 kHz, and advanced continuously from 1.395 to 44.137 seconds without a stuck
  error.
- Force-stopping the process during the USB test and starting a new process preserved a paused
  queue/position. The new process then reclaimed SK02 and sustained eight pending URBs without a
  media-session error. This proves that no leftover state prevented a subsequent exclusive open;
  it does not prove exact kernel-driver or prior-clock restoration because MIUI denied shell reads
  of `/proc/asound/cards` and `/proc/asound/card1/stream0`.
- Paused detach produced epoch 2 with no active session and `TARGET_DETACHED`; claim, exclusive and
  exactness facts became false, the 266-item queue and 4.537-second position remained paused, and
  no Shared PCM fallback occurred. Reattach only refreshed discovery: it did not request
  permission or reopen. Explicit retry minted epoch 3, displayed the Android permission dialog,
  reread descriptors, opened session 3 at PCM32/48 kHz and preserved the paused intent.
- Playing detach produced epoch 4 with no active session and `TARGET_DETACHED`; USB closed and no
  Shared PCM fallback occurred. Reattach again did not reopen. Explicit retry rebuilt with
  `items=266 index=6 positionMs=219953 resume=true`, opened PCM32/44.1 kHz and resumed USB writes.
  That saved position was about 34 ms from track end, so the player immediately crossed a track
  boundary. The following item made no progress for 10 seconds and tripped Media3's
  `StuckPlayerDetector`; the existing playback recovery moved to item 8, after which playback and
  eight pending URBs remained stable. This is not a USB write/claim failure, but it prevents this
  run from proving same-item playing-position recovery away from an end boundary.
- A third playing detach occurred after playback had advanced to item 10 and while the renderer was
  changing PCM rate. The current renderer surfaced its real failure, then owner epoch 6 published
  `TARGET_DETACHED` and closed USB; no fallback occurred. Reattach remained passive. Explicit retry
  minted epoch 7, rebuilt `items=266 index=10 positionMs=0 resume=true`, opened session 9 at
  PCM32/44.1 kHz and sustained eight pending URBs without a subsequent error. This closes the
  explicit detach/reattach/permission/reopen loop, but again does not prove a non-boundary
  same-item position because the user-visible detach happened after intervening automatic tracks.

These are short functional runs, not stability qualification. RAW_DATA framing remains unproven,
the Native profile remains experimental, and these results do not authorize `signalExact=true`.

A Native DSD64 single-track repeat stability attempt reached 15 minutes with four clean natural
repeat boundaries, zero new playback/USB errors, about 199 MB total PSS / 181 MB total RSS, and
battery temperature falling from 38.3 C to 34.0 C. Battery fell from 33% to 27% while the DAC
occupied USB-C, projecting power loss before 90 minutes, so the run was deliberately stopped and
is classified INCOMPLETE, not PASS. A full rerun requires simultaneous phone power (wireless
charging or a powered OTG hub) and must start again at minute zero.

## Not yet accepted

The following remain PENDING and must not be described as PASS, release-ready, verified, or
signal-exact based on old branches:

- playing detach/retry repeated at a non-boundary position, to isolate same-item position recovery
  from the observed near-end `StuckPlayerDetector` path;
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
- A playing detach leaves the Media3 session briefly in `STATE_ERROR` with the generic external
  message `Unexpected runtime error`; owner facts correctly retain `TARGET_DETACHED`. External
  controller error text is therefore less specific than the settings/diagnostics facts.

## Physical acceptance recording template

For each run record APK SHA-256, media SHA-256, stable/runtime identity, descriptor digest, Android
build, battery/power condition, requested and active mode, epoch/session, negotiated format,
milestone logs, URB telemetry, DAC indication/listening observation, cleanup/driver state and final
PASS/FAIL/HARNESS classification. Save milestones incrementally rather than only collecting logcat
at the end.
