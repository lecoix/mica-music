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
- Focused synthetic descriptor tests pass for UAC1 Type I PCM, UAC2 Type I PCM, UAC2 RAW_DATA
  classification and a truncated descriptor tail. RAW_DATA remains `FramingUnproven`.
- Explicit-feedback tests pass for a valid rate, missing/zero feedback, low and high malformed
  feedback, and recovery after 10,000 consecutive missing samples. Invalid feedback uses nominal
  cadence and the first subsequent valid sample takes effect without accumulated drift.
- A 72-hour exact-cadence projection and 32 x 100,000-packet fixed-seed cadence stress pass.
- A forced full Debug JVM run passes: 1,258 tests, zero failures/errors and nine skipped tests. The
  USB transport module separately passes 43 tests. The DSD decoder module currently has no JVM
  test sources.
- Debug, Perf and unsigned Release APK builds pass.
- The FFmpeg arm64 JNI library was rebuilt from the changed Java/JNI contract with PCM32 output.
- Final local artifact SHA-256 values:
  - Debug: `2B3D89B4A715D675C62D8D9A13FC75AB656F7D7B634E839D39BCA91492AE0A28`
  - Perf: `1925F2C06F691013ECAE2C9AB2E88BECEE350157EFE55B01BE8B0D5D87404053`
  - unsigned Release: `984309131B847EE63CA31070315B52F1277B74E6A69B72D8F295EAF883150B89`
- Perf now has a machine-readable `handoff` capacity mode that loads 10,000 real Media3
  `MediaItem` objects into ExoPlayer and invokes the production `playbackQueueSnapshot()` path. It
  records capture time, ART's monotonic allocated-byte delta, observational Java-heap delta, item
  count and reference identity. The <=5 MB verdict uses the allocation counter so a concurrent GC
  cannot create a false PASS. On-device, one cold run completed in 18.658 ms with 51,976 allocated
  bytes. Five subsequent cold Activity/process runs completed in 3.530-4.089 ms with
  50,704-52,352 allocated bytes. All six retained 10,000/10,000 original `MediaItem` references,
  reported `handoffWithin5Mb=true`, and kept the lazy lyrics policy. The installed Perf APK was
  read back with SHA-256 `1925F2C0...4053`; no app crash, ANR, ExoPlayer or USB error appeared in
  the sampled log. The last Perf snapshot was about 100 MB total PSS / 219 MB total RSS; battery
  was 76% at 33.7 C. Those process totals include ExoPlayer and the capacity harness and are not
  attributed to the 50-52 KiB handoff capture itself.

## Hybrid physical evidence obtained

Test device: Redmi `22081212C`, Android 12 / API 31, build
`Redmi/diting/diting:12/SKQ1.220303.001/V13.0.7.0.SLFCNXM:user/release-keys`. The connected target
was `Speed Dragon / Fosi Audio SK02`, USB address `/dev/bus/usb/002/002`, with no serial exposed.
The final Native stability attempt started at 33% battery and 38.3 C battery temperature.

- The USB-physically-tested Debug APK was installed and read back from `/data/app`; its device
  SHA-256 exactly matched `0481C2E5...72EC`. After the validation-only additions, the current Debug
  APK was installed and its device SHA-256 exactly matched `2B3D89B4...0A28`. The current APK has
  not repeated the USB physical matrix; the behavior evidence below remains tied to the former
  hash because test-visible descriptor parsing and the Perf-only capacity mode do not themselves
  constitute USB requalification.
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
- On the physically tested APK, experimental Native opened interface 2, alt 4 RAW_DATA with the
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
  epoch. On the physically tested APK, the formerly failing DSD -> PCM transition closed Native,
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
- direct proof that the DoP idle thread's blocking stop/join completed before resumed content.
  Byte-level marker alternation, partial-frame carry, `0x69` idle continuity, drain padding and
  reset phase pass in software, and short physical behavior passed, but the physical log did not
  emit a stop/join milestone;
- DAC lock/rate indicator and listening observations for DoP and Native;
- 90-minute Continuous, Lifecycle, Shared PCM baseline and DoP stability runs;
- Native requalification, 90-minute Native stability and any promotion to `signalExact=true`;
- a multi-vendor, captured UAC descriptor corpus beyond the focused synthetic UAC1/UAC2/truncated
  matrix. Malformed/lost/10,000-sample-gap feedback behavior is now covered in software;
- direct process-kill kernel-driver/prior-clock restoration proof, plus FD/memory/temperature/
  underrun measurements and the listening matrix.

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
