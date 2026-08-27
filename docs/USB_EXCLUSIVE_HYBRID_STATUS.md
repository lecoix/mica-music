# USB Exclusive Hybrid status

Snapshot: 2026-08-24. This document distinguishes implemented software from new Hybrid evidence.
P1 and rewrite results are design input only and are never counted as Hybrid PASS.

## Implemented scope

- Default output remains Shared PCM.
- Selection is capability-driven and fail-closed: any single attached device with a USB Audio
  isochronous OUT endpoint is selectable, regardless of vendor/product. Multiple USB Audio output
  devices are ambiguous and rejected rather than choosing an arbitrary first device. Stable identity
  uses VID/PID, bcdDevice/version and endpoint topology; manufacturer/product strings are diagnostic
  metadata only because Android may expose them only after permission.
- Application-level output coordination is owned by `UsbOutputCoordinator`: Android topology,
  output-permission callbacks, target stabilization, shared-route recovery, owner facts, telemetry
  sampling and reducer effects enter one coordinator lifecycle. User mode intent uses a mode
  generation and async waits use `UsbOutputOperationId`; neither is a native ownership epoch.
- Native open/reconfigure/close and facts publication remain owned by `UsbHybridSessionOwner` and its
  single control executor. Only that owner mints `UsbRequestEpoch`; attach changes discovery revision,
  while authorized arm/retarget/retire/release fence physical session ownership.
- Native submit/reap/resubmit/flush/telemetry/close require `(epoch, sessionId)`. A stale close can
  clean only its own session and cannot close a newer winner.
- PCM accepts integer PCM16, PCM24 and PCM32. Exact-width transport is preferred; when the DAC only
  exposes a wider USB resolution/subslot, the imported reference packetizer may losslessly left-align
  samples (16 -> 24/32 or 24 -> 32). Precision-reducing conversion, float, 8-bit,
  SRC/DSP/ReplayGain/Sonic/skip-silence/software volume and speed other than 1.0 are rejected.
- DSF has explicit DoP and explicit experimental Native modes. There is no DoP/Native fallback.
  Preparation stages at most one DSF block and USB writing arms only after renderer STARTED.
- Native follows the imported reference rule: a reviewed APK quirk wins; otherwise one
  unambiguous UAC2 RAW_DATA subslot is inferred as little-endian framing (1 byte -> `u8`,
  2 bytes -> `u16le`, 4 bytes -> `u32le`). Ambiguous/unsupported RAW_DATA widths remain
  `FramingUnproven`, and vendor/chip names are never guessed. SK02 keeps its explicit `u32le` framing
  and `maxDsd=256` quirk override. Native remains `signalExact=false` until the active device/path is physically qualified.
- The reference project's remote DAC adaptation loop is restored: the APK asset remains the shipped
  baseline, while a validated local `usb_dac_quirks_override.json` is loaded first for per-device
  testing. Settings can import reference-compatible v1/v2 quirk JSON; successful field trials should
  still be reviewed and folded back into the shipped asset.
- USB failure does not rebuild Shared PCM. Queue, item references, position, repeat, play intent and
  requested mode remain available for explicit retry or manual Shared PCM selection.
- Audio settings now link to a dedicated USB page adapted from rewrite's information architecture:
  DAC/current output, explicit modes, owner-published exactness facts, transport telemetry and
  support actions. It deliberately does not copy rewrite's rounded cards, circular status controls or
  borders. The reference runtime quirk import is restored as a support workflow; selected preferences
  and imported profiles never imply ACTIVE by themselves, only owner facts do.

## Software evidence obtained in this worktree

- 2026-08-24 supersedes the 2026-08-23 SK02 cold-entry qualification. Cross-player testing (FiiO/HiBy) established that SK02 can enter and play Native DSD correctly, and Mica physical A/B isolated the real initialization dependency: park the streaming interface at alt 0, configure the UAC2 clock, then activate the target RAW alt. With `streaming.resetAlt=true`, SK02 passed cold Native DSD64 (88.2 kHz/u32le), DSD128 (176.4 kHz/u32le), and DSD256 (352.8 kHz/u32le). DSD64 passed after removing the 2.5 s PrimeThenReopen workaround; DSD256 then passed a true process-cold `OPEN_FRESH current=null` directly at 352.8 kHz after removing the DSD128 cold-entry prime. `coldEntryPrimeDsd`, `coldEntryThresholdDsd`, and `primeThenReopenMs` are therefore removed from the transport quirk model; the earlier 2026-08-23 results are retained only as historical evidence of the pre-resetAlt failure mode.

- 2026-08-24 cold-start playback-stack retirement race is fixed. A queued MediaSession `setMediaItems` could resolve after Shared->Exclusive replacement had already released the old Exo playback thread, producing `Handler ... sending message to a Handler on a dead thread` from `ExoPlayerImplInternal.setMediaSources`. `MicaCompositePlayer` now enters an explicit retired state before owner cleanup / `ExoPlayer.release()` and fail-closes late mutating commands; physical repro captured the former stale command as `PlaybackStack: retired-command-dropped ... command=setMediaItems` with zero dead-handler warnings across three cold starts. The same audit corrected external-audio restart authority: MediaStore external URIs are restorable only when the app actually holds the corresponding durable media-read permission, and legacy snapshots are filtered at process/service bootstrap instead of rehydrating unreadable URIs. Three cold-start runs showed zero `SecurityException` / `SOURCE_PERMISSION` failures.
- 2026-08-24 FiiO/HiBy reference closure corrected UAC streaming lifecycle and advertised-rate qualification before the final hardware matrix. UAC2 streams now defer target-alt activation until after clock/configuration for PCM, DoP and Native; `streaming.resetAlt` is a separate device quirk that additionally parks alt 0 before configuration. UAC1 keeps target-alt-first followed by endpoint `SET_CUR`. Candidate qualification now parses UAC1 Type-I discrete/continuous sample-rate declarations, queries UAC2 Clock `GET_RANGE`, rejects known-incompatible rates/channels, and keeps unknown capability fail-open only for compatibility. PCM/DoP candidates are restricted to non-RAW_DATA alternates, while Native uses RAW_DATA when that framing evidence exists. The new descriptor/range/activation-plan tests were force-rerun with Gradle cache bypassed and passed; the full USB transport unit suite and `:app:assembleDebug` also pass. Hardware requalification is intentionally deferred to the consolidated final matrix rather than counted here as physical GREEN.
- 2026-08-22 generalization removed the app-layer SK02 VID/PID/name gate. Production app code now
  selects one USB Audio isochronous OUT device and reports explicit not-found/ambiguous failures.
  SK02 remains only as one reviewed transport quirk/profile and as the current physical baseline.
- Stable identity no longer hashes permission-gated manufacturer/product strings. Tests require the
  same digest before/after those strings appear while still changing the digest for topology changes.
- Integer PCM16/PCM24/PCM32 and lossless USB-slot widening use the imported reference packetizer.
  Byte-level tests cover 24 -> 32 and 16 -> 24 left alignment; precision-losing mappings are rejected.
- Native is quirk-first, then reference RAW_DATA inference for one explicit 1/2/4-byte subslot;
  ambiguous or unsupported widths remain `FramingUnproven`, with no Native -> DoP fallback.
- Focused Hybrid owner, identity, PCM policy/sink, settings, diagnostics, preroll and Native scope
  tests pass.
- Reference transport packetizer, DSF converter, DoP/Native encoder, quirk and Native-candidate
  tests pass.
- Focused synthetic descriptor tests pass for UAC1 Type I PCM, UAC2 Type I PCM, UAC2 RAW_DATA
  classification and a truncated descriptor tail. A 4-byte RAW_DATA subslot infers `u32le`;
  ambiguous/unsupported subslot widths remain `FramingUnproven`.
- Explicit-feedback tests pass for a valid rate, missing/zero feedback, low and high malformed
  feedback, and recovery after 10,000 consecutive missing samples. Invalid feedback uses nominal
  cadence and the first subsequent valid sample takes effect without accumulated drift.
- A 72-hour exact-cadence projection and 32 x 100,000-packet fixed-seed cadence stress pass.
- A forced full Debug JVM run passes after the USB settings-page migration: 1,261 tests, zero
  failures/errors and nine skipped tests. The
  USB transport module separately passes 43 tests. The DSD decoder module currently has no JVM
  test sources.
- The current Debug APK build passes. Perf and unsigned Release APK builds passed before the
  UI-only settings-page change and have not been rebuilt for that change.
- The FFmpeg arm64 JNI library was rebuilt from the changed Java/JNI contract with PCM32 output.
- Current Debug artifact SHA-256:
  `DB4624ED1FAEDD7157F92A3E7B0771C4456CBB5E5A0E137DF1DAA7EDFB7ACC64` (final SK02 qualification build).
  The last pre-UI Perf and unsigned Release hashes remain `1925F2C0...4053` and
  `98430913...B89`; they are not hashes of the current source snapshot.
- The current Debug APK was installed on Redmi 22081212C and the installed base APK hash matched.
  The USB settings page was inspected at 1220x2576: the summary metrics and mode choices fit,
  the lower transport/actions content remained reachable above the mini player after scrolling,
  and system Back returned to Audio & Devices. TalkBack was not manually exercised.
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
  APK was installed and its device SHA-256 exactly matched `2B3D89B4...0A28`. The later UI-only
  Debug APK `BA338A12...3EA19` was installed and its device hash matched, but it has not repeated
  the USB physical matrix;
  the behavior evidence below remains tied to the former hash because validation and settings UI
  changes do not themselves constitute USB requalification.
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
- 2026-08-23 edge-state reruns covered several reconnect races on the current Hybrid rewrite. Unplug
  while the Android USB permission dialog was visible moved `PermissionWaiting -> Disconnected`;
  the dialog disappeared, no stale permission callback opened USB, and a later reattach plus one
  grant recovered playback. Fast and deliberately delayed Native selection after reattach also
  recovered without the earlier paused/failed state.
- A Media3 cross-item PCM stall was reproduced after recovery. `UsbHybridPcmAudioSink.flush()` had
  been clearing the sink STARTED state, causing the first new buffer to become a held prebuffer with
  no subsequent `play()` callback. `flush()` now discards old timeline/buffers while preserving the
  started/paused state; `reset()` remains the full stop. The focused regression test passed, and a
  real UI Next transition then advanced normally on USB PCM with queue 266, `error=null`, and no
  delayed automatic transition.
- Historical validation-only runs forced detach inside the former `NativeWarmup` / `NativeReopening`
  phases and proved that delayed technical callbacks were fenced after detach. Those phases are no
  longer part of the production Native path: the 2026-08-24 reset-alt A/B removed the 2.5-second
  `PrimeThenReopen` and cold-entry-prime mechanisms entirely. This bullet is retained only as stale-
  callback evidence for the superseded implementation, not as a description of the current path.
- The Native -> Shared return contract was rerun from a real `ExclusiveActive(NativeDsd)` state.
  Selecting Shared produced `SharedReconnectRequired` and closed the exclusive transport; physical
  detach produced `Disconnected`; reattach produced `SharedRouteWaiting -> SharedActive` only after
  Android reported the USB shared route. The Shared player rebuilt with queue 266 and the frozen
  position/paused intent, then a short play smoke advanced from 27.612 to 39.088 seconds with
  `error=null`.
- Two short Shared-mode churn rounds mixed DSD/PCM item selection, seek, play/pause and Next while
  SK02 remained on the Android shared USB route. After clearing logcat for the second round there
  were no `UsbExclusiveAudioTransport`, `OUTPUT_FAILED`, `No such device`, or playback-failure
  records, and the state remained `SharedActive`. Process PSS/RSS moved from about 231/284 MB before
  churn to 254/307 MB after the first warm round and 250/305 MB after the second, which does not show
  linear growth over this short sample. Android proc restrictions hid app FD counts, so this is not
  an FD-leak qualification. Final focused app USB-hybrid/bootstrap/shared-return tests and the
  imported transport module unit tests passed, and `git diff --check` reported no whitespace error.

### 2026-08-24 consolidated final SK02 requalification

The final hardware matrix below supersedes older SK02 qualification statements where they conflict.
The installed Debug APK SHA-256 matched the local artifact exactly:
`DB4624ED1FAEDD7157F92A3E7B0771C4456CBB5E5A0E137DF1DAA7EDFB7ACC64`.

- **Exact PCM 44.1 kHz / 16-bit source: GREEN.** ALAC 44.1/16 opened interface 2 alt 3 as
  44.1 kHz stereo in a 4-byte / 32-bit USB subslot. UAC2 activation followed
  `alt0 -> SET_CUR 44100 -> alt3`; USB writes remained stable and listening/indicator checks were
  normal.
- **Exact PCM 96 kHz / 24-bit source: GREEN.** The 96 kHz/24-bit FLAC fixture opened the same
  4-byte / 32-bit USB subslot at 96 kHz, with `alt0 -> SET_CUR 96000 -> alt3`, 12 feedback frames per
  microframe-equivalent output cadence, 96-byte packets and a stable 100-URB queue. Listening and
  display were normal. The current 266-song library contains no true 32-bit PCM source, so this does
  **not** qualify a 32-bit source-file path; it qualifies lossless 24 -> 32 USB-slot widening.
- **DoP DSD64: GREEN.** DSD64 opened the non-RAW 24-bit alternate at a 176.4 kHz DoP carrier with
  `alt0 -> SET_CUR 176400 -> alt2`. Pause, paused seek and resume kept the legal idle carrier,
  stopped/restarted it in the correct order and resumed real content without audible/display error.
- **Native DSD64 / DSD128 / DSD256: GREEN on SK02.** The reviewed SK02 path uses RAW interface 2
  alt 4 with `u32le`. DSD64, DSD128 and DSD256 opened at 88.2, 176.4 and 352.8 kHz USB frame rates,
  respectively. UAC2 ordering is `alt0 -> clock/config -> alt4 -> feedback arm`; DSD256 feedback was
  approximately 44.1 frames per output packet cadence and the stable packet size was about 360 bytes.
  All three rates passed DAC-indicator and listening checks. No multi-second reopen or DSD128 prime
  is present in the accepted path.
- **Transition matrix: GREEN for the exercised cases.** Native DSD64 -> PCM 44.1, PCM 44.1/24 ->
  Native DSD128, Native DSD128 -> DSD128 same-format reuse, and Native DSD128 -> DSD256 rate
  reconfiguration all completed with the expected reuse/reconfigure behavior and normal listening /
  display.
- **Active Native detach/replug: GREEN.** Pulling SK02 during Native DSD64 produced a real
  `USBDEVFS_SUBMITURB ... No such device`, closed the old exclusive session and preserved the
  266-item queue/position with `error=null`. Replug plus one USB grant opened a fresh owner
  epoch/session and automatically resumed the previously-playing intent; listening and display were
  normal.
- **Paused Native detach/replug: GREEN after one lifecycle fix.** A first paused-replug run exposed a
  real seam: when a fresh DSD session was configured while the Media3 renderer was already STOPPED,
  there was no later `onStopped()` edge, so only pre-roll was sent and SK02 fell back to PCM48.
  `UsbHybridDsdRenderer.configure()` now starts the DSD idle carrier immediately when the renderer is
  not STARTED. The patched build then passed paused detach -> replug/grant -> fresh Native session,
  retained position and PAUSED intent, sustained 0x69 filler with 100 pending URBs, kept SK02 locked
  at DSD64 with no sound, and cleanly switched filler -> real payload on resume.
- **Process cold-start restore: GREEN under the frozen A24 fail-safe policy.** Force-stop/cold-start
  restored the 266-item queue, item 0, saved position and Native DSD64 mode, opened a fresh process
  epoch/session and, while paused, immediately restored the DSD64 filler. A run stopped while PLAYING
  also restored the saved queue/position/mode but intentionally came back PAUSED: A24 explicitly
  defines cross-process restore as fail-safe PAUSE rather than persisted auto-play authority.
- **Native -> Shared return on SK02: GREEN with physical reconnect.** Selecting Shared from active
  Native produced `SharedReconnectRequired` and closed exclusive transport without
  `USBDEVFS_CONNECT`/kernel rebind. Physical detach/replug then followed
  `Disconnected -> SharedRouteWaiting -> AndroidSharedRouteReady -> SharedActive`; Android routed
  `STREAM_MUSIC` to `USB-Audio - Fosi Audio SK02`, no Mica exclusive permission was requested, and
  listening/display were normal.
- **Permission-dialog detach fencing: GREEN for the physical race exercised.** Pulling SK02 while
  the Android USB permission dialog was still pending moved `PermissionWaiting -> Disconnected`; the
  dialog was cancelled and no delayed permission result, `ExclusiveOpening`, or stale `request-open`
  followed. A later replug plus one normal grant opened a fresh epoch/session and restored paused
  Native DSD64 correctly. Android cancelled the pending request rather than delivering a late grant,
  so explicit rejection of a truly late callback remains a software-contract test rather than a
  physically observed callback case.
- **Short final Native soak: GREEN.** A clean 60-second formal-package sample window produced six of
  six `PLAYING / error=null` MediaSession samples with one stable PID and monotonically advancing
  position. USB bytes/URBs/iso-packets increased continuously with `pendingUrbs=100` and
  `isoPacketSize=96`; the error scan found no ENODEV/SUBMITURB failure, dead Handler,
  `SecurityException`, `SOURCE_PERMISSION`, FATAL/ANR or unexpected reopen. Final listening and SK02
  DSD64 indication were normal.

These are short functional runs, not stability qualification. Generic inferred RAW_DATA framing remains
unqualified across devices; SK02's explicit `u32le` Native profile is physically qualified for DSD64/128/256.
The Native mode remains experimental at the product level, and these results alone do not authorize
`signalExact=true` for unqualified devices/paths.

A Native DSD64 single-track repeat stability run completed 90 minutes 14 seconds from
2026-08-23 15:43:56 to 17:14:10 (+10:00). Nineteen five-minute samples remained PLAYING with
Media3 `error=null`; repeat boundaries reused the same Native `u32le`/88.2 kHz USB session without
`OUTPUT_FAILED`, USB write errors, `No such device`, playback exceptions, or process death. Battery
moved from 100% to 60%, temperature from 31.0 C to 39.1 C, and PSS/RSS fluctuated without linear
growth (about 201/307 MB at start and 183/291 MB at the final sample). This Native stability run is
GREEN. The earlier 15-minute attempt remains historical only and is superseded by this completed run.

## Not yet accepted

The following remain PENDING and must not be described as PASS, release-ready, verified, or
signal-exact based on old branches:

- multi-vendor physical qualification of the generalized selector/PCM/DoP/Native paths. SK02 remains
  the current physical baseline, not the only supported product identity;
- true **32-bit PCM source-file** physical qualification. The current 266-song library has no PCM
  source with `bitsPerSample=32`; 24-bit source -> 32-bit USB subslot widening is qualified, but a
  genuine 32-bit source fixture is still missing;
- 90-minute Continuous/Lifecycle/Shared-PCM/DoP stability runs on the current production path. A
  90-minute Native DSD64 stability run is already GREEN; the final post-fix build additionally has a
  60-second Native smoke/soak, but the other long-duration matrices remain open;
- multi-vendor captured UAC descriptor/rate-capability corpus beyond the focused synthetic
  UAC1/UAC2/truncated matrix. Malformed/lost/10,000-sample-gap feedback behavior is covered in
  software;
- direct proof of exact prior kernel-driver/clock restoration after process death, plus broader
  FD/memory/temperature/underrun measurements. Shared return is intentionally conservative and may
  require a physical reconnect on SK02.

## Known limitations and honest risk

- Exclusive close is intentionally conservative: release the claimed interface and close the fd without
  issuing `USBDEVFS_CONNECT` or actively rebinding the kernel driver. If Android does not recover the
  Shared route by itself, the owner reports `SharedReconnectRequired` and waits for one physical DAC
  detach/reattach. The current clock configurator does not retain a trustworthy prior target-DAC clock
  value, so "clock restored to its exact prior value" is not proven.
- The owner control wait has a timeout that aborts switching rather than proceeding. There is no
  watchdog that crosses a never-returning `ExoPlayer.release()`; such a release can still leave the
  synchronous switch incomplete, as specified.
- Manufacturer/product strings are intentionally excluded from stable identity because Android may
  expose them only after USB permission. VID/PID + bcdDevice/version + endpoint topology remain the
  reconnect-stable evidence; multiple attached USB Audio output devices fail closed as ambiguous.
- DoP appears in settings as `USB DoP`; Native remains explicitly marked experimental.
- Older pre-final detach runs could briefly surface a generic Media3 runtime error. The consolidated
  2026-08-24 Native DSD64 active-detach/replug run retained queue/position with `error=null` and
  recovered normally after one grant, so the older generic-error observation is historical rather
  than the accepted SK02 final behavior.

## Physical acceptance recording template

For each run record APK SHA-256, media SHA-256, stable/runtime identity, descriptor digest, Android
build, battery/power condition, requested and active mode, epoch/session, negotiated format,
milestone logs, URB telemetry, DAC indication/listening observation, cleanup/driver state and final
PASS/FAIL/HARNESS classification. Save milestones incrementally rather than only collecting logcat
at the end.
