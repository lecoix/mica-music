# USB Reference Function Audit Handoff — 2026-08-23

## 0. Purpose

This handoff is for a new ChatGPT session to continue the current Mica USB-exclusive work **without restarting the audit**.

User direction is explicit: **go through the reference project function-by-function and do not declare the port complete until every relevant reference function is accounted for**. If a reference behavior exists, prefer copying/adapting it rather than inventing a parallel implementation.

## 1. Repository / reference roots

Main worktree:

`D:\AI\3\mica-music-a8fa312e3b45477f922d0dde3ca38e99d203cebc`

Current HEAD at handoff:

`3e9684fec72f157993e32a627a7b6cc2f1da48ce`

Reference fork snapshot used for function-level comparison:

`D:\AI\3\mica-music-a8fa312e3b45477f922d0dde3ca38e99d203cebc\.codex-tmp\sylvakru-usb-fork-ref-20260812`

Primary reference engine:

`.codex-tmp\sylvakru-usb-fork-ref-20260812\android\app\src\main\kotlin\com\afalphy\sylvakru\UsbExclusiveAudioEngine.kt`

Current extracted/adapted transport:

`third_party\sylvakru-usb-transport\src\main\kotlin\com\afalphy\sylvakru\`

Function audit ledger:

`docs/USB_REFERENCE_FUNCTION_AUDIT.md`

**Important:** the main worktree contains substantial unrelated/user work. Do **not** reset, clean, checkout-overwrite, drop stashes, or mass-revert. Do not commit unless the user explicitly asks.

## 2. Current audit policy

Every reference USB function must end in one of these buckets:

- `EXACT` — copied/reference-identical logic.
- `EQUIVALENT` — same semantics with Mica/Media3 integration.
- `MICA_STRICTER` — deliberate fail-closed strengthening with no lost reference capability.
- `MEDIA3_REPLACED` — reference decoder/player lifecycle replaced by Media3; USB behavior still preserved.
- `MICA_EXTENSION` — physically justified Mica-only behavior.
- `NOT_APPLICABLE` / explicitly out of product scope — only when proven, not assumed.

Do not use function-name absence alone to call something missing; many reference engine functions were split across Mica transport/controllers. Read function bodies and call sites.

## 3. Major omissions already discovered and fixed during this audit

The old “directly copied reference” work was not complete. The function-level audit found real missing behavior, including:

1. `UsbStreamSignature` / `REUSE` / `SILENT_RECONFIGURE` handling.
2. Dynamic pending-URB queue depth application. Native default is 8, but reference computes pending URBs from target buffer; default target is 200 ms. SK02 HS path is roughly 100 pending URBs.
3. PCM transition fades:
   - pause: 16 ms fade-out + 24 ms silence;
   - resume: 16 ms fade-in;
   - seek/discontinuity: fade-out -> cadence reset -> fade-in.
4. `pcmSampleForUsbTransition` behavior including gain clamp.
5. Feedback diagnostics callback.
6. Reference clock-setting tolerance (do not fail just because GET_CUR is null/0 or some DAC control behavior is odd; only fail on a valid nonzero mismatched readback where reference does).
7. 4-second deferred USB session close / hot-reuse window.
8. `setTargetBufferMs(50..1000)` capability.
9. Session-scoped diagnostics and derived transport telemetry.
10. Reference `UsbVolumeProtocol.kt` was entirely missing; now copied in along with its reference tests.
11. Standard UAC Feature Unit volume runtime was missing; now implemented in `UsbStandardHardwareVolumeController.kt`.
12. iBasso Macaron HID volume runtime/config was missing; reference Macaron quirk entry was also absent. Runtime is now split into `UsbIbassoHardwareVolumeController.kt` and wired into transport.
13. USB volume settings/UI (`auto/dac/digital/raw`, DSD compensation, smooth handoff) were missing; UI/preferences/Media3 PCM+DSD volume seams have now been added.
14. DSD renderer volume message handling was added via `androidx.media3.exoplayer.Renderer.MSG_SET_VOLUME`.
15. Digital volume now makes `signalExact=false` rather than falsely reporting bit-perfect.

## 4. DSD256 / SK02 physical finding and Mica-only quirk

SK02 VID/PID: `0x262a:0x0001`.

Physical matrix on Redmi test phone showed:

- PCM/non-Native -> Native DSD256: could produce sustained noise.
- Native DSD128 -> Native DSD256: normal.
- A DSD128 Native silence prime before entering DSD256 made PCM -> DSD256 normal.

Therefore Mica has a **transport-only, SK02-specific cold-entry prime extension**:

- `nativeDsdColdEntryPrimeDsd = 128`
- `nativeDsdColdEntryThresholdDsd = 256`

When entering DSD256 from non-Native, transport first opens a DSD128 Native silence session, then uses the normal reference `SILENT_RECONFIGURE` path to DSD256.

Do **not** reintroduce the deleted app/service `PrimeThenReopen / NativeWarmup / NativeReopening` lifecycle. That duplicate authority caused earlier failures. The transport is the only stream reconfiguration authority.

## 5. Last known GREEN checkpoints

Before the **latest** volume-coalescing / preserved-volume edits below:

- Transport unit tests were GREEN repeatedly, including copied reference `UsbVolumeProtocolTest` and `UsbHardwareVolumeTest`.
- App USB/preferences targeted tests were GREEN after the volume UI and Media3 volume wiring:
  `BUILD SUCCESSFUL in 43s`.
- A previous targeted app run after PCM/DSD Media3 volume wiring was also GREEN:
  `BUILD SUCCESSFUL in 1m 40s`.

These GREEN checkpoints do **not** include the final two changes described in section 6.

## 6. Historical stopping point — resolved by section 13

The session ended immediately after implementing the last two reference-runtime gaps. **Do not assume the worktree is currently GREEN until you compile/test it.**

### 6.1 Async latest-wins volume request coalescing — just added

Reference `setExclusiveVolume()` is asynchronous:

- first request starts one volume command worker;
- while a command is running, new requests replace the pending target (`coalescedUsbVolumeRequest`, latest-wins);
- iBasso uses `usbVolumePendingDelayMs()` (150 ms settle / 300 ms quiet behavior from `UsbVolumeProtocol.kt`);
- session generation invalidates stale volume requests.

Mica transport was previously synchronous. It has just been changed toward the reference model in:

`third_party/sylvakru-usb-transport/src/main/kotlin/com/afalphy/sylvakru/UsbExclusiveAudioTransport.kt`

New/current pieces include approximately:

- `volumeCommandLock`
- `volumeCommandExecutor` (`MicaUsbVolume` daemon thread)
- `volumeCommandRunning`
- `runningVolumeRequest`
- `pendingVolumeRequest`
- `pendingVolumeRequestUpdatedAtMs`
- async `setVolume(...)`
- `drainVolumeRequests(...)`
- `applyVolumeRequest(...)`
- `invalidatePendingVolumeRequestsLocked()`

**First task for new session:** compile and inspect this coordinator against reference lines around `setExclusiveVolume`, `drainVolumeRequests`, `applyVolumeRequest`, and `invalidatePendingVolumeRequests`. Ensure session reopen/close invalidation points are complete and no deadlock is introduced by transport synchronization.

### 6.2 Preserved PCM iBasso verification after pre-roll — just added / still needs validation

Reference special case:

If an iBasso HID control connection is newly recreated, a previously verified PCM hardware-volume target exists, but initial hardware readback is unavailable, reference **does not write a new target immediately**. It freezes the trusted previous PCM target, then after the new stream pre-roll performs an asynchronous readback. Only if the readback matches does it unfreeze and resume normal volume control.

The controller has just been changed in:

`third_party/sylvakru-usb-transport/src/main/kotlin/com/afalphy/sylvakru/UsbIbassoHardwareVolumeController.kt`

Current relevant additions:

- the `newConnection && previousAppliedTarget != null && readBaseRaw == null && !isDsd` branch returns a frozen result instead of writing a target;
- `verifyPreservedTarget(deviceId, target)`;
- `acceptPreservedTarget(device, target, readbackRaw)`.

At the moment of handoff the file section around lines ~255-270 is syntactically formatted correctly:

- `verifyPreservedTarget(...)`
- `acceptPreservedTarget(...)`
- then `private fun accept(...)`

But this latest change has **not** been compiled/tested yet.

Transport still needs to be checked carefully for reference-equivalent ordering:

`establish session -> apply/freeze volume decision -> PCM pre-roll -> schedule preserved verification`

Reference does not write pre-roll first and only then decide volume state. The audit was in the middle of changing/checking this order when the session limit was reached.

Reference helper already exists exactly in `UsbStreamTransition.kt`:

`preservedVolumeVerificationAction(...)`

Reference engine function to compare:

`schedulePreservedPcmVerificationAfterPreRoll()` around the reference engine lines ~2234+, and its call from PCM pre-roll around ~3364.

## 7. Volume system current architecture

### Pure reference protocol/primitive files now present

- `UsbVolumeProtocol.kt`
- `UsbHardwareVolumePrimitives.kt`
- `UsbStreamTransition.kt`

Reference tests copied/present:

- `UsbVolumeProtocolTest.kt`
- `UsbHardwareVolumeTest.kt`
- `UsbStreamTransitionTest.kt`

### Standard UAC volume

`UsbStandardHardwareVolumeController.kt`

Intended reference semantics already implemented:

- Feature Unit descriptor parsing;
- output terminal source resolution;
- UAC1/UAC2 range reads;
- current reads;
- SET_CUR per channel;
- readback verification;
- rollback on failure;
- dedicated connection / interface claim behavior according to reference quirks.

### iBasso HID volume

`UsbIbassoHardwareVolumeController.kt`

Current controller includes:

- dedicated HID connection/interface;
- IN reader thread;
- command response futures;
- ACK/event routing;
- unsolicited volume events;
- reader health/restart/write-only downgrade;
- trusted target retention;
- readback verification;
- PCM freeze / DSD fail-safe behavior;
- preserved-target methods just added.

There is no Macaron hardware available in this session, so runtime physical qualification is still outstanding even after software equivalence is proven.

### Product behavior / default

Mica deliberately defaults USB exclusive volume mode to **Raw / full-scale** to preserve already-qualified SK02 behavior. This differs from blindly applying the reference app's default `auto` to all DACs.

Settings now expose:

- Auto
- DAC hardware volume
- Digital
- Raw/full-scale
- DSD gain compensation (-12..+6 dB)
- Smooth hardware-volume handoff

This is a deliberate generalized-device adaptation: reference hardware-volume safety logic is used when the user/configured DAC actually engages hardware volume; unknown/SK02 Raw mode must not suddenly require verified hardware volume for DSD.

## 8. Function audit ledger status

`docs/USB_REFERENCE_FUNCTION_AUDIT.md` has already been expanded substantially and now includes the remaining `UsbExclusiveAudioEngine.kt` classifications.

However, because the last two runtime changes were made immediately before handoff, **reconfirm the rows for**:

- `setVolume`
- `drainVolumeRequests`
- `applyVolumeRequest`
- `invalidatePendingVolumeRequests`
- `schedulePreservedPcmVerificationAfterPreRoll`
- `writePreRollIfNeeded`
- `writeIbassoHidVolume`

Only keep them as `EQUIVALENT` after the latest implementation is compiled/tests pass and the call ordering is verified against reference.

Then rescan the full reference function list and ensure the audit table contains no unexplained `MISSING`, `PARTIAL`, `待审`, or unclassified function.

## 9. Immediate continuation plan

Do these in order:

1. **Do not edit first.** Read the current `git diff` of:
   - `UsbExclusiveAudioTransport.kt`
   - `UsbIbassoHardwareVolumeController.kt`
   - `UsbVolumeProtocol.kt`
   - `UsbStreamTransition.kt`
   - `UsbHybridPreferences.kt`
   - `AndroidUsbHybridControlEffects.kt`
   - `UsbHybridPcmAudioSink.kt`
   - `UsbHybridDsdRenderer.kt`
   - `SettingsUsbHybridPanel.kt`
   - `docs/USB_REFERENCE_FUNCTION_AUDIT.md`
2. Compile `:sylvakru-usb-transport:testDebugUnitTest` first. Fix only latest coalescing/preserved-verification issues if red.
3. Run app targeted USB/preferences tests after transport is GREEN.
4. Verify the precise PCM fresh/reconfigure order is reference-equivalent: volume freeze/decision before new pre-roll, preserved verification after pre-roll.
5. Verify every session teardown/reopen invalidates pending volume request generation exactly once and no stale command can write a successor session.
6. Add/port tests for coordinator runtime if reference pure helpers do not cover the integration:
   - rapid volume updates -> latest pending target wins;
   - session generation change cancels stale queued request;
   - iBasso pending quiet/settle behavior;
   - preserved PCM target with missing first readback does not write a new target before post-pre-roll verification;
   - matching post-pre-roll readback accepts/unfreezes;
   - mismatch keeps frozen;
   - DSD never uses the PCM frozen compensation path.
7. Refresh `docs/USB_REFERENCE_FUNCTION_AUDIT.md` only after tests prove equivalence.
8. Run final gates:
   - full transport tests;
   - app USB targeted tests;
   - QA/debug assemble (`-Pmica.qaSideBySide=true` as previously used);
   - `git diff --check`.
9. Only after software is GREEN return to physical SK02 regression. iBasso/Macaron physical volume verification requires matching hardware and must not be claimed GREEN without it.

## 10. Known physical USB baseline that must not regress

Previously qualified SK02 behaviors include Shared/Exact/DoP/Native mode switching, unplug/replug recovery, pause/resume/seek, DSD64/128, and the newly conditional DSD256 cold-entry prime path.

Most important DSD256 rule from this session:

**Do not remove the SK02 DSD128 cold-entry prime just because steady-state DSD256 USB cadence looks correct.** PCM -> direct DSD256 produced noise physically; DSD128 -> DSD256 and the transport cold-prime path were normal.

## 11. Safety / workflow notes

- Use AgentDock against the device-local repository.
- Do not reset/clean unrelated dirty work.
- Do not mass-normalize line endings; several PowerShell edits previously created noisy diffs, so keep patches surgical.
- Do not reintroduce an app/service-level Native reopen state machine.
- Do not lower the reference 16 ISO packets/URB behavior or remove dynamic target-buffer based pending-URB sizing.
- Do not call the audit finished merely because function names are all present; compare bodies/call timing and preserve tests.
- No commit was requested.

## 12. Handoff state in one sentence

**Historical state only: the audit was nearly closed at this point. Section 13 records the completed follow-up; software/reference-function audit is now closed at 142/142 unique function names (144 declarations) accounted, with hardware qualification remaining separate.**

## 13. Completion update — 2026-08-23 late session

This section **supersedes the stopping-point warnings in sections 5, 6, 8, 9 and 12**. The previously unverified async-volume and preserved-volume work was continued, audited against the reference implementation, fixed where needed, and revalidated.

### 13.1 Additional reference gaps found and fixed after the original handoff

1. **Frozen iBasso state carryover was incomplete.**
   - Transport copied the controller's `active/readback/writeOnly` result but did not retain `frozen` / `syncPending` correctly through the runtime state transition.
   - This could let the generic fallback path overwrite a just-created frozen trusted PCM state.
   - Mica now preserves `hardwareVolumeFrozen` and `hardwareVolumeSyncPending`, retains the trusted target, and computes only attenuation-safe PCM compensation while frozen.

2. **A subsequent request while already frozen did not first prove the trusted hardware register had recovered.**
   - Reference behavior requires reading the current iBasso base register and matching it to the trusted target before normal writes can resume.
   - `UsbIbassoHardwareVolumeController.apply(...)` now performs that recovery gate.
   - Matching readback clears the frozen state; missing/mismatched readback keeps PCM frozen. DSD fails closed instead of using PCM compensation.

3. **Write-only new iBasso connections were incorrectly treated like failed initial readback.**
   - Initial readback is now requested only when the reader is actually readable.
   - A deliberate write-only state is no longer mislabeled as a transient readback failure.

4. **DSD async volume failure did not stop later real DSD payload writes.**
   - `setVolume()` is intentionally asynchronous/latest-wins, so it can return before a hardware verification failure occurs.
   - The reference engine responds by pausing DSD after the volume safety gate fails.
   - Mica has no identical `paused` engine flag, so the equivalent fail-closed seam is now at the transport payload boundary: `writeDsd()` rejects real payload whenever the active hardware-volume state is unsafe. The existing Media3 renderer `UsbRealtimeResult.Failed` path stops the stream.
   - Raw/full-scale mode remains exempt as the intentional Mica/SK02 compatibility mode.

5. **Logical PCM/DSD `REUSE` did not invalidate the previous logical playback's queued volume generation.**
   - Fresh/reconfigure close paths already invalidated pending volume requests, but hot reuse returned before doing so.
   - Both PCM and DSD `REUSE` now call `invalidatePendingVolumeRequestsLocked()` before the logical successor takes over.
   - Preserved-PCM verification is cleared at the same invalidation point, preventing a previous track's delayed verification or volume request from mutating a reused successor session.

6. **Preserved PCM verification recovery needed the reference smooth handoff semantics.**
   - Ordering is now verified as: establish USB session -> make/freeze hardware-volume decision -> write new-session silence pre-roll -> schedule preserved readback verification.
   - A matching post-pre-roll readback accepts/unfreezes the trusted target and reapplies the requested volume through a forced smooth PCM handoff.
   - A missing/mismatching readback leaves the hardware target frozen and allows only attenuation-safe PCM compensation.

7. **Reference-function accounting had two scan layers to close.**
   - The earlier Kotlin-engine scan found 134 unique names because it excluded `external fun` declarations. Its remaining unlisted names were `start`, `stop`, the nested growing-file `close`, and packetizer `ByteArray.toHexPreview`; those were explicitly classified.
   - A final full-file scan then included the top-level `UsbExclusiveNative` JNI surface and found **142 unique function names across 144 function declarations** (`close` and `readAttribute` each exist in two distinct scopes).
   - The eight additional unique native names are `open`, `writePcm`, `writeIsoPackets`, `setIsoPacketSize`, `feedbackFramesPerPacketQ16`, `transportTelemetry`, `setMaxPendingOutputUrbs`, and `flushOutput`; native `close` is separately scoped and is now listed too.
   - Comparing both Kotlin wrappers and C++ implementations confirmed the reference native behavior is retained, with Mica's additional epoch/session ownership fencing. Final result: **142 / 142 unique function names accounted for, all 144 declarations mapped by scope/group.**

### 13.2 Concurrency decision

The reference project uses separate volume/session-write locking. Mica currently keeps the physical HID transaction inside the synchronized transport open/close boundary.

This was reviewed deliberately and is **not** being mechanically changed just to resemble the reference. Mica's current boundary is more conservative: a physical USB session close/reopen cannot overtake an in-flight HID write. The audit therefore classifies `applyVolumeRequest` as `MICA_STRICTER`, not missing/partial. Splitting that lock without a separate session-visibility protocol would create a new stale-session race surface.

### 13.3 Final software validation

After the fixes above:

- `:sylvakru-usb-transport:testDebugUnitTest` -> **BUILD SUCCESSFUL**.
- Re-run of app USB/Media3 unit tests (`:app:testDebugUnitTest`) -> **BUILD SUCCESSFUL in 22s**.
- QA side-by-side debug assemble (`:app:assembleDebug -Pmica.qaSideBySide=true`) -> **BUILD SUCCESSFUL in 59s**.
  - One earlier invocation failed only because PowerShell parsed `-Pmica.qaSideBySide=true` as a task; rerunning through `cmd /c` passed. This was command-line quoting, not a code failure.
- `git diff --check` -> **exit 0**.
  - Only existing CRLF->LF warnings were printed; there were no whitespace errors.
- Full function-ledger rescan -> **142 unique names / 144 declarations accounted by scope/group**.

### 13.4 Audit closure status

The **software/reference-function audit is now closed**: there is no unexplained `MISSING`, `PARTIAL`, `待审`, or unclassified reference function in the ledger, and the final runtime integration changes pass the software gates above.

This does **not** mean every hardware path is physically re-qualified:

- Existing SK02 physical qualifications remain the baseline and must not regress.
- The SK02 Native DSD256 DSD128-silence cold-entry prime remains required and unchanged.
- iBasso/Macaron HID volume still lacks matching physical hardware in this session, so it is **software-equivalent / physically unqualified**, not physically GREEN.

### 13.5 Next work after audit closure

Do not restart the function audit. The next useful phase is physical regression/qualification of the now-audited implementation:

1. SK02 core regression: Shared PCM / Exact PCM / DoP / Native, pause/resume/seek, track reuse, mode transitions, unplug/replug and DSD64/128/256 cold-entry behavior.
2. If iBasso/Macaron-compatible hardware becomes available, physically verify hardware-volume readback, rapid latest-wins changes, frozen recovery, unsolicited events, write-only degradation and DSD fail-closed behavior.
3. Any future USB bug should first be checked against `docs/USB_REFERENCE_FUNCTION_AUDIT.md`; do not introduce a second app/service stream-reconfiguration authority.
