# USB Exclusive Audio M1–M6 Assumption Audit

> Status: `ASSUMPTION_AUDIT_CLOSED / A01_A37_CONSOLIDATED / REPAIR_PROGRAM_AUTHORIZED`
> Date: 2026-08-16
> Scope: hidden ordering, provenance, callback-existence, lifecycle-completion, output-availability and recovery assumptions across the frozen playback protocol and M2–M6 adapters/integration.
> Architecture core: `docs/USB_EXCLUSIVE_AUDIO_ARCHITECTURE.md` `FROZEN_V1` authority/ownership algebra remains the baseline. P4 directive69, P5 directive30 and P6 directive04 found eight additional implementation/provenance assumptions (A30–A37) but no contradiction requiring a new authority plane or replacement of the M1 ownership/lease/receipt algebra. Coordinator folded those gaps into addendum V2; P4 directive70, P5 directive31 and P6 directive06 independently accepted the final target contracts. The audit is closed and implementation repair may proceed from the preserved committed baseline.

## 1. Why this audit exists

The first real M3 device runs disproved an implicit adapter assumption: `RENDERER_STREAM` can arrive roughly 50–80 ms before `TIMELINE_PERIOD`. The architecture correctly required `mediaId`, `periodUid`, `PlaybackOccurrence` and `AdapterInstanceId` to match before destination binding, but neither the document nor the M2 adapter required the join to converge for every legal arrival order. Tests mostly exercised `TIMELINE_PERIOD -> RENDERER_STREAM`, while the device repeatedly produced the opposite order.

The review therefore changes the completeness question from only:

- "Is the reducer total for the events already in the model?"

to both:

- "Are all externally supplied facts represented?"
- "Can every multi-observation authority join converge regardless of legal arrival order, repetition, omission and supersede?"
- "Does every proof come from the physical/runtime layer it claims to prove?"
- "Does every lifecycle transition distinguish identity/version change from actual resource availability/completion?"

## 2. Classification

- `PROVEN`: supported by implementation ordering plus deterministic/physical evidence or authoritative platform contract.
- `CONTAINED`: assumption exists but a later exact fence currently prevents it from becoming authority; document/hardening still required.
- `UNPROVEN`: code/plan relies on a behavior that has not been established and lacks a fail-closed alternative.
- `CONTRADICTED`: real device or source inspection shows the assumption is false.
- `CONFORMANCE_BUG`: implementation contradicts an already-frozen architecture rule without requiring a new authority model.

## 3. Audit matrix

| ID | Phase | Hidden assumption | Evidence / current behavior | Classification | Required correction / gate |
|---|---|---|---|---|---|
| A01 | M2/M3 | `TIMELINE_PERIOD` arrives before `RENDERER_STREAM`, or a later stream callback will retry binding | `bindManualDestination()` is attempted only from `observeRawStream()`. `observeTimelinePeriod()` only records the map. Device repeatedly shows STREAM ~50–80 ms before PERIOD; same-item reselect may not emit another stream callback. | `CONTRADICTED / BLOCKER` | Every join operand stores immutable facts and recomputes join closure. `STREAM->PERIOD` and `PERIOD->STREAM` must converge to identical final state and bind exactly once. |
| A02 | M2/M3 | One "latest stream" per adapter is enough for unresolved observations | `latestRawStreams[adapterId]` overwrites older unresolved B if the same renderer reads C before B's timeline identity arrives. | `UNPROVEN / HIGH` | Store unresolved stream facts by exact `(AdapterInstanceId, PlaybackOccurrence)` with bounded lifecycle/supersede cleanup; test B/C read-ahead permutations. |
| A03 | M2/M3 | A `mediaId` maps to at most one relevant period | `observeApplicationMedia()` reverse-searches `periodToMediaId.entries.firstOrNull { value == mediaId }`. Duplicate queue entries can share one mediaId. | `CONFORMANCE_BUG / HIGH` | Never infer period/occurrence from mediaId alone. Carry exact timeline/window/period provenance and join independently. |
| A04 | M2/M3 | `targetMediaId` alone identifies an indexed/manual destination | Architecture includes `ExpectedTargetPeriodUid?`; legacy path can compute it, protocol `beginManualNavigation()` currently drops it. Same-mediaId different queue entries can also be classified as same-item seek. | `CONFORMANCE_BUG / HIGH` | Propagate exact expected period/window/index evidence where known; mediaId alone must not establish same logical queue occurrence. |
| A05 | M2/M3 | `periodUid -> mediaId` mappings can live forever without timeline versioning | `periodToMediaId` is unversioned and accumulative. Queue/timeline replacement can leave stale joins. | `UNPROVEN / HIGH` | Scope mapping to a timeline revision/identity and replace/retire stale entries deterministically. |
| A06 | M2/M3 | Analytics `EventTime.currentMediaPeriodId` and `player.currentMediaItem` are the same logical instant | Current listener extracts occurrence from each EventTime but pairs it with global `player.currentMediaItem?.mediaId`. | `UNPROVEN / HIGH` | Preserve EventTime provenance: derive/validate media identity from the same EventTime timeline/window or leave occurrence independent until joined. |
| A07 | M2/M3 | If an Analytics batch contains null/different current periods, a later unanimous batch will always arrive | Current listener drops occurrence when batch EventTimes disagree/null. Progress may then rely on an unspecified future callback. | `UNPROVEN / HIGH` | Store event-time facts independently; define deterministic selection/reconciliation and explicit fail-closed timeout/diagnostic instead of callback recurrence. |
| A08 | M2/M3 | Supported local tracks always resolve to one non-placeholder period quickly | `ManualNavigationTimelinePeriodResolver` returns null for multi-period/placeholder windows. | `UNPROVEN / MEDIUM` | Make support boundary explicit; retry on later timeline facts and diagnose persistent unsupported mappings. |
| A09 | M2/M3 | Re-select/re-dispatch will produce another renderer `onStreamChanged` if identity was incomplete | Real run did not retrigger it. Media3 only promises stream change on enable/replacement, not arbitrary app reselection. | `CONTRADICTED` | Never require callback recurrence for join completion; retain facts and replay closure from any later operand. |
| A10 | M3/M5 | Captured `resumePlayback` remains authoritative across technical flush | `flushPlaybackPipeline(positionMs, resumePlayback)` restores the captured boolean directly after stop/seek/prepare. FROZEN_V1 §8.1 requires latest-ledger refence. | `CONFORMANCE_BUG / HIGH` | Technical quiesce captures only an `IntentRevision` fence; immediately before restore re-read ledger and latest intent wins. |
| A11 | M3 | `playExoDirect()/pauseExoDirect()` can safely publish semantic intent forever | M2 explicitly classified them as technical controls; M3 now publishes protocol intent. Current callers appear semantic, but future technical reuse would corrupt ledger. | `UNPROVEN / MEDIUM` | Rename/restrict as semantic command helpers or remove internal publication and require callers to state semantic vs technical intent explicitly. |
| A12 | M3 | PCM runtime can be declared `FAMILY_RUNTIME_RELEASED` before delegate reset completes | Current dirty reconfigure experiment calls `observePcmRuntimeReleased()` (which mints+accepts terminal receipt) before `super.reset()`. | `CONFORMANCE_BUG / BLOCKER` | Two-phase PCM retirement: revoke/close source intake + drain writers -> perform exact delegate reset/release -> mint terminal release proof. Never prove release before physical/runtime completion. |
| A13 | M3 | Current period projection after `reset()/release()` identifies the source that was actually destroyed | PCM reset/release passes `playbackPeriodProjection.snapshot()` to release proof after delegate cleanup; projection may already be successor B while owned source was A. | `CONFORMANCE_BUG / HIGH` | Capture committed source ownership identity before cleanup and bind proof to that exact identity. |
| A14 | M3 | Protocol write-lease drain proves Media3 PCM delegate tail ordering | Retained PCM handoff manufactures `tailOrderingProof="pcm-adapter-lease-drained:A->B"`. That is not evidence that delegate reconfigure is unnecessary or that A's accepted tail is ordered before B's first data. | `CONFORMANCE_BUG / BLOCKER` | Adapter/runtime must produce real compatibility + tail-order proof. If unavailable, retained handoff is forbidden and real reconfigure/rebuild is required. |
| A15 | M3 | M2 shadow exception-isolation remains safe after the projection becomes production authority | `observeSafely()` still catches reducer/lifecycle errors and logs `DIVERGENCE` for many authority-critical events. | `CONFORMANCE_BUG / HIGH` | Split facts-only diagnostics from authority transitions. Authority failures must be explicit/fail-closed before associated real side effects proceed. |
| A16 | M2–M5 | P2 generation publication means a usable USB output is bound | `publishNextGeneration()` invokes generation observer before later REQUESTED/OPENING/ACTIVE/RELEASING facts. Generation changes also occur for invalidate/detach/release/fallback. Coordinator currently maps any generation to `UsbBound(gen)`. | `CONFORMANCE_BUG / BLOCKER` | Separate generation invalidation from availability. New generation first invalidates old binding; only exact ACTIVE/exclusive/exact P2 facts create `UsbBound`. REQUESTED/OPENING/RELEASING/FAILED -> `Unavailable`; fallback -> `SharedPcm`. |
| A17 | M5 | SharedPcm fallback will be rebound to USB by a later P2 generation callback | Coordinator deliberately ignores generation updates for stacks already `SharedPcm`. | `PROVEN / CONTAINED` | Preserve this property while implementing A16; add explicit fallback/current-output tests. |
| A18 | M4 | Direct PREFILL may write carrier data before committed `ActiveWriteLease` | PREFILL is a staged activation effect. Transport `writeCanonical()` still requires exact P2 active-session binding (`withActiveSession` + `ensureActiveSession`) and stage permit. P5 confirmed the P2 physical fence, but found the cached stage permit itself can outlive a later semantic PAUSE; see A33. | `P2 PHYSICAL FENCE PROVEN / STAGE TEMPORAL FENCE OPEN` | Keep exact P2 redemption. Add A33-style revocable stage authority so each real PREFILL/ARM side-effect boundary revalidates activation + current intent/mutation/output. |
| A19 | M3/M4 | Direct full release proof is emitted only after actual runtime close | `closePump()` captures exact source and calls `closingPump.close()` before release observation, but P5 showed `session.release()` may return with cleanup/transport facts not fully green; `observeDirectRuntimeReleased()` then manufactures a non-blank string proof that protocol accepts. | `REFUTED AS TERMINAL FAMILY PROOF / BLOCKER` | Preserve exact pre-captured identity and close-before-observe ordering, but require a typed runtime-issued Direct full-release proof whose physical zero/drain/restore facts are actually green; see A34. |
| A20 | M3–M5 | Main-thread `ExoPlayer.release()` is synchronously bounded enough to be the stack retirement barrier | Real rebuild hung because `ExoPlayer.release()` did not return. Custom renderer/native teardown may dominate platform timeout behavior. | `CONTRADICTED / BLOCKER` | Protocol `Retiring`/physical quiesce must be an explicit barrier independent of blind synchronous release. Define timeout/failure disposition and prohibit candidate physical activation without old-runtime terminal proof. |
| A21 | M3–M5 | If protocol `beginRetiring()` fails, logging a divergence and continuing real release is acceptable | `retireStack()` currently executes `beginRetiring()` inside `observeSafely()`. | `CONFORMANCE_BUG / HIGH` | Stack retirement authority must fail closed and return explicit success/failure; real cutover cannot continue on a swallowed protocol retirement failure. |
| A22 | M5 | Recovery's `requireFrameProgress` boolean remains valid for the whole recovery timeout | `UsbRecoveryActivationExpectation` snapshots `usbResumePlaybackRequested` for up to 5 s. User PAUSE/PLAY during recovery can make the expected frame-progress rule stale in either direction. | `CONFORMANCE_BUG / HIGH` | Fence recovery against `IntentRevision` and re-evaluate latest ledger. Separate transport-active proof from semantic resumed-frame proof. |
| A23 | M5 | Interrupted-playback resume boolean can own final rebuild resume | It is persisted across detach/reconnect, but current stack publication re-reads service ledger through `restoreAfterTechnicalQuiesce()` before final `playWhenReady`. | `CONTAINED` | Document as reconstruction metadata only; do not use it as live semantic authority in activation/recovery policy. |
| A24 | M5 | Process death should preserve the old in-memory PLAY revision | Ledger restarts PAUSE; persisted player restore does not auto-play. | `PROVEN / FAIL-SAFE` | Keep unless product policy explicitly changes to cross-process auto-resume; such a policy would require a new persisted semantic-intent contract. |
| A25 | M2/M3 | `onStreamChanged` is a repeatable app-command acknowledgment | Media3 semantics tie it to renderer enable/stream replacement, not arbitrary select operations. | `PROVEN FALSE` | Treat stream observation as durable identity fact, not request/ack callback. |
| A26 | M2/M3 | Event-time identity can safely be reconstructed from global player getters | Media3 EventTime already carries event-time current timeline/window/period context. | `PROVEN FALSE AS DESIGN CHOICE` | Keep event-time context intact and join from same provenance. |
| A27 | M6 | Legacy bridge/coordinators are already removable after M3 software cutover | Production still uses ManualNavigation projection/binding and Direct seek causal evidence; old track coordinator is mostly plumbing-only but not all compatibility paths are replaced. | `M6_GATE_NOT_MET` | After A01–A26 corrections, perform whole-repo authority/caller audit and delete only when exact replacement evidence exists. |
| A28 | M5 | Android DETACHED arrives before transport/session close | Historical physical run showed renderer/session release first, DETACHED later; owner facts were already cleared. A transient last-proven stable identity repair exists outside P2. | `CONTRADICTED / ALREADY_REPAIRED` | Preserve session-scoped stable-identity latch; never regress to owner-facts-at-DETACHED assumption. Test both event orders. |
| A29 | M5 | USB permission survives physical re-enumeration | Historical real replug showed connection-scoped permission can disappear. | `CONTRADICTED / ALREADY_FAIL-CLOSED` | Preserve permission reacquisition + stable-identity reproof; no VID/PID-only or stale-runtime authority. |
| A30 | M2/M3 | Destination stream facts do not need to retain the `AdapterInstanceId` that supplied them | P4 reproduced: renderer adapter A binds destination occurrence/facts; reducer drops A; adapter B can then obtain `PcmConfigurePermit` for the same mutation/occurrence/facts. | `CONFORMANCE_BUG / HIGH` | Bound destination observation/state must retain exact destination `AdapterInstanceId`; family prepare/stages require that adapter unless an explicit handoff transfers authority. |
| A31 | M5 | A newer rebuild can supersede an old rebuild while the old publication path holds `publicationLock` | P4 reproduced: R1 enters `retirePublished()` while holding publication lock; R2 cannot even increment generation until R1 returns, so R1 cannot observe supersede and may publish first. | `CONTRADICTED / BLOCKER` | Keep generation/current-epoch transitions in short critical sections only. Never hold supersede-generation lock across stage/retire/release/framework/native work. Old retirement is a keyed barrier; newer epochs may supersede stale publication attempts. |
| A32 | M3/M5 | Protocol `Retired` for PCM implies physical PCM runtime/delegate release | P4 reproduced legal `PcmOwned -> beginRetiring() -> Retired` once lease drains, with `familyOwnership == PcmOwned` and no PCM teardown receipt required. Direct has an explicit teardown barrier; PCM does not. | `CONFORMANCE_BUG / BLOCKER` | Add teardown-scoped PCM retirement state/proof parallel in principle to Direct: exact pre-captured ownership, lease revoke/drain, successful delegate reset/release/tail proof, then exact receipt before clearing ownership and reaching Retired. |
| A33 | M3/M4 | A cached Direct PREFILL/ARM stage permit remains valid across a later semantic PAUSE | P5 found PREFILL permit can span multiple render/write calls. Later writes recheck P2 session identity but not semantic intent/current stage authority; ARM also has PLAY-check-to-native-arm race. | `CONFORMANCE_BUG / BLOCKER` | Every real PREFILL write and ARM side-effect boundary must redeem/revalidate a revocable activation-stage fence bound to activation/stage/intent revision/mutation/occurrence/output. PAUSE before unstarted side effect denies/defer; PAUSE after partial side effect triggers exact cleanup. |
| A34 | M3/M4 | Direct `session.release()` returning is sufficient evidence for `FAMILY_RUNTIME_RELEASED` | P5 found release records `cleanupGreen`/`transportGreen`/`exactCleanupComplete` but may return even when native destroy, alt/clock restore, interface release, driver reconnect, writer/drain or carrier state is not fully green; protocol accepts a manufactured non-blank string proof. | `CONFORMANCE_BUG / BLOCKER` | Runtime teardown must mint immutable typed `DirectFamilyReleased` proof only when writer join, feeder/P5 zero incl. partial/half, native teardown and required transport/alt/clock/interface/driver restoration all succeed for exact source/runtime identity. |
| A35 | M3/M4 | Retained DOP handoff can collapse real carrier proof into generic `Completed` + booleans | P5 found physical retained path has strong feeder/P5 zero/reset/marker evidence, but protocol receives only generic completion text plus booleans, bypassing typed retained `SourceRetirementReceipt` / `DirectRuntimeRetained` proof. | `CONFORMANCE_BUG / HIGH` | Runtime/feeder must issue typed retained proof bound to old/new exact occurrences, runtime/source generation, feeder/P5 zero, reset, no stale partial/half and retained marker continuity before successor DOP ownership commits. |
| A36 | M3/M4 | Direct seek carrier barrier may be marked satisfied before physical old-carrier barrier completes | P5 found `observeDirectPositionReset()` can mint seek barrier token from causal position match before `closePump()` performs drain/teardown; A34 means close return itself is not reliable terminal proof. | `CONFORMANCE_BUG / BLOCKER` | Position match opens a pending seek-reset phase only. `carrierBarrierSatisfied` is published exclusively by actual old runtime/feeder zero/reset/release proof after physical completion. |
| A37 | M2/M3 | Every `onTimelineChanged` callback/reason is equivalent to playback-topology identity replacement | P6 found Halcyon/Media3-facing code must tolerate metadata/display-only timeline callbacks (`SOURCE_UPDATE`, and in some versions even `PLAYLIST_CHANGED`) without queue/current playback identity changing; directive05 further found that merely saying “authoritative topology change” still left the epoch producer undefined. | `CONFORMANCE CONTRACT / FINAL CLARIFIED` | The sole `PlaybackTopologyEpoch` producer is the per-stack application/route topology seam: initial stack creation and playback-relevant queue/source topology mutations mint/advance it before Exo dispatch. `onTimelineChanged` only reconciles mappings within the current epoch and never mints one. Metadata-only replacement is non-topological; unexpected structural change without a matching application/rebuild epoch is bounded fail-closed rather than heuristically versioned. |

## 4. New global invariants required before M3 physical qualification resumes

### 4.1 Order-independent observation join closure

For every logical fact assembled from independent observations, each operand arrival must:

1. store an immutable, provenance-scoped fact;
2. recompute the relevant join closure;
3. complete at most one exact transition when all required identities agree;
4. remain safe under repetition and duplicates;
5. discard/retire stale operands on mutation, adapter, timeline, stack or output supersede.

The final legal state must not depend on callback arrival order. At minimum:

```text
STREAM -> PERIOD == PERIOD -> STREAM
APPLICATION -> PERIOD -> STREAM == STREAM -> APPLICATION -> PERIOD
CURRENT_OCCURRENCE may arrive before/after each of the above
supersede/retire may occur between any two operands
```

### 4.2 No identity inference from a non-unique field

`mediaId`, queue index, `periodUid`, `windowSequenceNumber`, adapter id and output generation are distinct facts. No single non-unique field may be reverse-looked-up and silently promoted into another authority identity.

### 4.3 Event-time provenance is preserved

Facts originating from Media3 `EventTime`, renderer stream callbacks, application currentness and timeline callbacks retain their own observation provenance until an explicit join proves they describe the same target/occurrence.

### 4.4 Callback absence/repetition is not progress authority

A legal transition cannot depend on an undocumented callback recurring later. If a required external fact may already have arrived, it must be retained/replayed. If it may never become available, the state machine must expose a bounded fail-closed/unsupported state rather than wait forever.

### 4.5 Terminal release proof follows physical/runtime completion

A `SourceRetirementReceipt` with `FAMILY_RUNTIME_RELEASED` or stronger is historical evidence of completed release. The protocol may first close/revoke source authority, but terminal proof is minted only after the relevant runtime/delegate close/reset/drain/restore operation has actually completed for that exact source identity.

### 4.6 Proof provenance comes from the layer it claims to prove

Protocol lease drain cannot stand in for Media3 delegate tail ordering; a generation number cannot stand in for active USB availability; a projection snapshot cannot stand in for committed source ownership. Synthetic strings or derived booleans are not proof unless the producing runtime actually observes the stated boundary.

### 4.7 Generation identity and output availability are separate

P2 generation invalidates stale hardware authority immediately. A usable `UsbBound(generation)` additionally requires an exact current, active, permitted, claimed, exclusive/exact output/session fact. Generation publication alone only invalidates/rebinds identity; it never proves availability.

### 4.8 Technical execution never restores stale semantic intent

Every technical stop/flush/rebuild/recovery restore uses captured `IntentRevision` only as a fence and re-reads the latest service ledger immediately before restoring execution.

### 4.9 M2 exception isolation does not cross into M3 authority

Shadow observation errors may be swallowed for diagnostics. Once the same reducer owns production authority, authority-critical failures become explicit fail-closed outcomes. Production side effects cannot continue after a swallowed authority mutation failure.

### 4.10 Lifecycle barriers are explicit, bounded and fail closed

Stack/player/PCM/Direct/USB release are not inferred from method invocation or anticipated callbacks. Replacement activation requires exact terminal proof or a defined timeout/failure state. `ExoPlayer.release()` returning is not the sole physical-retirement proof. Production rebuild wiring is frozen in architecture §9.1.1: sequencer-minted epochs, 5s `USB_RECOVERY_ACTIVATION_TIMEOUT_MS` fail-closed watchdog, `Failed` delivered without waiting for a hung `ExoPlayer.release()`, main-only discrete `release()`, timeout never `Retired`.

### 4.11 Destination authority retains adapter provenance

A destination bound from renderer/runtime facts stores the exact `AdapterInstanceId` that supplied those facts. Matching family/facts/occurrence from another registered adapter is insufficient to prepare/configure/arm it. Cross-adapter authority exists only through an explicit modeled handoff that transfers provenance.

### 4.12 Direct stage authority is revocable at each physical side-effect boundary

A cached PREFILL/ARM permit cannot authorize later real writes/arm after semantic PAUSE, mutation supersede or output invalidation. Each physical side-effect boundary revalidates the exact activation/stage/intent/mutation/occurrence/output identity in addition to P2 USB/session redemption. Partial side effects use identity-scoped cleanup; they do not become valid merely because the original stage permit was once current.

### 4.13 Physical family proof is runtime-issued, typed and scope-specific

PCM and Direct terminal/retained/seek proofs come from the runtime layer that observes the physical boundary. A method return, string, generic Completed receipt or logical write-lease drain is not sufficient proof. `Retired` cannot be reached while a conflicting PCM/Direct runtime remains physically live. Retained DOP and Direct seek carry their own typed zero/reset/tail/marker/barrier evidence.

### 4.14 Rebuild supersession remains live while retirement waits

A newer rebuild can mint/advance its supersede epoch even while an older rebuild is blocked in retirement. No lock needed to mint the newer epoch may span framework/native teardown. The old request may continue exact cleanup but cannot publish after supersede. Break-before-make still blocks successor physical activation until terminal old-stack proof. Production must not post whole `rebuild()` onto the main looper; see architecture §9.1.1.

## 5. Phase disposition after consolidated A01–A37 audit

- **M1:** pure algebraic protocol model remains accepted at `0e05399c`. No coordinator/P4/P5/P6 finding requires a new authority plane or replacement of the ownership/lease/receipt algebra. Additive destination-adapter provenance and teardown/stage substates are required to make the frozen invariants executable.
- **M2:** checkpoint `e0b0afdf` is reopened for observation-contract conformance across A01–A09/A15/A16/A30/A37. The current raw adapter cannot be promoted without an order-independent, topology-versioned provenance reconciler and authority/facts exception split.
- **M3:** software checkpoint `e6c2b3a9` is reopened; physical gates remain RED. Required correction includes A10–A16/A19–A21/A30/A32–A36 before physical M3 qualification resumes.
- **M4:** `4c65a0a9` P2 lease-redemption mechanisms remain useful evidence and A18's physical P2 fence remains valid, but M4 physical qualification has **not started**. A16/A33 and all upstream M3 retirement/provenance blockers must close first.
- **M5:** blocked until technical-intent, output-availability, bounded/supersedable rebuild, PCM/Direct terminal proof and recovery revision fencing close (A10/A16/A20–A24/A31/A32/A34).
- **M6:** blocked until the corrected production path is one-writer green and a fresh whole-repo authority audit shows compatibility helpers are facts-only/removable.

## 6. Independent review result and final targeted rereview

Orthogonal review is complete:

- **P4 directive69:** `ASSUMPTION_AUDIT_P4_BLOCKERS`; independently reproduced A30 cross-adapter destination provenance loss, A31 publication-lock supersede failure and A32 PCM Retired-with-live-runtime.
- **P5 directive30:** `ASSUMPTION_AUDIT_P5_BLOCKERS`; independently confirmed A12–A18 boundaries, refuted A19 as sufficient terminal proof and added A33–A36 Direct stage/full-release/retained/seek proof gaps.
- **P6 directive04:** found no contradiction to A01–A29 and one finite reference-supported A37 topology-epoch gap.

Coordinator has folded A30–A37 into the architecture addendum. Final review is deliberately narrow:

- **P4 directive70:** verify A30–A32 and their composition with break-before-make/one-writer semantics;
- **P5 directive31:** verify A33–A36 plus corrected A18/A19 Direct proof semantics;
- **P6 directive06:** `ASSUMPTION_ADDENDUM_V2_P6_REFERENCE_GREEN / READY_FOR_REPAIR`; A37 closes with application/route-owned topology epoch minted before topology dispatch, while `onTimelineChanged` remains reconciliation-only.

All final targeted reviews are green. The assumption audit is closed. Repair proceeds in ordered tranches from committed checkpoint `4c65a0a9`, beginning with R0 quarantine + R1 observation/provenance/output availability under P3 directive86.
