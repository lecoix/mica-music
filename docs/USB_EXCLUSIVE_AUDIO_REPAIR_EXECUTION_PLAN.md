# USB Exclusive Audio — Remaining Repair Execution Plan

> Coordinator-owned normative implementation plan
> Date: 2026-08-17
> Baseline for planning: P4 D91 reviewed exact `8494b0206f384fc91c03ba4d24c589bd11450921`; D106 core A20/A31 goals GREEN with one overlap-watchdog residual and one contained null-stack proof hole
> Architecture authority: `docs/USB_EXCLUSIVE_AUDIO_ARCHITECTURE.md`
> Assumption authority: `docs/USB_EXCLUSIVE_AUDIO_ASSUMPTION_AUDIT.md`
> Status: **NO IMPLEMENTER DESIGN FREEDOM**

## 0. How this plan is used

This document is an execution specification, not a list of goals. A P3 implementation directive must point to one numbered slice below and require that slice **as written**. P3 may not choose an alternative thread model, timeout, state owner, proof source, recovery-intent source, fallback policy, or test substitute.

Rules for every slice:

1. One clean baseline SHA is named before implementation.
2. One direct successor commit only.
3. Only the files/functions explicitly allowed by that slice may change, except compile-only signature propagation named in OUTBOX.
4. No adjacent A-number may be pulled in early.
5. If one normative bullet cannot be implemented without changing an accepted authority owner, P3 must STOP and report the exact bullet; it must not invent a workaround.
6. No hardware/APK in a software slice.
7. P4 independently reviews the exact successor before the next slice unlocks.
8. P6/worker planning is not allowed to reinterpret this document after a slice is frozen; only the coordinator may revise it.

## 0.1 Reference-first design gate — mandatory before every new software repair slice

Reference projects are a **required first input**, not an after-the-fact sanity check. Do not copy GPL code; compare behavior, ownership and sequencing only.

Before the coordinator freezes any new USB-exclusive software repair slice, it must first perform and record a reference preflight against the closest available implementation pattern:

- RawS: stale async work is fenced by request tokens; bounded old-worker shutdown refuses replacement on timeout.
- NeriPlayer: one session controller owns the exact current native handle/session; recovery/reconfiguration repeatedly checks the current request/generation around async work; stop/open/reconfigure side effects are authorized from controller-owned state, not by independently reconstructed metadata strings.
- sylvakru-usb / sylvakru where applicable: one engine/owner serializes worker/session lifecycle; stop intake -> bounded join -> hard-close/fail rather than timeout-as-success.

For each proposed Mica authority field, token, permit, proof, or callback gate, the plan must answer all four questions before implementation:

1. **Reference primitive:** what is the closest reference project's minimal authority primitive here: owner-held handle/session, request token/generation, captured resource, or another typed value?
2. **Why Mica must be stronger:** which concrete Media3/USB race requires more identity than that reference primitive? `PlaybackOccurrence`, `AdapterInstanceId`, topology epoch, intent revision or typed physical proof may be stronger only when that extra distinction closes a named race.
3. **Single producer/domain:** who is the sole canonical producer of every authority identity? Two modules may not independently serialize/format the same semantic fact and later compare strings for authority.
4. **Metadata separation:** which values are diagnostics/compatibility facts only and therefore forbidden from authorizing irreversible physical side effects?

A new field or authority layer is forbidden if the plan cannot answer question 2 with a concrete failing event sequence. A String assembled independently in more than one module is never an authority identity. Human-readable `facts`, format descriptions, mediaId/periodUid correlation and legacy bridge epochs may be used for diagnostics/correlation only unless a separate typed canonical owner is explicitly frozen.

Required plan marker before any new P3 implementation directive:

```text
REFERENCE_FIRST_PREFLIGHT_COMPLETE
MINIMAL_AUTHORITY_SET_FROZEN
SINGLE_CANONICAL_IDENTITY_PRODUCER_FROZEN
NO_CROSS_LAYER_STRING_AUTHORITY
```

If this marker is absent, implementation is blocked and the coordinator must audit the design first.

Mica may remain stronger than the references on `PlaybackOccurrence`, `AdapterInstanceId`, `PlaybackTopologyEpoch`, `IntentRevision`, typed PCM/Direct physical completion and frozen P2 lease redemption, but each stronger dimension must remain tied to the concrete race that justified it; it is not a license to accumulate redundant identity fields.

## 0.2 USB repair test ladder — mandatory for the next newly opened software residual

This policy becomes the default **starting with the next newly discovered USB-exclusive software bug/residual after the currently active repair slice**. It does not retroactively change an already-active implementation directive. The purpose is to stop using a large count of isolated unit/structure tests as a substitute for production-shaped cross-component evidence.

Recent Q1 failures are the motivating evidence:

- Q1-R1 passed broad software regression but failed physically because rebuild snapshot reconstruction preserved an old stack's producer tag into a new stack.
- The next Q1 rerun physically closed Q1-R1, then exposed another cross-component assumption at the Media3 `MaskingMediaSource` / child-period / renderer-stream producer-handle boundary.
- Both failures crossed component seams that were individually unit-tested. More protocol-only tests would not by themselves prove those seams.

Therefore future repair verification is split into four explicit layers.

### Layer A — focused tests for the changed invariant

For each finite residual, first run only the smallest set of tests that directly exercises the modified ownership/ordering rule, normally about 5–20 cases rather than the entire USB suite.

Requirements:

- reproduce the failing sequence before claiming the repair;
- include positive, stale/negative and the concrete race/order permutation that justified the change;
- use real production identity/value producers where practical rather than independently injecting matching synthetic strings/IDs;
- source/structure-string assertions may protect wiring shape, but they are **not** primary behavioral evidence and must not substitute for runtime/state-flow tests.

### Layer B — production-shaped USB software-flow integration

Every newly opened repair slice must add or extend a small integration harness that crosses the relevant real production seams. The preferred home/name is:

```text
app/src/test/.../UsbExclusiveSoftwareFlowIntegrationTest.kt
```

or an equivalent `androidTest` when real Media3/Android runtime behavior cannot be represented faithfully in local JVM tests.

The integration harness should keep only final hardware I/O fake. As far as the environment permits, it should use the real chain:

```text
real ExoPlayer / Media3 scheduling
-> real MediaSourceFactory / MaskingMediaSource behavior
-> real PlaybackTopologyMediaSourceFactory
-> real SampleStream producer-handle carrier
-> real Direct/PCM renderer boundary
-> real UsbExclusiveShadowCoordinator / UsbExclusivePlaybackProtocol
-> real P2 redemption/session-owner logic
-> fake USB/native transport at the final physical boundary
```

Do not replace those intermediate seams with hand-authored callback sequences when the bug concerns their interaction; otherwise the test merely repeats the assumption that failed in production.

The first integration harness should preferentially cover the software equivalents of Q1 scenarios 1–6:

1. PLAYING DOP -> DOP, same-plan retained;
2. PLAYING DOP -> DOP, rate/geometry change fresh path;
3. PLAYING DOP -> PCM;
4. Direct pause -> GAP -> resume;
5. PAUSED DOP -> DOP;
6. PAUSED DOP -> PCM.

It must also retain regression flows for every cross-component bug discovered physically. At minimum, once implemented, keep permanent coverage for:

- rebuild -> select/play DSD -> new-stack producer provenance remains current, with no old producer tag inheritance;
- Media3 masking/internal period UID differing from renderer/external UID while the exact active stream-producer handle still scopes the renderer observation and allows legal destination binding;
- stale/released producer handle cannot bind a later producer even if external occurrence/media representation is reused.

If a future residual occurs in Q1 scenario 7–11, extend this same integration harness with the corresponding flow before another hardware rerun when feasible.

### Layer C — broad regression only at major checkpoints

Keep the broad USB regression suite, but do not require the entire ~325-test matrix after every tiny repair by default.

Run the broad matrix at major confidence boundaries such as:

- independent exact-hash review before reopening a hardware gate;
- completion of a multi-slice software gate such as G1/M3;
- before Q1/Q2 physical qualification;
- before final integration/merge/release qualification;
- whenever the repair changes a shared authority contract or touches several subsystems.

A repair directive may still require a broader run when its blast radius justifies it, but test count itself is not evidence of integration correctness.

### Layer D — hardware qualification remains final authority for physical behavior

Q1/Q2 hardware testing remains required for behavior that software simulation cannot prove, including Android USB host/permission state, USBFS/kernel behavior, DAC timing, physical drain/release, reconnect, real transport progress and device-specific behavior.

Hardware must not be used as the first place to discover a cross-component software seam that a production-shaped software-flow integration test can reasonably exercise.

### Default verification order for future bugs

Starting with the next newly opened software residual, use this order unless the coordinator records a concrete reason not to:

```text
reference-first preflight
-> focused failing reproduction
-> minimal implementation
-> focused positive/negative/race tests
-> USB software-flow integration covering the affected real seams
-> independent exact-hash review
-> broad regression at the next major checkpoint
-> hardware qualification
```

Required markers in future repair directives/OUTBOXes once this policy activates:

```text
FOCUSED_RESIDUAL_TESTS_COMPLETE
USB_SOFTWARE_FLOW_INTEGRATION_COMPLETE
NO_STRUCTURE_TEST_AS_PRIMARY_EVIDENCE
BROAD_REGRESSION_DEFERRED_TO_CHECKPOINT
```

If the affected cross-component flow cannot be represented without new production test seams, the worker must report that limitation explicitly. It must not silently replace the missing integration coverage with more isolated unit tests.

---

## 1. Gate G0 — D106 core A20/A31 production wiring

### Current status

P3 D106 produced exact `8494b0206f384fc91c03ba4d24c589bd11450921`. P4 D91 independently returned:

`R_A20_HANG_GREEN / R_A31_MAIN_GREEN / OVERLAPPING_MAIN_BLOCKING_WATCHDOG_STARVE_RESIDUAL / NOT_READY_FOR_A22`

The following D106 goals are accepted and must not be reopened in the next slice:

- dedicated `mica-output-rebuild` sequencer exists;
- whole `rebuild()` is no longer posted to main;
- `ExoPlayer.release()` remains a discrete main-looper runnable;
- production timeout is exactly `USB_RECOVERY_ACTIVATION_TIMEOUT_MS == 5_000L`;
- an isolated never-returning release returns `Failed` without waiting forever;
- R2 can mint while R1's release runnable is hung, provided R2 does not itself synchronously wait on main;
- A21 refuse occurs before release;
- A32/A34 remain the terminal old-runtime proof source.

D91 exposed two finite production residuals that must close before R3:

1. **R-OVERLAP-WATCHDOG-STARVE** — `capture`, `buildCandidate`, `stageCandidate`, and `publishCandidate` still use `runOnMainBlocking`. If main is occupied by R1 release while R2 enters one of those waits, the single rebuild sequencer can block and starve R1's scheduled 5s watchdog.
2. **R-NULL-STACK-PROOF** — production `awaitOldStackBarrier` currently maps `rebuildRetiringStack == null` to `TerminalProof`; a missing retiring stack is not a physical terminal proof for a replacement rebuild.

Nothing after G0.5 unlocks until both are independently P4-green.

---

## 1.5 Slice G0.5 — asynchronous main-phase continuation + null-proof fail-closed

### 1.5.1 Purpose and baseline

Baseline is exact clean `8494b0206f384fc91c03ba4d24c589bd11450921`.

Close exactly:

- `R-OVERLAP-WATCHDOG-STARVE`;
- `R-NULL-STACK-PROOF`.

Do not change A22/A23, P2, PCM/Direct physical proof semantics, timeout value, rebuild generation ownership, or the requirement that Exo-touching work runs on main.

### 1.5.2 Frozen threading model

The model is exactly:

```text
mica-output-rebuild ScheduledExecutorService = transaction sequencer + watchdog scheduler
main looper = Exo/Media3 side-effect executor only
```

The rebuild sequencer may **schedule** main work but may never synchronously wait for main work.

Forbidden in every production rebuild phase:

- `CountDownLatch.await()` on `mica-output-rebuild`;
- `Future.get()/join()` on `mica-output-rebuild` waiting for main;
- `runBlocking`/condition-variable waiting for main;
- any helper equivalent to `runOnMainBlocking` in `capture`, candidate build, stage, publish, A21 retire, or release;
- moving `ExoPlayer.release()` off main;
- creating a second watchdog executor or timeout constant.

### 1.5.3 Exact coordinator API shape

`PlaybackOutputRebuildCoordinator.submitRebuild(...)` must become a non-blocking phase machine. The synchronous `rebuild(...)` test/helper path may remain for pure deterministic tests, but production `submitRebuild` must not call the current synchronous `prepareRebuild()` path.

Replace production-facing synchronous phase callbacks with these exact asynchronous callback contracts in `PlaybackOutputRebuildCoordinator`:

```kotlin
private val captureAsync: ((Result<Snapshot>) -> Unit) -> Unit,
private val buildCandidateAsync: (Target, Snapshot, (Result<Candidate>) -> Unit) -> Unit,
private val stageCandidateAsync: (Target, Snapshot, Candidate, (Throwable?) -> Unit) -> Unit,
private val publishCandidateAsync: (
    Long,
    Target,
    Snapshot,
    Candidate,
    (PublishDisposition) -> Unit,
) -> Unit,
```

where the generation argument is exactly `requestGeneration`, and:

```kotlin
private enum class PublishDisposition {
    PUBLISHED,
    SUPERSEDED_BEFORE_PUBLISH,
    FAILED,
}
```

There is no parallel `(Throwable?) -> Unit` publication contract.

The existing synchronous callbacks may remain only if required by `rebuild(...)` tests. Production `submitRebuild(...)` must use the four async callbacks above exclusively.

Each callback completion may occur on main. The coordinator must immediately marshal every continuation back with:

```kotlin
scheduler.execute { ... }
```

before reading/updating rebuild-generation or completion state.

No phase callback is allowed to call `onComplete` directly from main.

### 1.5.4 Exact production service wiring

In `MicaMediaService`, wire the four async callbacks exactly as discrete `mainHandler.post` operations:

```text
captureAsync:
  mainHandler.post {
    Result.capture(PlaybackStackSnapshot.capture(checkNotNull(compositePlayer)))
    -> callback(result)
  }

buildCandidateAsync:
  mainHandler.post {
    Result.capture(ExoPlaybackStackFactory.build(...))
    -> callback(result)
  }

stageCandidateAsync:
  mainHandler.post {
    runCatching { snapshot.stageInto(candidate.exoPlayer) }
    -> callback(errorOrNull)
  }

publishCandidateAsync(requestGeneration, target, snapshot, candidate, callback):
  mainHandler.post {
    val disposition = tryPublishCurrent(requestGeneration) {
      publishRebuiltPlaybackStack(target, snapshot, candidate)
    }
    callback(disposition)
  }

`tryPublishCurrent(requestGeneration) { ... }` is coordinator-owned and uses the existing `publishClaimLock` for exactly the final `generation == requestGeneration` compare plus the real publication side effect. It returns exactly `PublishDisposition.PUBLISHED`, `PublishDisposition.SUPERSEDED_BEFORE_PUBLISH`, or `PublishDisposition.FAILED`. There is no `runCatching -> callback(errorOrNull)` publication wiring and no Throwable-only publication callback.
```

Use normal Kotlin `runCatching`/`Result` representation; do not invent a new public result/proof hierarchy.

After these four production uses are removed, delete `runOnMainBlocking` if it has no other callers. Do not retain an unused rebuild-only blocking helper.

`releaseCandidate` remains `mainHandler.post { candidate.exoPlayer.release() }` and is cleanup only.

### 1.5.5 Exact phase machine and generation checks

For one request generation `R`, `submitRebuild` must execute exactly this sequence:

```text
sequencer:
  acquire existing `publishClaimLock`
  R = generation.incrementAndGet()
  onGenerationPublished(R)
  release `publishClaimLock`
  request captureAsync and RETURN to scheduler

capture callback -> scheduler:
  if generation != R -> Superseded(R); stop
  if capture failed -> Failed(R); stop
  transform snapshot on scheduler only
  request buildCandidateAsync and RETURN

build callback -> scheduler:
  if generation != R:
      release built candidate if one exists
      Superseded(R); stop
  if build failed -> Failed(R); stop
  request stageCandidateAsync and RETURN

stage callback -> scheduler:
  if generation != R:
      release exact candidate
      Superseded(R); stop
  if stage failed:
      release exact candidate
      Failed(R); stop
  arm/start retirement exactly once using existing A21/startRetirement path
  arm the existing 5s watchdog on the scheduler
  RETURN

retirement completion OR watchdog -> scheduler:
  exactly one wins via existing atomic completion gate
  watchdog -> Failed(R) immediately; release candidate; old protocol remains Retiring
  retirement error -> Failed(R); release candidate
  successful retirement callback -> evaluate exact old-stack barrier

barrier success + generation == R:
  request publishCandidateAsync carrying requestGeneration=R and RETURN

main publish runnable:
  call coordinator-owned `tryPublishCurrent(R) { publishRebuiltPlaybackStack(target, snapshot, candidate) }`
  `tryPublishCurrent` acquires the existing `publishClaimLock`, performs the final `generation == R` compare, and only if still current executes `publishRebuiltPlaybackStack(...)` inside that same critical section
  return exactly one disposition: `PUBLISHED`, `SUPERSEDED_BEFORE_PUBLISH`, or `FAILED`

publish callback -> scheduler:
  `PUBLISHED` -> Published(R); do not reclassify/release merely because a newer generation mints after the publication linearization point
  `SUPERSEDED_BEFORE_PUBLISH` -> release exact candidate once; Superseded(R)
  `FAILED` -> release exact candidate once; Failed(R)
```

`transformSnapshot` executes on `mica-output-rebuild`, not main, and must remain pure with respect to Exo/USB side effects.

The scheduler must be free between every `request ...Async` and its callback. This freedom is the required fix; P3 may not replace it with a bounded blocking wait.

`publishClaimLock` has exactly two allowed production critical sections in G0.5:

1. generation mint: lock -> `generation.incrementAndGet()` + `onGenerationPublished(R)` -> unlock;
2. final real publication: lock -> compare `generation == requestGeneration` -> if current, execute the real `publishRebuiltPlaybackStack(...)` side effect -> unlock.

The lock must never span a post/wait, capture, candidate build, stage, retirement, `ExoPlayer.release()`, native/USB teardown, watchdog wait, or callback continuation. This makes generation mint and the real publication side effect mutually ordered. A post-publication scheduler callback must not perform a second generation comparison that can retroactively turn an already-published candidate into `Superseded`.

### 1.5.6 Watchdog ordering — frozen

The 5s watchdog is armed **after A21/stage have succeeded and immediately before/when starting old-stack retirement**, using the same `outputRebuildExecutor` scheduler and the existing `USB_RECOVERY_ACTIVATION_TIMEOUT_MS`.

Once armed:

- later requests may mint new generations while main is blocked;
- later requests may themselves be waiting for their posted main phase, but that waiting is represented only as pending callbacks, never by occupying the scheduler thread;
- therefore the scheduler must still execute the older watchdog at its deadline;
- watchdog fire wins the atomic completion gate and delivers `Failed` without waiting for any main callback;
- a late main callback for a timed-out/superseded request may only trigger exact candidate/resource cleanup and must not publish.

### 1.5.7 Null retiring-stack proof — exact rule

For a **replacement rebuild** where a published old Exo/composite stack was captured for retirement:

```kotlin
val stack = rebuildRetiringStack
if (stack != null && stack.hasTerminalOldRuntimeProof()) {
    OldStackBarrierDisposition.TerminalProof
} else {
    OldStackBarrierDisposition.FailedWithoutProof
}
```

`stack == null` is never `TerminalProof` in replacement publication.

Initial service startup/no-old-stack construction is not a replacement rebuild and must continue through its existing startup path. Do not create a synthetic terminal proof for startup.

### 1.5.8 Exact production files allowed

Production:

1. `app/src/main/java/com/mica/music/media/PlaybackOutputRebuildCoordinator.kt`
2. `app/src/main/java/com/mica/music/media/MicaMediaService.kt`

Tests may modify/add only rebuild coordinator/service-structure tests required for the matrix below.

Forbidden:

- recovery policy/A22/A23 files;
- `UsbExclusivePlaybackProtocol` authority changes;
- P2/session owner;
- PCM/Direct renderer/sink/runtime proof logic;
- JNI/native/USBFS;
- any new timeout/proof type/executor;
- hardware/APK.

### 1.5.9 Finite acceptance matrix

| ID | Sequence | Required result |
|---|---|---|
| G05-T1 | R1 reaches retirement; main blocks forever in R1 `release()` | R1 watchdog returns `Failed` at injected deadline; no publish |
| G05-T2 | T1 + R2 requested after R1 release occupies main | R2 generation mints immediately on sequencer before any R2 main callback can run |
| G05-T3 | T2 + R2 `captureAsync` is pending behind blocked main | R1 watchdog still executes on time; sequencer is not occupied by R2 |
| G05-T4 | R1 times out; main later unblocks and R1 release callback returns terminal proof | R1 remains failed; cannot publish |
| G05-T5 | R2 supersedes R1 while R1 stage/retire callback is late | late R1 callback cannot publish or mutate R2; exact R1 candidate cleanup only |
| G05-T6 | R2 superseded while its candidate build main callback completes | candidate is released exactly once; R2 result `Superseded` |
| G05-T7 | stage callback fails | candidate released; `Failed`; retirement not started |
| G05-T8 | R3 races with R2 final publication | if R3 acquires `publishClaimLock` and mints first, R2 main publication performs no side effect and R2 becomes `Superseded`; if R2 acquires the lock, verifies currentness, and completes `publishRebuiltPlaybackStack(...)` first, R2 is `Published` and R3 mints afterward. No outcome may publish R2 and then release/reclassify that same candidate merely because R3 minted before the async callback continuation ran. |
| G05-T9 | replacement rebuild has `rebuildRetiringStack == null` | `FailedWithoutProof`; never `Published` |
| G05-T10 | replacement rebuild has exact terminal old-stack proof | publication may proceed only if generation still current |
| G05-T11 | A21 refuses | no release runnable; no watchdog-retired success; `Failed` |
| G05-T12 | source structure | no production rebuild phase calls `runOnMainBlocking`; no blocking wait from sequencer to main |

Required regressions:

- D91 isolated-hang tests;
- D91 R2-mints-while-R1-release-hung tests;
- A21 refuse;
- return-without-proof;
- Option B Direct t13;
- PCM A32/A34 proof matrices;
- Debug + Perf compile;
- `git diff --check`.

### 1.5.10 Exit gate

Exactly one direct successor from `8494b020`.

P3 OUTBOX, when this slice is eventually authorized, must state:

```text
OVERLAP_WATCHDOG_STARVE_CLOSED
NULL_STACK_IS_NOT_TERMINAL_PROOF
NO_BLOCKING_MAIN_WAIT_ON_REBUILD_SEQUENCER
NO_A22_A23_CHANGE
```

Then STOP for P4 exact-hash review. R3 remains locked until P4 returns GREEN on G05-T1..T12.

---

## 2. Slice R3 — A22 + A23 recovery intent/reconstruction closure

### 2.1 Purpose

Close exactly:

- **A22** — a captured `requireFrameProgress` Boolean must not remain authority for the recovery window;
- **A23** — interrupted `resumePlaybackRequested` is reconstruction metadata only and must not own final semantic resume.

No rebuild redesign, no output-owner redesign, no new recovery state machine.

### 2.2 Required authority model — frozen

The following owners are fixed:

| Fact | Sole owner |
|---|---|
| latest semantic PLAY/PAUSE + `IntentRevision` | existing service-lifetime `PlaybackIntentLedger` inside `UsbExclusivePlaybackCoordinator` |
| recovery epoch/action/backoff/budget | existing `UsbRecoveryCoordinator` |
| current USB generation / request / ACTIVE-exact facts | frozen P2 `UsbOutputSessionOwner` / `PlaybackOutputFacts` |
| final rebuilt Exo execution state | existing rebuild publication path via `restoreAfterTechnicalQuiesce()` and latest ledger |
| queue/index/position interrupted state | reconstruction metadata only |
| transport activation success | `UsbRecoveryActivationPolicy` from exact fresh P2 facts, optionally plus fresh-session frame progress when the **current** ledger says PLAY |

`usbResumePlaybackRequested`, persisted/interrupted resume flags, `player.playWhenReady`, renderer STARTED, or an earlier intent snapshot may never substitute for the latest ledger at recovery reconciliation.

### 2.3 Exact production files allowed

Primary files:

1. `app/src/main/java/com/mica/music/media/usb/UsbRecoveryActivationPolicy.kt`
2. `app/src/main/java/com/mica/music/media/MicaMediaService.kt`
3. `app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt` — **only** for the read-only semantic-intent snapshot accessor defined below, if needed.

Tests:

4. `app/src/test/java/com/mica/music/media/usb/UsbRecoveryActivationPolicyTest.kt`
5. add one focused source/structure test under `app/src/test/java/com/mica/music/media/` for the service wiring described below.

Forbidden in this slice:

- `PlaybackOutputRebuildCoordinator` semantic changes;
- `UsbRecoveryCoordinator` epoch/backoff algorithm changes;
- P2/session-owner changes;
- PCM/Direct runtime/proof changes;
- JNI/native/USBFS;
- fallback policy changes;
- `PlaybackIntentLedger` semantics changes;
- new public proof/receipt/capability type;
- new timeout constant;
- direct `player.play()/pause()` calls from recovery code.

### 2.4 Exact API/data-model changes

#### 2.4.1 `UsbRecoveryActivationExpectation`

Change it from:

```kotlin
internal data class UsbRecoveryActivationExpectation(
    val action: UsbRecoveryAction,
    val expectedRequest: UsbOutputRequest?,
    val requireFrameProgress: Boolean,
    val deadlineElapsedRealtimeMs: Long,
)
```

to exactly:

```kotlin
internal data class UsbRecoveryActivationExpectation(
    val action: UsbRecoveryAction,
    val expectedRequest: UsbOutputRequest?,
    val deadlineElapsedRealtimeMs: Long,
)
```

There is **no replacement captured Boolean** and no captured semantic-intent field in the expectation.

Rationale: recovery action identity and deadline may be captured; semantic intent must remain live.

#### 2.4.2 Read-only latest-intent accessor

Add exactly this behavior to `UsbExclusiveShadowCoordinator`:

```kotlin
@Synchronized
fun semanticIntentSnapshot(): IntentSnapshot = ledger.snapshot()
```

This is the **only** production recovery semantic-intent read path. `MicaMediaService` must obtain recovery semantic intent only through:

```kotlin
usbExclusivePlaybackCoordinator.semanticIntentSnapshot()
```

It is read-only. It does not publish/adopt/advance any protocol state. Do not expose the mutable ledger object through a new API.

Forbidden substitutes in recovery code:

- direct service access to `.ledger`;
- stack-local adopted intent;
- `player.playWhenReady`;
- `usbResumePlaybackRequested`;
- any captured Boolean or earlier `IntentSnapshot`.

#### 2.4.3 `UsbRecoveryActivationPolicy.evaluate`

Signature must accept the **current** semantic intent snapshot at evaluation time:

```kotlin
fun evaluate(
    expectation: UsbRecoveryActivationExpectation,
    facts: PlaybackOutputFacts,
    currentIntent: IntentSnapshot,
    elapsedRealtimeMs: Long,
): UsbRecoveryActivationState
```

Keep every existing fresh-session/request/ACTIVE/exclusive/exact check unchanged.

Replace the old branch:

```text
expectation.requireFrameProgress
```

with exactly:

```text
currentIntent.desired == PlaybackIntent.PLAY
```

Semantics:

- current `PAUSE`: exact fresh ACTIVE/exclusive/exact session may succeed without frame progress;
- current `PLAY`: the same fresh session must additionally have `runtimeHealth.completedFrames > 0` before `SUCCEEDED`;
- timeout while PLAY has no completed frame -> `FAILED`;
- switching PLAY -> PAUSE during the wait removes the frame-progress requirement on the next evaluation;
- switching PAUSE -> PLAY adds the frame-progress requirement on the next evaluation;
- an intent revision change is **not** `STALE` and does not mint a new recovery epoch. Recovery action staleness remains owned by recovery epoch/action/request identity.

Do not compare `IntentRevision` numerically to declare failure. The revision is a freshness source whose latest snapshot changes the requirement; it is not another recovery generation.

### 2.5 Exact `MicaMediaService` wiring

#### 2.5.1 `continueUsbRecovery`

After a `Published` rebuild, construct expectation with only:

- `action = request.action`
- `expectedRequest = usbRecoveryRequest`
- `deadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + USB_RECOVERY_ACTIVATION_TIMEOUT_MS`

Delete `requireFrameProgress = usbResumePlaybackRequested`.

Do **not** read semantic intent here for later use. Do not capture `playWhenReady` here.

#### 2.5.2 `reconcilePendingUsbRecoveryActivation`

All `MicaMediaService` recovery bookkeeping fields and recovery ACK processing are **main-looper confined**. Any result arriving from the D106 `mica-output-rebuild` sequencer must be marshalled to main before creating, clearing, evaluating, or ACKing `usbRecoveryActivationExpectation` / recovery action state.

`reconcilePendingUsbRecoveryActivation()` is main-looper only. One non-suspending main-loop turn must perform, in this exact order:

1. validate existing recovery epoch and output path exactly as today;
2. call exactly once `usbExclusivePlaybackCoordinator.semanticIntentSnapshot()`;
3. call `UsbRecoveryActivationPolicy.evaluate(...)` with that snapshot;
4. immediately process `WAITING/STALE/FAILED/SUCCEEDED` and any recovery coordinator ACK/backoff transition.

There may be **no** `Handler.post`, coroutine suspension, future, callback, executor hop, or other asynchronous boundary between steps 2–4. The `IntentSnapshot` read is the semantic linearization point for that reconcile turn.

`SUCCEEDED` means the recovery action's transport/session activation criteria are satisfied under the semantic requirement current at that exact linearization point. It must **not** call `play()`, `pause()`, set `playWhenReady`, or write `usbResumePlaybackRequested`.

Final Exo execution state remains owned by the rebuild publication path, which already performs:

```text
candidate.playbackStack.restoreAfterTechnicalQuiesce()
-> latest ledger snapshot
-> candidate.exoPlayer.playWhenReady = (latest == PLAY)
```

Do not duplicate that logic in recovery.

#### 2.5.3 `usbResumePlaybackRequested`

Do not delete or globally rename this field in R3. Its existing detach/interrupted-playback reconstruction uses remain allowed.

However, after R3 it is forbidden as recovery activation authority:

- no `UsbRecoveryActivationExpectation` field derived from it;
- no `UsbRecoveryActivationPolicy` branch on it;
- no success/failure ACK condition derived from it.

The field may continue to record reconstruction/product bookkeeping elsewhere until later cleanup.

### 2.6 Exact finite acceptance matrix

All cases use a fresh recovery action/session generation and otherwise-valid exact ACTIVE facts unless stated.

| ID | Sequence | Required result |
|---|---|---|
| R3-T1 | PLAY at issue -> PLAY at reconcile -> completedFrames=0 before deadline | `WAITING` |
| R3-T2 | same as T1 -> completedFrames>0 | `SUCCEEDED` |
| R3-T3 | PLAY at issue -> PAUSE before reconcile -> completedFrames=0 | `SUCCEEDED` |
| R3-T4 | PAUSE at issue -> PLAY before reconcile -> completedFrames=0 | `WAITING` |
| R3-T5 | T4 then completedFrames>0 | `SUCCEEDED` |
| R3-T6 | PLAY no frame -> deadline reached | `FAILED` |
| R3-T7 | PLAY no frame -> PAUSE before deadline -> reconcile | `SUCCEEDED`, no frame required |
| R3-T8 | current ledger PAUSE + exact ACTIVE + completedFrames=0; publish PLAY before the reconcile main-turn snapshot | that same reconcile must read PLAY, return `WAITING`, and emit no success ACK; after completedFrames>0 a later reconcile may `SUCCEEDED` |
| R3-T9 | wrong fresh request | existing `STALE` behavior unchanged |
| R3-T10 | generation not newer than recovery epoch | `WAITING` until deadline, then `FAILED` |
| R3-T11 | ACTIVE but permission/claim/exclusive/signalExact false | `FAILED` unchanged |
| R3-T12 | `usbResumePlaybackRequested=true`, latest ledger PAUSE, no frames | `SUCCEEDED`; stale Boolean cannot force progress |
| R3-T13 | `usbResumePlaybackRequested=false`, latest ledger PLAY, no frames | `WAITING`; stale Boolean cannot waive progress |
| R3-T14 | recovery rebuild publication restores execution after PLAY->PAUSE | final candidate `playWhenReady=false` via existing `restoreAfterTechnicalQuiesce()` path |
| R3-T15 | recovery rebuild publication restores execution after PAUSE->PLAY | final candidate `playWhenReady=true` via existing ledger path |

Required structural assertions:

- production source contains no `requireFrameProgress = usbResumePlaybackRequested`;
- `UsbRecoveryActivationExpectation` contains no semantic Boolean;
- recovery activation policy receives an `IntentSnapshot` at evaluation time;
- recovery code does not call `player.play()`/`pause()` or directly own final semantic restore;
- `USB_RECOVERY_ACTIVATION_TIMEOUT_MS` remains the existing single activation deadline.

Required regressions:

- all existing `UsbRecoveryActivationPolicyTest` cases adapted without weakening request/generation/exactness checks;
- `UsbRecoveryCoordinatorTest` green unchanged;
- `UsbLifecycleRecoveryCoordinatorTest` green unchanged;
- D106 rebuild concurrency tests green;
- A10/A11 intent restore tests green;
- Option B Direct/PCM focused matrices green;
- Debug + Perf compile;
- `git diff --check`.

### 2.7 Commit/review gate

R3 is exactly one direct successor from the P4-GREEN D106 SHA.

P3 OUTBOX must state:

```text
A22_CLOSED
A23_CLOSED_AS_METADATA_ONLY
NO_RECOVERY_INTENT_STATE_MACHINE_ADDED
NO_REBUILD_P2_PCM_DIRECT_CHANGE
```

Then STOP. P4 independently reruns R3-T1..T15 and the regression matrix. No hardware yet.

---

## 3. Gate G1 — software repair closure / one-writer audit

### 3.0 Current G1 status

P4 D94 accepted exact R3 checkpoint `36de455309d9fe157addc6f51f01efc1d9c642a5` as:

```text
R3_GREEN / A22_GREEN / A23_METADATA_ONLY_GREEN / READY_FOR_G1
```

P6 D18 then ran the required whole-production audit and returned:

```text
M3_SOFTWARE_ONE_WRITER_RED / REBUILD_GENERATION_MINT_BLOCKED_BY_DIRECT_GAP_QUIESCE / NOT_READY_FOR_Q1
```

Architecture disposition remains `NO_ARCHITECTURE_GAP`.

The first finite residual is G1 question 6 only: `PlaybackOutputRebuildCoordinator.mintGeneration()` currently invokes production `onGenerationPublished` while holding `publishClaimLock`, and `MicaMediaService` wires that callback to synchronous Direct GAP quiesce/join followed by `UsbOutputRuntime.owner.invalidate()`. A blocked GAP join can therefore occupy both the rebuild sequencer mint step and `publishClaimLock`, preventing a newer rebuild generation from minting.

Q1/M4 remains locked until the bounded slice below is implemented and independently P4-green, then G1 is rerun.

### 3.1 Slice G1-R1 — async Direct pre-invalidation quiesce; pure generation mint

#### 3.1.1 Purpose and baseline

Baseline is exact clean:

`36de455309d9fe157addc6f51f01efc1d9c642a5`

Close exactly:

`REBUILD_GENERATION_MINT_BLOCKED_BY_DIRECT_GAP_QUIESCE`

Do not alter A22/A23, G0.5 publication linearization, P2 session semantics, Direct physical proof semantics, PCM proof semantics, recovery policy, timeout values, or hardware behavior.

#### 3.1.2 Frozen ownership and ordering

The required order for every production rebuild request generation `R` is exactly:

```text
mica-output-rebuild:
  lock publishClaimLock
  R = generation.incrementAndGet()
  unlock publishClaimLock
  request Direct pre-invalidation quiesce on main
  RETURN; sequencer is free

main:
  synchronously stop/join the currently registered Direct pause GAP, if any
  callback(errorOrNull)

callback -> mica-output-rebuild:
  if generation != R:
      Superseded(R)
      DO NOT invalidate UsbOutputRuntime.owner
      DO NOT capture/build/stage/publish
      stop
  if quiesce failed:
      Failed(R)
      DO NOT invalidate owner
      stop
  UsbOutputRuntime.owner.invalidate()
  continue into existing G0.5 captureAsync -> buildCandidateAsync -> stageCandidateAsync -> retirement -> publish sequence
```

The physical order **Direct GAP quiesced/joined -> P2 owner invalidated** is preserved for the current generation. Generation mint itself occurs before either operation and is never blocked by either operation.

A stale quiesce completion is cleanup/observation only. It has no authority to invalidate the current P2 owner, capture a stack, build/stage a candidate, retire, or publish.

#### 3.1.3 Exact coordinator API changes

In `PlaybackOutputRebuildCoordinator`, remove the production teardown meaning from `onGenerationPublished`. Production `submitRebuild(...)` must not execute any caller callback while holding `publishClaimLock` during generation mint.

Generation mint becomes exactly:

```kotlin
private fun mintGeneration(): Long = synchronized(publishClaimLock) {
    generation.incrementAndGet()
}
```

No callback, quiesce, owner invalidation, logging callback, main wait, native/USB teardown, or other side effect is permitted inside this critical section.

Add exactly one asynchronous pre-invalidation phase contract to the coordinator:

```kotlin
private val preInvalidateAsync: (Long, (Throwable?) -> Unit) -> Unit,
private val invalidateOwner: (Long) -> Unit,
```

`submitRebuild(...)` must invoke `preInvalidateAsync(R, callback)` immediately after mint and return control to the scheduler. The callback continuation must marshal back through the existing `scheduler.execute { ... }` before checking generation or calling `invalidateOwner`.

`invalidateOwner(R)` is called only after `generation == R` has been revalidated on `mica-output-rebuild`. It remains a short current-generation side effect and must not perform Direct GAP join, main wait, Exo release, native close, or another blocking teardown.

The existing G0.5 `captureAsync/buildCandidateAsync/stageCandidateAsync/publishCandidateAsync` contracts and `tryPublishCurrent` publication lock semantics remain unchanged.

#### 3.1.4 Exact Direct quiescence seam

In `DirectDsdTeardownQuiescenceCoordinator`, expose exactly one internal synchronous operation for the already-existing registered quiescer:

```kotlin
fun quiesceActiveForOutputRebuild(): DirectDsdTeardownQuiesceOutcome =
    state.quiesceActive()
```

This does not create a new authority or proof. It only separates the already-existing Direct GAP stop/join from owner invalidation so the service can schedule it asynchronously.

Do not add a second registration state, token, generation type, proof type, timeout, or executor.

The existing `quiesceBeforeOwnerInvalidation(...)` may remain only if still used outside the rebuild path; production rebuild wiring must not call it after this slice. If it has no production/test caller after signature propagation, remove it rather than maintaining two rebuild-authority paths.

#### 3.1.5 Exact MicaMediaService wiring

Production `MicaMediaService` must wire:

```text
preInvalidateAsync(requestGeneration, callback):
  mainHandler.post {
    val error = runCatching {
      val outcome = DirectDsdTeardownQuiescenceCoordinator.quiesceActiveForOutputRebuild()
      DiagnosticLog.event(
        "UsbOutputRebuild",
        "barrier=pre-invalidate-quiesce generation=$requestGeneration outcome=$outcome"
      )
    }.exceptionOrNull()
    callback(error)
  }

invalidateOwner(requestGeneration):
  DiagnosticLog.event(
    "UsbOutputRebuild",
    "barrier=owner-invalidate generation=$requestGeneration"
  )
  UsbOutputRuntime.owner.invalidate()
```

`preInvalidateAsync` must use `mainHandler.post`; P3 may not choose another executor. It may block the main runnable while joining the GAP worker, but it may not block `mica-output-rebuild` because the sequencer has already returned and waits only through callback state.

The callback itself must not invalidate owner. Only the coordinator continuation, after current-generation revalidation, may invoke `invalidateOwner(R)`.

#### 3.1.6 Failure and supersede semantics

Exactly these outcomes are allowed:

- quiesce returns normally + R still current -> invalidate owner -> continue G0.5 phases;
- quiesce throws -> `Failed(R)`; no owner invalidation; no candidate side effect;
- R superseded before quiesce callback continuation -> `Superseded(R)`; no owner invalidation; no later phase;
- newer R2 may mint while older R1 main quiesce is blocked; R2 does not wait for R1's quiesce to finish before its generation mint;
- if R1 later returns after R2 minted, R1 callback is stale and must not invalidate the owner belonging to R2/current state;
- no timeout is introduced for Direct quiesce in this slice; timeout is not converted into success/proof;
- no request may reach capture/build/stage before its own current-generation pre-invalidation quiesce has completed and owner invalidation has executed.

#### 3.1.7 Exact files allowed

Production only:

1. `app/src/main/java/com/mica/music/media/PlaybackOutputRebuildCoordinator.kt`
2. `app/src/main/java/com/mica/music/media/MicaMediaService.kt`
3. `app/src/main/java/com/mica/music/media/dsd/DirectDsdTeardownQuiescence.kt`

Tests may modify/add only focused rebuild/quiescence/service-structure tests needed for the finite matrix below.

Forbidden:

- A22/A23 or recovery-policy changes;
- P2/session-owner implementation changes;
- PCM/Direct renderer/pump/runtime proof behavior changes other than calling the existing quiescer through the frozen coordinator seam;
- JNI/native/USBFS/RAW_DATA/FFmpeg;
- new executor or timeout;
- new authority/proof/receipt/capability type;
- G0.5 final publication API/`publishClaimLock` compare+publish rule changes;
- APK/hardware.

#### 3.1.8 Finite acceptance matrix

| ID | Sequence | Required result |
|---|---|---|
| G1R1-T1 | R1 mint, Direct GAP quiesce blocks on main | `publishClaimLock` released immediately after mint; `mica-output-rebuild` remains free |
| G1R1-T2 | T1 + submit R2 while R1 quiesce still blocked | R2 generation mints before R1 quiesce returns |
| G1R1-T3 | R1 quiesce later returns after R2 minted | R1 -> `Superseded`; R1 does not call `UsbOutputRuntime.owner.invalidate()` and does not enter capture/build/stage/publish |
| G1R1-T4 | R current, quiesce returns `NO_ACTIVE_SESSION` | current R invalidates owner exactly once, then enters capture |
| G1R1-T5 | R current, quiesce returns `NO_ACTIVE_GAP` | same as T4 |
| G1R1-T6 | R current, quiesce returns `QUIESCED` | GAP join completes before owner invalidation; invalidate exactly once; then capture |
| G1R1-T7 | quiesce throws | `Failed`; no owner invalidation; no capture/build/stage/publish |
| G1R1-T8 | R superseded while quiesce runnable is pending on main | late runnable may quiesce the registered old GAP, but callback continuation cannot invalidate owner or proceed |
| G1R1-T9 | structure | generation mint critical section contains only increment; no caller callback/Direct quiesce/owner invalidation inside `publishClaimLock` |
| G1R1-T10 | structure | rebuild production no longer calls `quiesceBeforeOwnerInvalidation`; exact preInvalidateAsync main-post + scheduler continuation + current check is present |
| G1R1-T11 | regression | G05-T1..T12 remain green, including watchdog liveness and final publication race |
| G1R1-T12 | regression | R3-T1..T15, A21, A32/A34, Direct typed proof t13 remain green |

Required compilation/checks:

- focused G1R1-T1..T12;
- G0.5 rebuild async matrix;
- R3 recovery-intent matrix;
- Direct teardown/quiescence focused tests;
- Option B Direct typed proof + PCM physical proof regressions;
- Debug + Perf compile;
- `git diff --check`.

#### 3.1.9 Commit/review gate

Exactly one direct successor from `36de455309d9fe157addc6f51f01efc1d9c642a5`.

P3 OUTBOX must state:

```text
REBUILD_GENERATION_MINT_BLOCKED_BY_DIRECT_GAP_QUIESCE_CLOSED
GENERATION_MINT_PURE_SHORT_CRITICAL_SECTION
STALE_PREINVALIDATION_CANNOT_INVALIDATE_OWNER
NO_A22_A23_P2_PROOF_CHANGE
```

Then STOP for P4 exact-hash review. G1 must be rerun after P4 GREEN. Q1/M4 remains locked until that rerun returns `M3_SOFTWARE_ONE_WRITER_GREEN`.

### 3.2 G1 rerun after G1-R1

After P4 GREEN on the G1-R1 successor, rerun one **read-only whole-production-authority audit** before hardware.

P4/P6 audit questions remain fixed:

1. Can any production caller still mutate PCM/DOP family/currentness/retirement outside `UsbExclusivePlaybackProtocol`?
2. Can any legacy `ManualNavigationTransitionBridge` / `DirectDsdTrackTransitionCoordinator` path still authorize rather than observe/project?
3. Can any recovery/fallback path set final semantic `playWhenReady` from captured metadata rather than latest ledger?
4. Can any USB-capable final write bypass P2 active-session/request/cleanup lease redemption?
5. Can any rebuild publish without exact terminal old-runtime proof?
6. Can any newer rebuild be blocked from minting by a lock held across old teardown?
7. Can any caller construct/submit Direct physical release/retained authority again?

Result must be either:

```text
M3_SOFTWARE_ONE_WRITER_GREEN
```

or one first finite residual. Any new residual becomes its own narrow software slice and must be P4-green before Q1/M4.

### 3.3 G1-R2 — DESIGN REOPENED AFTER P4 D96; implementation blocked pending reference-first authority-minimization audit

#### 3.3.1 Historical residual and failed implementation attempt

P6 D19 identified:

```text
M3_SOFTWARE_ONE_WRITER_RED / LEGACY_MANUAL_NAVIGATION_FRESH_DIRECT_RETIREMENT_BYPASSES_PROTOCOL_CLAIM / NOT_READY_FOR_Q1
```

P3 D111 implemented `2257b615ad0ae5ea02a6362c88c364c0f3393ac5`, adding a `DirectFreshRetirementPermit`. P4 D96 then returned:

```text
G1_R2_RED / FRESH_RETIREMENT_TARGET_FACTS_IDENTITY_DOMAIN_MISMATCH / NOT_READY_FOR_G1_RERUN
```

Classification: `IMPLEMENTATION_RESIDUAL / NO_ARCHITECTURE_GAP`.

The failure is normative evidence that the frozen G1-R2 plan was over-specified: it made `targetFacts: String` part of authority even though production protocol binding and legacy bridge correlation generate different representations for the same legal DOP destination. Unit tests hid the mismatch by supplying the same synthetic facts string to both sides.

**The previous §3.3.3–§3.3.10 authority shape is superseded. Do not patch it by canonicalizing one String representation. Do not issue another P3 implementation directive from the old field list.**

#### 3.3.2 Mandatory reference-first preflight for the redesign

Before a replacement G1-R2 slice is frozen, the coordinator must audit the closest reference patterns and the real Mica production call chain.

Reference baseline:

- **NeriPlayer:** irreversible USB stop/open/reconfigure is authorized by one session owner from current handle/session state plus transition/request gates. Human-readable format descriptions are compatibility/reuse inputs, not independently reconstructed authority identity.
- **RawS:** where evidence is available, stale async work is fenced by request tokens rather than metadata re-derivation; the public repository does not contain the complete USB-exclusive core, so do not infer missing implementation details.
- **sylvakru-usb/sylvakru:** use only the verified owner/lifecycle behavior available in the local audit; do not infer an authority contract from absent code.

Mica production chain to reconstruct exactly:

```text
manual command
  -> protocol MANUAL mutation
  -> Media3 stream/timeline callbacks
  -> protocol observe/bind current destination
  -> legacy ManualNavigation bridge correlation
  -> DirectDsdMedia3Renderer fresh/non-retained branch
  -> prepareFreshTrackTransitionWithP2
  -> closePump
  -> typed Direct release observation/receipt
```

The redesign must identify the **smallest owner-held authority set** sufficient to prove that the physical source runtime being retired belongs to the current MANUAL destination transition.

#### 3.3.3 Reference-first preflight result — frozen minimal authority set

The authority-minimization audit is complete. The replacement fresh-retirement path uses only the identities that close a named production race:

- renderer-supplied `sourceOccurrence`: proves which logical source the currently held Direct runtime belongs to; required because one `RuntimeIdentity` may survive a retained A -> B handoff while the source occurrence changes;
- renderer-supplied `targetOccurrence`: proves which exact destination this callback observed; required to reject late B after B -> C supersede or same-mediaId/period reuse;
- renderer-supplied `runtimeIdentity`: proves which physical Direct runtime is about to be retired; required to reject an old renderer/session runtime;
- adapter identity: required for renderer/adapter churn, but it is intrinsic to the calling `UsbExclusiveShadowAdapter` and is not a renderer argument.

Explicitly **not authority inputs/outputs** for this fresh path:

- caller `mutationId`: forbidden. D111 re-read `protocol.snapshot().mutation` at claim time and passed that current id back to the same protocol, so it was not an originating causal token and added no stale fence;
- `targetFacts` / format identity String: forbidden. Format/rate/geometry may choose retained-vs-fresh policy, but may not authorize physical retirement;
- fresh `activationId`: forbidden. There is no asynchronous claim/receipt/commit lifecycle here, so a generated-only activation id has no authority function;
- returned `outputTarget`: forbidden. `outputTarget` remains an internal protocol currentness check only; successor USB write authority is separately obtained/redeemed by the existing P2 + Direct-stage path;
- `ManualNavigationTransitionEpoch` fields: correlation/diagnostic/policy input only, never physical authority.

Reference-first markers are therefore satisfied:

```text
REFERENCE_FIRST_PREFLIGHT_COMPLETE
MINIMAL_AUTHORITY_SET_FROZEN
SINGLE_CANONICAL_IDENTITY_PRODUCER_FROZEN
NO_CROSS_LAYER_STRING_AUTHORITY
```

#### 3.3.4 Exact replacement claim contract — frozen

Renderer-facing adapter seam:

```kotlin
fun authorizeFreshDirectRetirement(
    sourceOccurrence: PlaybackOccurrence?,
    targetOccurrence: PlaybackOccurrence?,
    runtimeIdentity: RuntimeIdentity,
): Boolean
```

The receiver `UsbExclusiveShadowAdapter` supplies its own `adapter.id`; renderer code does not pass or reconstruct adapter identity.

The shadow stack may call one synchronized protocol-owner method with that intrinsic adapter id:

```kotlin
fun authorizeFreshDirectRetirement(
    adapterInstanceId: AdapterInstanceId,
    sourceOccurrence: PlaybackOccurrence,
    targetOccurrence: PlaybackOccurrence,
    runtimeIdentity: RuntimeIdentity,
): Boolean
```

This is a one-shot currentness/ownership gate only. It returns no permit object, activation id, mutation id, output target, facts string, proof, receipt or cleanup capability, and it does not hold a protocol monitor/lock across physical teardown.

At one synchronized protocol linearization point it returns `true` only if all of these are true:

- lifecycle is `Active`;
- no topology transaction is active;
- current mutation exists, is `MANUAL`, is destination-bound, and has no accepted `sourceRetirement` yet;
- current target family is `DOP`;
- current mutation source occurrence equals `sourceOccurrence`;
- current mutation target occurrence equals `targetOccurrence`;
- current destination adapter equals `adapterInstanceId`;
- `applicationCurrent.occurrence == targetOccurrence`;
- current family ownership is `DopOwned` and its ownership id equals the mutation's captured source ownership id;
- owned occurrence equals `sourceOccurrence`;
- owned adapter equals `adapterInstanceId`, and that adapter is still registered;
- owned runtime identity equals `runtimeIdentity`;
- protocol `outputTarget` is not `Unavailable`;
- owned write lease's output target equals the protocol's current `outputTarget`.

Any mismatch returns `false` with no protocol mutation and no physical side effect.

The claim intentionally does **not** compare `targetFacts`, mediaId, periodUid, bridge request id, bridge format identity, or a caller mutation id.

A newer MANUAL mutation that supersedes the callback **before this linearization point** must make the old callback fail the target/current checks. A newer mutation after a successful claim does not retroactively invalidate the already-authorized old-source full retirement; physical release is still re-observed through exact source occurrence + runtime identity and accepted only against the then-current owner/mutation. This preserves safety without holding protocol locks across teardown or inventing a half-activation lifecycle.

#### 3.3.5 Exact renderer wiring — frozen

In the fresh/non-retained branch of `applyBoundManualNavigationDestination(...)`, before either irreversible call:

```kotlin
if (!playbackAdapter.authorizeFreshDirectRetirement(
        sourceOccurrence = shadowRuntimeOccurrence,
        targetOccurrence = shadowOccurrence,
        runtimeIdentity = checkNotNull(shadowRuntimeIdentity),
    )
) return

prepareFreshTrackTransitionWithP2(active, DoPCarrierSessionReset.RECONFIGURE)
closePump("manual-navigation-fresh")
```

No bridge-derived field may be passed to this authority seam. Existing `DirectDsdTrackTransitionPolicy` may still use format/rate/geometry solely to select retained-vs-fresh mode before this claim.

`closePump(...)` continues to capture the exact closing source occurrence/runtime and call the existing typed `observeDirectRuntimeReleased(...)` after physical close. Do not move release observation earlier and do not alter endpoint-issued Direct release proof semantics.

#### 3.3.6 Frozen constraints that remain valid

These parts of the old G1-R2 intent remain authoritative:

- `ManualNavigationTransitionBridge` is correlation/diagnostic only and may not authorize physical retirement.
- stale/quarantined producer callbacks must not trigger `prepareFreshTrackTransitionWithP2(...)` or `closePump(...)`.
- a newer MANUAL mutation must supersede older callback authority.
- exact occurrence/runtime identity remains required wherever the production race demonstrates it is necessary; mediaId/periodUid alone are insufficient.
- retained-same-plan Direct transition stays on its existing `DirectRetainedHandoffPermit` path unless the audit proves that path itself contains a separate residual.
- existing endpoint-issued typed Direct release proof/receipt after physical close remains unchanged unless a separately named residual proves otherwise.
- no new timeout/executor/authority owner/state machine may be added by default.
- P2/session-owner, PCM, rebuild G0.5/G1-R1, R3 recovery, JNI/native/USBFS/RAW_DATA/FFmpeg remain out of scope unless the audit finds a concrete dependency that makes a smaller seam impossible.

#### 3.3.7 Production-real finite acceptance matrix — frozen

At least one positive test/harness path must use the same production occurrence/runtime producers that feed `DirectDsdMedia3Renderer` and protocol destination binding. A unit test that independently injects matching synthetic identity strings is not evidence for this slice; no authority String is accepted by the replacement API at all.

| ID | Sequence | Required result |
|---|---|---|
| G1R2B-T1 | legal current MANUAL DOP destination; renderer carries exact owned source occurrence, exact callback target occurrence, exact current runtime; calling adapter is current | `authorizeFreshDirectRetirement(...) == true`; existing fresh preparation and `closePump("manual-navigation-fresh")` execute |
| G1R2B-T2 | B callback arrives after C has superseded B before the claim | B target occurrence != protocol current target/application-current occurrence -> `false`; zero prepareFresh/closePump |
| G1R2B-T3 | same mediaId/period reuse but callback target `PlaybackOccurrence` differs from protocol current target occurrence | `false`; zero retirement side effect |
| G1R2B-T4 | renderer carries stale source occurrence while `RuntimeIdentity` happens to be the same retained runtime | `false`; proves source occurrence is not redundant with runtime identity |
| G1R2B-T5 | renderer carries exact occurrences but stale/wrong `RuntimeIdentity` | `false`; zero retirement side effect |
| G1R2B-T6 | exact occurrences/runtime but claim is made through a stale/wrong `UsbExclusiveShadowAdapter` | intrinsic adapter id mismatch -> `false` |
| G1R2B-T7 | destination bound but application-current occurrence is not the exact target | `false` |
| G1R2B-T8 | protocol output is `Unavailable`, or owned write-lease output target no longer equals current protocol output target | `false`; no physical retirement |
| G1R2B-T9 | legacy bridge correlation/bind succeeds while protocol claim conditions are stale/mismatched | bridge alone cannot authorize; `false`; zero prepareFresh/closePump |
| G1R2B-T10 | structure/API | renderer-facing claim has exactly `sourceOccurrence`, `targetOccurrence`, `runtimeIdentity`; no caller mutationId, targetFacts, activationId or outputTarget; adapter id comes from receiver |
| G1R2B-T11 | structure/order | successful authorization occurs before `prepareFreshTrackTransitionWithP2`; that occurs before `closePump`; typed `observeDirectRuntimeReleased` remains after physical close |
| G1R2B-T12 | structure | failed D111 `DirectFreshRetirementPermit`/fresh activation-id plumbing is removed rather than retained unused; no fresh `ActivationRecord` is introduced |
| G1R2B-T13 | retained same-plan manual transition | remains on existing `DirectRetainedHandoffPermit` activation/receipt/commit path; replacement fresh Boolean gate is not used |
| G1R2B-T14 | regression | G1-R1, G0.5, R3, P2 redemption, Direct typed-release, PCM proof and retained-handoff focused matrices remain green |

Required checks:

- focused G1R2B-T1..T14;
- production-shaped manual-navigation + raw-observation/occurrence-binding test sufficient to prove T1 and T2 without synthetic authority Strings;
- full protocol/manual-navigation/Direct focused matrices;
- G1-R1 + G0.5 rebuild regressions;
- R3 recovery-intent regressions;
- P2 redemption + PCM proof regressions;
- Debug + Perf compile;
- `git diff --check`.

#### 3.3.8 Implementation/review gate — frozen

Current checkpoint `2257b615ad0ae5ea02a6362c88c364c0f3393ac5` is **not accepted for G1-R2**. It is the failed D111 implementation checkpoint and may be used only as the repair baseline for one direct successor that removes the over-specified fresh permit shape.

Allowed production files remain exactly:

1. `app/src/main/java/com/mica/music/media/usb/protocol/UsbExclusivePlaybackProtocol.kt`
2. `app/src/main/java/com/mica/music/media/usb/shadow/UsbExclusiveShadowCoordinator.kt`
3. `app/src/main/java/com/mica/music/media/dsd/DirectDsdMedia3Renderer.kt`

Focused tests may change/add only for G1R2B-T1..T14 and required regression signature propagation. No P2/session owner, retained-handoff semantics, PCM runtime/proof, rebuild/recovery, JNI/native/USBFS/RAW_DATA/FFmpeg, timeout/executor, APK or hardware changes.

P3 implementation must remove the failed fresh permit surface rather than layering the Boolean gate beside it. In particular, if no non-test caller remains after propagation, delete `DirectFreshRetirementPermit` and its D111-only structure tests/fields instead of keeping compatibility shims.

P3 OUTBOX must state:

```text
REFERENCE_FIRST_G1_R2B_IMPLEMENTED
FRESH_RETIREMENT_MINIMAL_OWNER_GATE
NO_CALLER_MUTATION_ID
NO_FRESH_ACTIVATION_ID
NO_TARGET_FACTS_AUTHORITY
OUTPUT_TARGET_INTERNAL_ONLY
SOURCE_TARGET_RUNTIME_EXACT
MANUAL_NAVIGATION_BRIDGE_CORRELATION_ONLY
NO_RETAINED_P2_PROOF_RECOVERY_CHANGE
```

Then STOP for P4 exact-hash review. P4 must independently verify the exact direct-successor relationship, production T1 legality, stale T2/T3/T4/T5/T6 fail-closed behavior, absence of old D111 cross-layer authority fields, and regressions. Only P4 GREEN unlocks one whole-production G1 rerun. Q1/M4 remains locked until that rerun returns `M3_SOFTWARE_ONE_WRITER_GREEN`.

---

## 4. Slice Q1 — M4 physical transition qualification, fixed order

Only after G1 GREEN.

No product code changes are allowed during a physical qualification run. A failure returns to a new software diagnosis slice; do not patch during the run.

### 4.1 Hardware

Use the already qualified test phone + Fosi Audio SK02. Preserve existing exact-identity and permission/reconnect requirements. No claim of generic DAC compatibility is made from SK02.

### 4.2 Build/preconditions

- exact P3 SHA recorded before APK build;
- Debug/QA build generated from that SHA only;
- known-good local PCM plus **two known-good stereo DSF fixtures at distinct authoritative DSD rates** selected explicitly; both rates must map to capacity-proven DoP carrier geometries. If the second-rate DSF fixture is unavailable, Q1 is `BLOCKED`, not GREEN;
- USB permission state and initial SharedPcm/USB state recorded;
- transport diagnostics reset before each scenario;
- milestone logs persisted incrementally, not collected only from delayed logcat snapshot.

### 4.3 Scenario order — do not reorder

Run exactly in architecture M4 order:

1. PLAYING DOP -> DOP, exact same-plan retained-runtime case; must retire source via `SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED`
2. PLAYING DOP -> DOP, rate/geometry-changing case; must retire source via `FAMILY_RUNTIME_RELEASED`
3. PLAYING DOP -> PCM
4. ordinary Direct pause -> GAP -> resume
5. PAUSED DOP -> DOP
6. PAUSED DOP -> PCM
7. PAUSED PCM -> DOP
8. Direct seek within same source
9. manual DOP -> PCM -> DOP chain
10. automatic track transition across same-family DOP
11. rapid manual supersede A -> B -> C where B and C have distinct exact `PlaybackOccurrence` identities, the same `PlaybackFamily`, the same output geometry, and the same explicitly named non-identity format facts; stale B callbacks may not mutate C. If duplicate `mediaId` is used, record it explicitly as part of the case.

### 4.4 Per-scenario required evidence

Every scenario must record and assert:

- source/target `PlaybackOccurrence` and adapter/runtime identities;
- semantic `IntentRevision` before transition and at commit;
- source write-lease revoke/drain before conflicting successor data;
- exact A32/A34 terminal or retained proof path used;
- candidate cannot output before source retirement barrier;
- final USB IO redeems exact current P2 ACTIVE session;
- stale old renderer/sink callback produces no write/ownership mutation;
- transport errors/invalid feedback/data errors/underrun metrics remain zero unless the scenario intentionally faults them;
- final driver/interface/clock state clean on teardown.

PAUSED scenarios additionally require two separated position/frame samples showing no semantic playback progress after commit until PLAY.

Seek additionally requires position match only opens pending; target source acceptance occurs only after old carrier barrier.

### 4.5 Failure policy

Any single invariant failure makes the physical gate RED. Do not average scenarios or mark partial qualification green. Capture exact first violating sequence and return to software.

### 4.6 Q1-R1 — rebuild candidate must rebase producer provenance to the new stack

Q1 on exact `f2189e535764f8a321677d6f195ea8bf8942d240` returned the first finite physical residual:

```text
Q1_M4_PHYSICAL_RED / DIRECT_DESTINATION_STALE_PRODUCER_AFTER_REBUILD / EXACT_F2189E53 / RETURN_TO_SOFTWARE
```

Observed production sequence:

```text
old stack current topology producer = epoch 2
-> PlaybackStackSnapshot.capture() stores MediaItems carrying old ProducerTag
-> rebuild creates new stack with initial topology epoch 1
-> stageCandidateAsync calls snapshot.stageInto(candidate.exoPlayer)
-> raw Exo setMediaItems preserves old stack ProducerTag/epoch 2
-> new stack callbacks publish producer=2 while protocol current=1
-> APPLICATION_MEDIA / TIMELINE / CURRENT_PLAYER_EVENT_TIME => STALE_DROP
-> Direct destination cannot bind; Q1 scenario 1 cannot establish PLAYING DOP
```

This is a reconstruction/provenance bug, not a new authority-model gap.

Reference-first result:

- Halcyon queue restore reconstructs fresh `MediaItem`s from saved domain songs instead of carrying player-runtime generation metadata into a replacement player.
- Neri assigns a fresh stream generation to a new/reconfigured runtime and treats prior generation only as stale evidence; prior runtime generation is not inherited as the new owner's current generation.
- Mica architecture already freezes the same rule: stack creation owns the initial playback-topology epoch; cross-stack continuity is reconstruction data + semantic intent, not a surviving stack-local protocol token.

Frozen repair contract:

1. `PlaybackStackSnapshot` remains reconstruction data only. It may contain MediaItems captured from the old player, but their old `ProducerTag`/`PlaybackTopologyProducerToken` must not become producer authority in the candidate stack.
2. Candidate technical staging must re-tag every restored non-empty queue item with the **candidate stack's already-current initial topology token** before raw Exo dispatch.
3. Re-tagging must preserve the item's original non-provenance tag/source identity. Existing `PlaybackTopologyMedia3Provenance.tagForProducer(...)` / `originalTag(...)` semantics should be reused; do not nest/copy old producer authority.
4. Technical rebuild staging is **not** a canonical user topology mutation. It must not call ordinary `MicaCompositePlayer.setMediaItems(...)`, must not reserve/commit a new topology mutation, must not mint a MANUAL mutation, must not touch `ManualNavigationTransitionBridge`, and must not advance the candidate topology epoch beyond the initial epoch created with the new stack.
5. Ordinary product `set/add/remove/move/replace/select` topology mutations remain unchanged and continue to mint/commit their own next producer epoch.
6. `PlaybackStackSnapshot.stageInto(...)` / rebuild service wiring must stage technical playback properties directly on candidate Exo without publishing semantic PAUSE, while routing only queue installation through one internal candidate-composite technical-rebuild queue seam that applies the candidate current producer tag and then delegates directly to Exo.
7. No old stack `PlaybackTopologyProducerToken`, `PlaybackStackId`, topology epoch, protocol mutation id, adapter id, runtime identity, output target or authority object may be copied into the new stack as current authority.
8. Stale callbacks genuinely emitted by the old stack after publication must still fail closed by old stack/token identity; this repair only re-identifies the reconstructed candidate queue as belonging to the candidate stack.
9. No new authority/token/proof/receipt/state owner is permitted.

Minimum acceptance:

- deterministic regression reproduces old-tag epoch 2 -> new stack initial epoch 1 and proves candidate-restored items are tagged with the new stack/current epoch, not the old producer;
- candidate callback-owned timeline/application/current facts from the restored queue are accepted in the candidate initial epoch rather than `STALE_DROP`;
- old stack producer token remains rejected by the candidate stack;
- technical staging does not create a topology/MANUAL mutation or advance epoch;
- ordinary user `setMediaItems` still advances topology provenance exactly as before;
- G1R2B, G1-R1/G0.5, R3, P2 redemption, Direct typed proof/retained handoff, PCM proof regressions remain green;
- Debug + Perf compile and `git diff --check` green.

After one direct-successor implementation and P4 exact-hash GREEN, rerun Q1 from scenario 1 on the repaired exact SHA. Do not resume at scenario 2. Q2 remains locked.

### 4.7 Q1-R2 — preserve producer handle authority across Media3 masking UID domains

Q1 rerun on exact `076770d868c1019683a5f06cd000331ad381cc57` physically confirmed Q1-R1 GREEN, then returned the next first finite residual:

```text
Q1_M4_PHYSICAL_RED / UNSCOPED_DIRECT_STREAM_NOT_PROMOTED_AFTER_TIMELINE_CURRENT_PROOF / EXACT_076770D8 / RETURN_TO_SOFTWARE
```

Observed legal ordering:

```text
current candidate topology producer = 1
-> manual target DSD128 begins
-> Direct renderer onStreamChanged gets exact external occurrence Oext
-> assigned stream carries no accepted producer into observeRawStream; stream is quarantined unscoped
-> later timeline/application/EventTime prove Oext current under producer 1
-> no second renderer stream callback occurs
-> destination never binds; source remains buffering
```

The repair MUST NOT restore any historical `scopeUnscopedRawStreams` / period-uniqueness promotion. P4 previously proved that heuristic unsafe: a delayed old renderer callback from epoch E whose old period mapping never arrived can be incorrectly promoted into E+1 after E+1 publishes the same period identity. Missing old evidence cannot prove new producer provenance.

Reference/framework-first finding from the exact local Media3 `1.9.0` AAR used by this project:

- `BaseRenderer.replaceStream(...)` stores the assigned `SampleStream` before invoking renderer `onStreamChanged(...)`; therefore `getStream()` at the callback is the exact assigned stream.
- `MaskingMediaSource` maps the top-level/external `MediaPeriodId.periodUid` to an internal child `periodUid` before invoking the child `MediaSource.createPeriod(...)`, then maps child IDs back outward for player/renderer-facing events.
- Mica captures `StreamProducerHandle.occurrence` inside `PlaybackTopologyMediaSource.createPeriod(...)`, therefore that stored occurrence may be in the child/internal UID domain.
- Direct/PCM renderer callbacks derive their `PlaybackOccurrence` from renderer-facing `MediaPeriodId`, therefore that occurrence is in the external UID domain.
- The current adapter accepts a supplied handle only when `handle.occurrence == rendererOccurrence`. That is an invalid cross-domain equality requirement and can discard a genuine producer-owned handle even though the handle is physically attached to the exact assigned `SampleStream`.

Frozen repair contract:

1. Keep `StreamProducerHandle` as the sole renderer-side producer-attribution carrier. Do not derive a producer epoch from timeline uniqueness, mediaId, periodUid, occurrence uniqueness, callback order, or `unscopedRawStreams`.
2. A supplied producer handle is usable only when:
   - `handle.stackId == current protocol stackId`; and
   - `streamProducerHandles.redeem(handle.periodInstanceId) == handle` at the adapter observation boundary.
   This exact active registry redemption proves issuer/liveness and prevents a released/delayed handle from becoming current authority.
3. Do **not** require `handle.occurrence.periodUid == rendererOccurrence.periodUid`. The handle occurrence was captured in the child/internal Media3 UID domain and renderer occurrence can be external/masked.
4. The renderer-facing `PlaybackOccurrence` remains the occurrence used to key/store the scoped raw stream and to bind candidate/manual destination/currentness. Do not replace it with `handle.occurrence`.
5. `windowSequenceNumber` equality may be retained only as a supplementary invariant if production-shaped tests prove Media3 `copyWithPeriodUid(...)` preserves it for this assignment. It is not a substitute for exact active handle redemption.
6. A null/missing handle remains `INSUFFICIENT_EVIDENCE` / unscoped fail-closed. This repair MUST NOT add later promotion of producer-less streams.
7. A released handle, wrong-stack handle, wrong/unredeemable `periodInstanceId`, or otherwise non-current registry handle must remain rejected even when mediaId/periodUid/external occurrence happen to match.
8. Do not change producer-token minting, topology epoch ownership, application/timeline/EventTime provenance, manual navigation ownership, Direct/PCM protocol authority, P2 redemption, rebuild generation, recovery, typed physical proof, or retained/fresh retirement semantics.
9. No new authority token, identity type, generation, receipt, proof, owner, compatibility String, or metadata-derived authority may be introduced.

Mandatory implement-before-claim proof:

- First reproduce in a deterministic production-shaped test an **active exact handle** whose captured child/internal occurrence has a different period UID from the renderer/external occurrence, while the same assigned stream/period instance is being observed. The pre-fix adapter condition must reject this shape; the repaired adapter must accept the producer token through exact registry redemption and scope the renderer's external occurrence to the correct current epoch.
- Also prove released stale handle -> rejected; wrong-stack handle -> rejected; null handle -> remains unscoped/not promoted; same external occurrence reused by a later producer does not let the old released handle bind the successor.
- If the production-shaped test instead proves the renderer receives no `StreamProducerHandleSampleStream` at all, STOP and report `STREAM_HANDLE_CARRIER_MISSING_BEFORE_ADAPTER`; do not remove the occurrence comparison speculatively. The next repair would then be at the Media3 carrier seam, not the reducer.

Minimum regressions:

- original R1 exact-handle/reuse/stale tests;
- Q1-R1 rebuild producer rebase;
- G1-R2B fresh retirement gate;
- G1-R1/G0.5 rebuild ordering;
- R3 live semantic intent;
- P2 redemption;
- Direct typed release + retained handoff;
- PCM physical proof;
- Debug + Perf compile;
- `git diff --check`.

After one direct-successor implementation and P4 exact-hash GREEN, restart Q1 from scenario 1. Do not resume at scenario 2. Q2 remains locked.

### 4.8 Q1-R3 — separate USB session bootstrap from usable-output/data authority

Q1 rerun on exact `766f13584210e313eb412c2266b732760785e94e` physically confirmed Q1-R1 again, then returned the next first finite residual before Scenario 1 could legally start:

```text
Q1_M4_PHYSICAL_RED / P2_REDEMPTION_RESERVED_BUT_PROTOCOL_OUTPUT_UNAVAILABLE_BOOTSTRAP_DEADLOCK / EXACT_766F1358 / RETURN_TO_SOFTWARE
```

Observed cycle:

```text
USB-request candidate stack starts OutputTarget.Unavailable
-> TransitionAwarePcmAudioSink reserves exact P2 redemption generation G
-> generation publication correctly keeps protocol Unavailable (G != usable output)
-> protocol preparePcmConfigure rejects Unavailable
-> wrapped DefaultAudioSink.configure never runs
-> AudioOutputProvider cannot open/claim the reserved session
-> P2 cannot publish ACTIVE + attached + permission + claimed + exclusive + signalExact facts
-> coordinator therefore cannot publish UsbBound(G)
-> PCM configure permit remains permanently deferred
```

This is not permission to undo A16. `UsbOutputGeneration`/reservation identity still proves only which older hardware authority is stale and which exact P2 request may attempt establishment. It is **not** usable playback output and must not be published as `UsbBound` before ACTIVE/exclusive/exact facts.

Framework/reference-first findings:

- Exact local Media3 `1.9.0` bytecode shows `DefaultAudioSink.configure(...)` computes/stores configuration but does **not** call `AudioOutputProvider.getAudioOutput(...)`.
- `DefaultAudioSink.handleBuffer(...)` lazily calls `initializeAudioOutput()` / `getAudioOutput(...)` before its `ByteBuffer.hasRemaining()` check. Therefore an empty little-endian buffer can initialize the exact provider/session without submitting PCM payload bytes.
- The same bytecode initializes `startMediaTimeUs` from that `handleBuffer` call before the empty-buffer return; a bootstrap probe must therefore use the real first input buffer's `presentationTimeUs`, never a fabricated zero timestamp.
- Fresh local reference audit (2026-08-18) confirms the same lifecycle separation in all relevant checked references:
  - **Neri (closest USB-exclusive production reference):** `UsbExclusiveSessionController.openPlayerPcm(...)` performs the real permitted-device open, native handle creation, USB format/claim/rate preparation and `nativePreparePlayerPcm(...)`, then publishes state with `opened=true` and `streaming=false`. `UsbExclusiveAudioSink.startNativeTransportIfReady(...)` invokes `playPlayerPcm(...)` only later, after playback intent and queued/preroll conditions are satisfied. Neri native `nativeOpen(...)` also explicitly returns the streaming interface to alt 0 (`open_ready`) before returning the handle. This strongly supports "session established/prepared" as distinct from "streaming/data authority". This is design evidence only; no GPL implementation is copied.
  - **sylvakru-usb (direct USBFS prototype reference):** native `UsbExclusiveNative_open(...)` duplicates the fd, claims the interface when needed, selects the streaming alt and arms feedback independently of payload submission; PCM payload enters only through the later `UsbExclusiveNative_writePcm(...)` call. It therefore also does not require a prior payload-write authority in order to establish the USB session/resources.
  - **Halcyon (AudioSink lifecycle analogue, not a USBFS/session-owner authority reference):** `OboeAudioSink.configure(...)` opens the output, `play()` separately starts it, and `handleBuffer(...)` performs payload writes. It supports the generic open/configure-vs-start/write split but is weaker evidence than Neri/sylvakru for Mica's USB-host authority model.
- Mica's Direct DSD `sessionFactory.open(...)` is different from PCM: it synchronously consumes the exact P2 redemption binding, claims/configures the USB device, creates a dormant exact Native session, and returns only after P2 can publish ACTIVE facts. It emits no CONTENT/GAP/PREFILL write during that open.

Frozen repair contract:

1. Preserve A16 exactly: generation/reservation publication keeps protocol output `Unavailable`; only current matching `PlaybackOutputFacts` with ACTIVE + attached + granted + claimed + exclusive + signalExact may publish `UsbBound(G)`.
2. Preserve ordinary `preparePcmConfigure(...)` / `prepareDirectStage(...)` behavior. `Unavailable` remains rejected on ordinary activation/data paths.
3. Add only explicit **bootstrap prepare** entries for the first USB session establishment. Reuse existing `PcmConfigurePermit`, `DirectStagePermit`, `ActivationId`, `UsbOutputRedemptionBinding`, and `OutputTarget.UsbBound(G)` identities; do not add a new token/generation/proof/owner/authority plane.
4. A bootstrap prepare may target `UsbBound(G)` while the protocol itself is still `Unavailable` only when all normal mutation/destination/application-current/adapter/lifecycle/conflict checks are exact **and**:
   - the caller's `UsbP2RedemptionContext` already owns the exact current P2 redemption binding whose target is `UsbBound(G)`;
   - the coordinator's latest observed P2 generation is exactly `G`;
   - protocol output is still `Unavailable` for that same establishment attempt.
   The expected `UsbBound(G)` is the **required post-open commit target**, not current availability.
5. Before every bootstrap physical side effect, revalidate both planes: the protocol activation/mutation is still exact and `UsbP2RedemptionContext.ensurePermitTarget(...)` still redeems the same live owner binding. A superseded generation/mutation/adapter/stack fails before new hardware work.

PCM bootstrap sequence is fixed as:

```text
reserve exact P2 binding G
-> bootstrap PcmConfigurePermit(expected target = UsbBound(G))
-> technically suppress wrapped sink PLAY
-> wrapped DefaultAudioSink.configure(real format)
-> retain the exact configure permit uncommitted
-> on first real handleBuffer(B, pts): redeem bootstrap permit + exact P2 binding
-> call wrapped DefaultAudioSink.handleBuffer(EMPTY_LITTLE_ENDIAN, pts, 0)
-> Media3 lazy initializeAudioOutput/getAudioOutput consumes binding G
-> P2 publishes REQUESTED/OPENING then exact ACTIVE facts
-> coordinator changes protocol Unavailable -> UsbBound(G)
-> empty probe returns without provider PCM write / without consuming B
-> commit the existing PcmConfigurePermit
-> only exact current UsbBound(G) commit may create PcmOwned + ActiveWriteLease
-> restore wrapped sink PLAY only when commit disposition reflects latest semantic PLAY
-> return false so Media3 retries the untouched real buffer B
-> retry uses normal tryEnterWrite + withProtocolWrite + provider requireProtocolWrite
```

6. The empty initialization probe must be a zero-capacity/direct or otherwise stable `ByteBuffer` with `ByteOrder.LITTLE_ENDIAN`; it must carry the real first buffer `presentationTimeUs`, encoded-access-unit count `0`, and must never be treated as content acceptance.
7. While a PCM bootstrap configure is pending, `TransitionAwarePcmAudioSink.play()` must not let the wrapped `DefaultAudioSink` enter playing state. Bootstrap entry must technically quiesce the wrapped sink so `initializeAudioOutput()` cannot auto-start the new USB output before protocol commit. After commit, latest semantic intent wins: CurrentPlaying may restore `super.play()`, CurrentPaused stays paused.
8. If lazy initialization returns retry/not-ready, retain the exact pending bootstrap and return false; no family ownership and no data write exist yet.
9. If configure succeeded but bootstrap is later superseded/reset/flushed/released before commit, the exact pending activation must be failed/cancelled through the existing receipt/cleanup machinery and wrapped delegate cleanup must complete before the activation is forgotten. Do not leak an uncommitted configured sink or pending activation.
10. If ACTIVE facts do not arrive for exact G, arrive stale, or a newer generation wins before commit, the configure receipt cannot become current ownership; cleanup/fail-closed handling runs and zero PCM bytes reach USB.
11. The provider's existing final boundary remains unchanged as the data fence: a non-empty `UsbSk02AudioOutput.write(...)` still requires current active P2 session plus `redemptionContext.requireProtocolWrite(..., PCM_DATA)`. Bootstrap never grants a write scope.

Direct bootstrap sequence is fixed as:

```text
reserve exact P2 binding G
-> bootstrap DirectStage.CREATE_RUNTIME permit(expected target = UsbBound(G))
-> redeem exact protocol activation + exact P2 binding
-> sessionFactory.open(...)
-> P2 request lease owns USB claim/clock/alt/native dormant creation
-> no Direct CONTENT/GAP/PREFILL write occurs during open
-> P2 publishes exact ACTIVE facts before open returns
-> coordinator changes protocol Unavailable -> UsbBound(G)
-> commit CREATE_RUNTIME against exact current UsbBound(G)
-> only then may ordinary PREFILL/ARM/SOURCE_ACCEPT stages run
```

12. Bootstrap applies only to Direct `CREATE_RUNTIME`. PREFILL, ARM, SOURCE_ACCEPT, retained handoff, fresh retirement and all real Direct writes remain ordinary usable-output paths and continue to require exact current `UsbBound` + their existing typed permits/write leases.
13. Do not solve PCM by allowing the first real buffer to pass through unleased and relying on the provider to throw/block later. The bootstrap call must be explicitly zero-payload so no correctness depends on a failed real write.
14. Do not solve either family by copying reservation generation into protocol current output, fabricating ACTIVE facts, weakening `canPrepareLocked()` globally, or treating diagnostic/request metadata as availability.

Q1-R3 acceptance follows the §0.2 test ladder rather than another broad-test-only cycle.

Focused invariant tests must prove at minimum:

- ordinary `Unavailable -> preparePcmConfigure` remains denied;
- exact current bootstrap G may prepare, wrong/stale G may not;
- bootstrap permit with mutation/current/adapter superseded before open cannot touch provider/session;
- commit before exact ACTIVE G cannot create `PcmOwned`/`DopOwned` or a write lease;
- exact ACTIVE G followed by commit creates ownership whose lease target is exactly `UsbBound(G)`;
- G+1 arriving before commit makes G fail closed and cleanup; no stale ownership/write;
- PLAY->PAUSE during PCM bootstrap leaves the initialized output paused and commits latest paused semantics; no early `AudioOutput.play()`;
- reset/flush/release with an uncommitted configured PCM bootstrap leaves no pending activation/resource;
- Direct bootstrap is CREATE-only; no PREFILL/ARM/SOURCE_ACCEPT before exact ACTIVE G.

Mandatory production-shaped software-flow integration before hardware rerun:

1. Use the real Media3 `DefaultAudioSink` `1.9.0` with a deterministic fake `AudioOutputProvider`/`AudioOutput`, not a hand-written approximation of lazy initialization. Prove:
   - `configure()` performs zero `getAudioOutput` calls;
   - the zero-length little-endian first-buffer probe performs exactly one lazy initialization;
   - that probe performs zero payload writes and does not advance the real input buffer;
   - the probe uses the real first `presentationTimeUs`.
2. Compose real `TransitionAwarePcmAudioSink` + real `DefaultAudioSink` + real `UsbP2RedemptionContext`/`UsbOutputSessionOwner` + real shadow/protocol with only the final USB device/native output faked. Drive the full sequence `Unavailable -> reserve G -> bootstrap configure -> zero-byte lazy open -> ACTIVE G -> protocol commit -> normal leased PCM write` and assert every intermediate algebraic state.
3. Add stale/supersede variants: mutation change before lazy open, P2 generation G+1 before lazy open, G+1 between ACTIVE/open and protocol commit, and observer/currentness failure. All must produce zero stale data writes.
4. Add the Direct sibling flow using the real Direct renderer/session-factory seam with a fake final transport/P2 session: `Unavailable -> reserve G -> bootstrap CREATE -> ACTIVE G -> CREATE commit -> PREFILL eligible`; stale G and superseded mutation fail before physical/open or before later stage authority as appropriate.
5. Preserve permanent regressions for Q1-R1 rebuilt-producer rebase and Q1-R2 masking-UID active-handle redemption in the same software-flow checkpoint so the three setup failures cannot regress independently.

Broad USB regression is a major-checkpoint gate after these focused + software-flow tests are green, not the primary evidence. Only then run P4 exact-hash review and restart Q1 from Scenario 1 on one exact successor SHA. Q2 remains locked.

### Q1-R3a — P4 D100 post-open supersede cleanup residual

P4 exact-hash review of `c631e6e782f921cc15770edd29a72a50062e714a` found one finite lifecycle residual without reopening the Q1-R3 authority design:

- after the real zero-payload `DefaultAudioSink.handleBuffer(EMPTY_LITTLE_ENDIAN, realPts, 0)` has returned, P2 `consumeRedemption(...)` has already synchronously either (a) failed/released a superseded open, or (b) published the opened exact session as ACTIVE before returning it;
- therefore a post-probe protocol target mismatch against the redeemed permit's exact `UsbBound(G)` is not an ordinary "ACTIVE may arrive later" retry state. It is terminal stale-bootstrap evidence and must trigger immediate existing bootstrap failure + delegate/session cleanup;
- the current combined branch `if (!probeAccepted || snapshot.outputTarget != redeemed.outputTarget) return false` incorrectly retains both cases. `probeAccepted == false` with the exact target still current may remain a retry/not-ready case; **target mismatch after the probe must not be retained**;
- fix only by splitting the post-probe decisions and routing target mismatch through the existing `cancelPendingBootstrap(...)`/`failPcmConfigure` + delegate reset/release + `completeCleanup` machinery. Do not add a new cleanup token, owner callback, P2 release API, or output-generation rule;
- ordering matters: target mismatch is terminal even if `probeAccepted == false`; check/cleanup the mismatch before retaining the `!probeAccepted` retry case.

Required regression must stop after the first stale call: inject G+1 after ACTIVE G but before protocol commit, call the real first PCM `handleBuffer` exactly once, and immediately assert zero payload/ownership **plus** delegate AudioOutput/session release and activation cleanup. The test must not rely on a second `handleBuffer`, `flush`, `reset`, or `release` to trigger cleanup. Also preserve a focused retry case proving a non-accepted probe with the exact target still current does not spuriously mint ownership or write data.

Q1-R3a may change only `TransitionAwarePcmAudioSink.kt` plus the narrow PCM bootstrap software-flow test(s), unless compilation proves a directly necessary test seam. P2 owner/provider/protocol/shadow/Direct/native/provenance/rebuild semantics are frozen for this residual.

---

## 5. Slice Q2 — M5 detach/reconnect/recovery/fallback physical integration

Only after Q1 GREEN.

Run these scenarios in order. Existing P3.5 historical evidence may inform setup but does not substitute for fresh evidence against the repaired stack.

1. PLAYING UsbDirectPcm physical detach -> SharedPcm fallback -> reattach -> permission reacquisition if required -> exact stable-identity reproof by the **existing frozen P2/device-identity owner and typed proof path** -> USB rebuild. Device label/name, diagnostic strings, route descriptions, media metadata, or independently reconstructed identity strings are not reproof authority.
2. PAUSED UsbDirectPcm same detach/reconnect sequence; remains paused throughout unless user issues PLAY.
3. PLAYING USB detach, then user PAUSE while recovery/backoff is in progress; final restored stack remains PAUSE and recovery activation does not require frame progress after latest PAUSE.
4. PAUSED detach, then user PLAY during recovery; recovery requires current PLAY frame-progress proof before success and final rebuilt stack plays.
5. PLAY -> PAUSE -> PLAY during one recovery window; latest revision wins without minting a peer recovery epoch.
6. permission denied once -> recovery attempt fails/backoffs -> later attach/grant produces a new exact proof; stale grant/action cannot ACK current recovery.
7. detach while source side effect/cleanup is in flight; no conflicting USB activation before terminal cleanup.
8. recovery budget exhaustion -> explicit SharedPcm fallback; no hidden USB downgrade and no USB lease redemption from SharedPcm.
9. service/process restart baseline remains fail-safe PAUSE unless product policy explicitly says otherwise; no in-memory PLAY revision is resurrected.

The former physical case "R1 retirement deliberately delayed while R2 recovery rebuild is requested" is **not** a mandatory Q2 hardware case. The audited baseline has no behavior-neutral production retirement-delay/hang seam. Do not add one solely for qualification. Keep the equivalent `R1 blocked -> R2 mints -> R1 cannot publish` case as a mandatory D106/G1 deterministic software concurrency regression.

For scenarios 3–5, capture ledger revisions and the semantic intent passed to each recovery evaluation. This is the physical acceptance of A22/A23.

---

## 6. Gate G2 — M6 compatibility-authority removal planning

M6 is not automatically authorized by Q2 GREEN. First perform a whole-repo caller audit and write a deletion map.

The G2 audit itself is reference-first under §0.1: prefer deleting duplicated metadata-derived authority in favor of the already-accepted owner/token/typed-proof seam. Do not preserve a compatibility wrapper merely because it can reconstruct the same information. If a wrapper survives, the deletion map must name the concrete race/consumer that still requires it.

Delete/de-authorize only symbols proven to have no production authority callers. Candidate categories:

- remaining ManualNavigation authority wrappers;
- remaining Direct track-transition authority wrappers;
- obsolete global seek-authority helpers superseded by protocol-local causal handle;
- generic string proof/receipt compatibility shapes with no production consumer;
- duplicated recovery resume Boolean authority paths (metadata storage may remain if still needed for reconstruction/UI).

M6 implementation must be split into deletion-only or call-site-narrowing commits; no behavior feature may be added in the same commit.

After each deletion slice:

- full focused R1/A10/PCM/Direct/rebuild/recovery matrices;
- Debug+Perf compile;
- `git diff --check`;
- one P4 read-only authority audit.

No hardware rerun is required for a genuinely behavior-preserving deletion slice unless the diff touches runtime/adapter/USB execution paths.

---

## 7. Dependency chain — frozen

```text
D106 / 8494b020
  -> P4 D91 core-green + named residuals
    -> G0.5 overlap-watchdog + null-proof closure
      -> P4 G0.5 exact-hash review
        -> P6 final plan-precision audit = PLAN_GREEN
          -> R3 A22+A23 software closure
            -> P4 R3 exact-hash review
              -> G1 whole-production one-writer read-only audit (D18 RED)
                -> G1-R1 async Direct pre-invalidation repair
                  -> P4 G1-R1 exact-hash review = GREEN
                    -> G1 whole-production one-writer rerun D19 = RED
                      -> G1-R2 fresh Direct manual-navigation authority repair attempt D111 / 2257b615
                        -> P4 D96 = RED (`FRESH_RETIREMENT_TARGET_FACTS_IDENTITY_DOMAIN_MISMATCH`)
                          -> reference-first G1-R2 authority-minimization/design audit [CURRENT]
                            -> replacement bounded G1-R2 implementation slice
                              -> P4 exact-hash review
                                -> G1 whole-production rerun = GREEN required
                                  -> Q1 M4 physical transition qualification
                                    -> Q2 M5 detach/reconnect/recovery/fallback qualification
                                      -> G2 M6 deletion-map audit
                                        -> bounded M6 cleanup slices
```

No later node may start early.

---

## 8. What P3 is never allowed to decide

For avoidance of doubt, future P3 directives must not ask P3 to choose any of the following:

- thread/executor for rebuild;
- timeout value or timeout source;
- whether `ExoPlayer.release()` may run off main;
- whether timeout counts as retirement proof;
- source of semantic recovery intent;
- whether interrupted resume Boolean is authority or metadata;
- whether frame progress is required under PLAY/PAUSE;
- whether a new recovery epoch is minted on semantic intent change;
- whether recovery directly calls play/pause;
- whether P2 generation alone implies usable USB output;
- whether terminal PCM/Direct proof can be replaced by close-return/log/string/Boolean;
- whether stale cleanup may act on the current resource instead of exact captured resource;
- physical scenario order or pass criteria;
- whether a failed scenario can be waived because another scenario passed.

All of those choices are frozen above. P3's role is implementation and evidence only.

---

## 9. Reference-first plan audit — 2026-08-18 after P4 D96

This audit was performed after introducing §0.1 and before authorizing any replacement G1-R2 implementation.

### 9.1 Existing accepted slices

- **G0.5 / G1-R1:** mechanism is consistent with the reference-first rule. Generation/currentness is minted and checked by one rebuild owner, blocking main/native work is moved outside the generation critical section, and late callbacks are fenced by owner-held generation. No new metadata identity is required. Do not reopen absent a concrete production residual.
- **R3 A22/A23:** the live `PlaybackIntentLedger` is a single semantic owner. `IntentRevision` is stronger than the reference projects because Mica must distinguish PLAY/PAUSE changes during one recovery action; that stronger dimension is tied to the named recovery race and is not reconstructed from metadata. No plan change required.
- **PCM/Direct typed terminal proof and P2 redemption:** stronger than the references but justified by physical USB/runtime teardown and exact active-session redemption. Human-readable logs/strings remain non-authoritative. No plan change required from this audit.

### 9.2 G1-R2 audit result — old D111 shape is over-specified

Production inspection of `2257b615` shows that `DirectFreshRetirementPermit` is consumed by the renderer only as a nullable gate. After `prepareFreshDirectRetirement(...)` returns non-null, none of the permit's copied fields are read before the existing synchronous `prepareFreshTrackTransitionWithP2(...) -> closePump(...)` sequence.

The current permit therefore duplicates owner state without adding a later redemption boundary. In particular:

- `targetFacts: String` is invalid as authority because protocol destination binding and legacy bridge correlation have different producers/domains; P4 D96 proved the legal path cannot match them.
- `activationId` is minted for this permit but the fresh-retirement claim does not register a corresponding activation record and the renderer does not redeem/commit the permit by activation id. It therefore adds identity surface without providing a currentness fence.
- `outputTarget` remains required **inside the protocol owner** at the fresh-retirement claim linearization point: the owned Direct runtime/write lease must still belong to the protocol's current usable output target, and `Unavailable` must fail closed. However, the replacement fresh grant must **not copy or return `outputTarget` to the renderer**. Fresh retirement closes an already-owned old runtime; it does not itself authorize successor USB writes. Successor CREATE/PREFILL/ARM/SOURCE_ACCEPT continues to obtain and redeem the current P2/`OutputTarget.UsbBound(generation)` authority through the existing Direct-stage path. Therefore `outputTarget` is an internal owner/currentness check here, not a cross-layer grant identity.
- exact source/target `PlaybackOccurrence`, destination `AdapterInstanceId`, and `RuntimeIdentity` remain the current **candidate minimal claim identities** because they map to concrete duplicate-mediaId/period-reuse, renderer/adapter-churn, and stale physical-runtime-retirement races. Caller-supplied `mutationId` has been separately rejected below because the D111 caller merely re-read the protocol owner's current mutation at claim time rather than carrying an originating causal token.

Preferred redesign direction, not yet an implementation directive:

```text
renderer supplies exact source occurrence + target occurrence + runtime identity
adapter identity is intrinsic to the calling protocol adapter
protocol owner resolves/validates current MANUAL mutation internally
protocol validates application-current target + owned source/runtime + current usable output internally
-> return one opaque one-shot grant / success gate
```

No bridge-generated format identity participates in authority. Format/rate/geometry may still decide **which transition mode** is needed, but that is compatibility/policy input, not proof that the callback owns the right to retire a physical runtime.

The explicit caller-supplied `mutationId` question is now resolved for the fresh-retirement redesign: **do not require the renderer/caller to pass `mutationId`.** In D111 the shadow adapter obtains `protocol.snapshot().mutation?.mutationId` at claim time and immediately passes that owner-derived current id back into `protocol.prepareFreshDirectRetirement(...)`. This is not a callback-carried causal token and does not prove which historical MANUAL mutation produced the renderer callback. It therefore adds no independent stale fence beyond the protocol owner's own current-mutation check.

The replacement claim should resolve the current MANUAL mutation inside the synchronized protocol owner and validate the renderer-supplied exact source occurrence, target occurrence and runtime identity plus the intrinsic calling adapter identity against that current mutation/ownership/application-current state. If a future design wants a mutation token to provide additional causal fencing, that token must be captured before/at the originating manual dispatch and carried unchanged with the callback; a mutation id freshly re-read from protocol state at claim time does not qualify.

The fresh-retirement redesign also **does not retain `activationId`**. The D111 fresh permit minted an activation id but did not register a matching activation record, did not cross an asynchronous side-effect boundary that later redeemed that id, and did not commit/cleanup the fresh retirement by activation id; production consumed it only as logging/permit metadata before a synchronous `prepareFreshTrackTransitionWithP2(...) -> closePump(...)` sequence. That is not a real activation lifecycle. Do not preserve or recreate a half-formed activation token solely for symmetry with retained/direct-stage paths.

A future fresh-retirement design may reintroduce `activationId` only if a concrete race requires `claim -> asynchronous/late side effect -> receipt/redemption -> commit/cleanup` fencing. In that case the plan must add the complete owner-registered activation lifecycle and tie the token to the named race; an unused/generated-only id is forbidden.

This conclusion does **not** automatically reopen accepted retained-handoff behavior. The retained path has a separately registered activation/receipt redemption lifecycle and remains frozen unless a concrete residual demonstrates that its caller-supplied mutation-id shape causes an observable authority gap.

### 9.3 Remaining-plan audit

- **Q1/M4:** no new software authority is introduced. Scenario 11 already calls format facts `non-identity` and requires exact occurrence/adapter/runtime evidence; this is consistent with §0.1. Keep it that way.
- **Q2/M5:** the phrase `stable-identity reproof` was under-specified. §5 now fixes its authority source to the existing frozen P2/device-identity owner and typed proof path, explicitly excluding labels/diagnostic strings/reconstructed metadata.
- **G2/M6:** direction is correct because it deletes compatibility authority. §6 now explicitly applies the reference-first rule and requires a concrete surviving race/consumer for any wrapper that is retained.

### 9.4 Current gate

Plan audit result:

```text
REFERENCE_FIRST_PLAN_AUDIT_COMPLETE
G1_R2_OLD_AUTHORITY_SHAPE_REJECTED
TARGET_FACTS_STRING_NOT_AUTHORITY
OPAQUE_OWNER_CLAIM_DIRECTION_PREFERRED
Q1_Q2_G2_NO_NEW_CROSS_LAYER_STRING_AUTHORITY
Q1_M4_REMAINS_LOCKED
```

This is a design-audit result only. It does **not** authorize P3 implementation yet. The next coordinator action must freeze the exact replacement G1-R2 claim API/inputs and a production-real acceptance matrix from the findings above, then issue a new P3 directive.
