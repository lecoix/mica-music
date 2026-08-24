# USB Exclusive Audio Architecture

> Status: `FROZEN_V1_CORE / ASSUMPTION_AUDIT_ADDENDUM_V2_ACCEPTED / ADDENDUM_V3_REBUILD_PRODUCTION_ACCEPTED`
> Frozen: 2026-08-15
> Assumption-audit addendum opened: 2026-08-16
> Rebuild production addendum V3 accepted: 2026-08-17
> Frozen from: `COORDINATOR_CANDIDATE_V3`
> Authority: active coordinator conversation
> Scope: production USB-exclusive playback architecture across SharedPcm/USB PCM/Direct DSD, Media3 lifecycle, navigation/seek/pause, USB ownership, recovery/fallback and release qualification.
> Original review basis: P4 directives 59/60/61 concurrency audits, P5 directives 27/28 DSD-boundary audits and P6 directives 01/02 local-reference audits all green.
> Current audit basis: real M3 device evidence disproved an implicit `TIMELINE_PERIOD -> RENDERER_STREAM` ordering assumption. P4 directive69, P5 directive30 and P6 directive04 then found A30–A37 additional adapter/proof/lifecycle gaps. None requires a new authority plane; they are folded into the observation/provenance, Direct staged-side-effect, retirement-proof and rebuild/recovery clauses below. Core M1 authority/lease/receipt algebra remains frozen.
> Implementation rule: addendum V2 is accepted by P4 directive70, P5 directive31 and P6 directive06. Implementation repair may proceed in staged software tranches, but M3 physical/M4/M5/M6 progression remains blocked until the corresponding repair gates are independently green. Corrections may add provenance/state needed to make the frozen authority model real; any actual authority-model replacement still requires explicit coordinator refreeze.

## 1. Why this document exists

The USB work has accumulated several locally correct contracts: `UsbOutputSessionOwner` generation safety, Media3 playback-occurrence identity, Direct DSD STARTED-only arm, DoP carrier GAP continuity, exact-only transport, manual-navigation request ids and family handoff checks. Those contracts solved real failures, but the playback-transition authority remained split across `MicaCompositePlayer`, `ManualNavigationTransitionBridge`, `DirectDsdTrackTransitionCoordinator`, PCM sink callbacks and Direct renderer callbacks.

That split makes correctness depend on callback ordering that Media3 does not promise. Recent paused DOP->PCM work exposed the pattern clearly: grant, revoke and intent-generation patches can each close one interleaving while leaving another interleaving undefined.

The architecture is therefore changed from **bug-driven local coordination** to a **spec-driven playback protocol**. The protocol must define ownership and linearization before further production wiring.

## 2. Top-level architecture

USB exclusive is split into authority/execution layers. A layer may consume facts from another layer, but must not steal its authority.

```text
Product policy / UI
        |
        v
Playback route + queue commands
        |
        v
PlaybackIntentLedger  <---- survives Exo stack rebuilds
        |
        v
UsbExclusivePlaybackProtocol  <---- per-Exo-stack reducer
        ^              ^
        |              |
 Media3 adapters       +---- UsbOutputSessionOwner generation / leases
        |
        +---- PCM adapter ----> decoder / AudioSink
        +---- DSD adapter ----> Direct renderer / carrier runtime
        |
        v
Format / capability negotiation
        |
        v
UsbOutputSessionOwner
        |
        v
UsbIsochronousTransport / Native USBFS
        |
        v
DAC
```

`PlaybackIntentLedger` is not a second transition state machine. It is the cross-stack canonical ledger for semantic PLAY/PAUSE intent only. The per-stack protocol adopts the latest immutable intent snapshot and remains the sole owner of stack-local mutation/currentness/family/activation decisions.

### 2.1 Product policy / UI

Owns user-facing choices only: requested output mode, remembered device, exact/processed/compatibility policy, explicit fallback consent, volume policy and diagnostics presentation.

It does not infer runtime facts and does not directly claim/release USB.

### 2.2 Playback route + queue commands

`MicaCompositePlayer` / service routing translates MediaSession, Bluetooth, car and app commands into canonical playback commands. Canonical product PLAY/PAUSE first updates the cross-stack `PlaybackIntentLedger`; navigation/seek first begins a protocol mutation; only then is the corresponding ExoPlayer operation dispatched.

Technical controls used for stack staging, pipeline flush, rebuild, service destruction or Media3 execution suppression are **not** semantic PLAY/PAUSE commands and must not mint a new product intent revision.

It does not decide whether PCM/DSD is allowed to accept bytes.

### 2.3 `PlaybackIntentLedger`

A service/product-lifetime ledger owns only the latest semantic application playback intent:

```text
IntentSnapshot(revision, desired = PLAY | PAUSE)
```

A semantic PLAY<->PAUSE edge advances `revision`; repeated nested publication of the same already-current semantic intent is idempotent. The ledger survives Exo stack replacement, so a user PAUSE arriving while the old stack is retiring cannot be overwritten by an older rebuild snapshot that said PLAY.

Each new per-stack protocol must adopt the latest ledger snapshot before it may mint any activation permit. Old and new stacks never transfer a stack-local token; they only observe the shared immutable intent revision.

### 2.4 Exclusive Playback Protocol

A new per-Exo-stack state machine is the **only owner of stack-local playback-transition authority**. Working name: `UsbExclusivePlaybackProtocol`.

It owns:

- the latest ledger `IntentSnapshot` adopted by this stack;
- logical playback mutation/navigation/seek epoch;
- exact target playback occurrence binding;
- PCM/DOP family ownership and retirement state;
- typed activation permits, side-effect receipts and commit dispositions;
- active write-lease ownership/revocation;
- source-retirement classification and proof acceptance;
- semantic paused/playing state for the active family;
- binding to the current output target / USB output generation.

It does **not** perform USB syscalls, PCM delegate configure, Native writes or DSD encoding.

### 2.5 Media3 adapters

PCM sink wrappers and Direct DSD renderer are protocol adapters. Their job is to carry authoritative Media3 identities and lifecycle observations to the protocol and execute permits returned by it.

They must never reconstruct callback identity from global current item, format equality or UI state.

### 2.6 Format / capability negotiation

Owns immutable UAC capabilities, exact candidate selection, typed rejection and signal-exactness facts. It does not own playback state.

### 2.7 `UsbOutputSessionOwner`

The existing P2 contract remains frozen. It is the sole owner of USB-output generation and the sole serialized seam for USB side effects, permission, attach/detach, recovery, fallback publication and active-session replacement.

It must not absorb Media3 queue/navigation state.

### 2.8 Transport / carrier runtime

Owns URBs, feedback, bounded rings, PCM packing, DoP session continuity and Direct DSD carrier liveness. It executes already-authorized work; it does not decide which logical track or playback intent is current.

## 3. Authority planes

Each fact has exactly one authority owner.

| Authority | Owner | Consumers | Forbidden substitutes |
|---|---|---|---|
| USB device/session generation | `UsbOutputSessionOwner` | protocol, transport | mediaId, renderer generation |
| Requested output/fallback policy | product policy | route owner | transport health inference |
| Latest semantic PLAY/PAUSE intent | cross-stack `PlaybackIntentLedger` | per-stack protocol, route/rebuild | `AudioSink.play/pause`, renderer STARTED, rebuild snapshot |
| Stack-local adopted intent + transition decisions | `UsbExclusivePlaybackProtocol` | PCM/DSD adapters | reading the ledger and acting later without a reducer transaction |
| Logical navigation/seek epoch | protocol | adapters | current queue index after callback arrival |
| Playback occurrence | Media3 application/lifecycle observation, stored/validated by protocol | adapters/protocol | Format equality, current mediaId alone |
| Adapter lifecycle identity | immutable `AdapterInstanceId` | protocol/adapters | StackId or occurrence alone |
| PCM/DOP family ownership | protocol | adapters | sink/renderer local booleans |
| Renderer STARTED / execution readiness | exact Media3 adapter instance lifecycle | protocol/Direct adapter | application PLAY intent |
| Active physical write ownership | protocol-issued `ActiveWriteLease`, executed by adapter/runtime | PCM/Direct data plane | old activation permit or current globals |
| DSD CONTENT vs GAP execution mode | Direct carrier runtime under current protocol/lease state | feeder/session | Media3 render cadence |
| USB IO side effects | `UsbOutputSessionOwner` + current request/cleanup lease | hardware | protocol generation comparison alone |
| UI runtime facts | facts publisher | UI/diagnostics | preferences as active state |

The architectural rule is: **semantic intent answers “may product playback proceed?”, playback occurrence answers “which playback instance is this?”, adapter lifecycle answers “which concrete renderer/sink instance emitted this observation?”, renderer execution state answers “is this execution point ready?”, active write ownership answers “may this already-activated source still write?”, and USB generation/lease answers “which hardware session may receive the IO?”. No one token substitutes for another.**

## 4. Identity model

Every asynchronous side effect must be attributable to immutable identity captured before the callback can become stale.

```text
PlaybackStackId
UsbOutputGeneration
IntentRevision
MutationId
AdapterInstanceId
NavigationRequestId? / SeekRequestId?   # aliases inside one MutationEpoch, never peer freshness authorities
TargetMediaId
ExpectedTargetPeriodUid?
PlaybackOccurrence(periodUid, windowSequenceNumber)
FamilyOwnershipId
ActivationId
ResourceIdentity / SideEffectReceiptId
```

### 4.1 `PlaybackStackId`

Minted when an Exo playback stack is created. All protocol/adapters belong to exactly one stack. Stack rebuild retires the old protocol instance; old callbacks cannot enter the new instance.

### 4.2 `UsbOutputGeneration`

Comes only from `UsbOutputSessionOwner`. A new USB request/session/recovery generation invalidates old hardware-side permits. **Generation identity is not output availability.** Publication of a newer generation proves only that older hardware authority is stale; it does not prove that the new generation has reached a usable claimed/exclusive/exact ACTIVE session. Playback protocol may continue to know the logical target, but hardware activation requires a separately observed exact current output binding. REQUESTED/OPENING/RELEASING/FAILED or generation-only observations map to `Unavailable`; only current ACTIVE/exclusive/exact session facts may create `UsbBound(generation)`. Explicit fallback creates `SharedPcm`, which later generation publication must not silently reverse.

### 4.3 `IntentRevision`

Monotonic revision of canonical semantic application PLAY/PAUSE intent, minted only by `PlaybackIntentLedger`. Repeated identical nested calls may be coalesced; a semantic PLAY<->PAUSE edge always advances the revision. Technical Exo quiesce/stop/prepare operations and Media3 execution suppression do not advance it.

This replaces ad-hoc resume grants as the root authority. There is no separate long-lived “resume grant” state in the target architecture. Each stack protocol adopts an immutable `IntentSnapshot` and refreshes it transactionally before any decision that depends on semantic intent.

### 4.4 `MutationId`

Monotonic logical playback mutation serial for any operation that can invalidate stream identity:

- manual track navigation;
- automatic media-item transition;
- seek/discontinuity requiring Direct restart;
- queue replacement/start-at-item;
- output-stack rebuild when playback occurrence changes.

Manual navigation mints the id before Exo seek/navigation dispatch. Seek mints before seek dispatch and returns an adapter-scoped causal handle described below.

Automatic transition uses a two-step rule because renderer read-ahead may observe B before application currentness leaves A:

1. renderer/sink observations of a future occurrence are stored only as `CandidateOccurrence(adapterInstanceId, occurrence, facts)` and carry **no mutation or family authority**;
2. the Exo-stack application adapter mints the auto `MutationId` only when one application-looper observation proves both the logical current target and the full current-player occurrence have advanced to B; the newly minted mutation may adopt only an already-observed candidate with exactly the same occurrence/target facts.

No callback is relabeled with “whatever MutationId is current now”. An unmatched read-ahead candidate stays quarantined or is discarded.

### 4.5 `PlaybackOccurrence`

Full Media3 occurrence identity, currently `MediaPeriodId(periodUid, windowSequenceNumber)`. Renderer projections and Analytics current-player occurrence remain separate observations; only exact full identity may bind a destination.

### 4.6 `AdapterInstanceId`

Minted whenever a concrete renderer/sink-projection adapter instance is created inside one Exo stack. Lifecycle observations such as Direct `onStreamChanged/onStarted/onStopped/onDisabled`, PCM period projection and source-release callbacks carry this identity.

A stale R1 lifecycle callback cannot satisfy/retire R2 merely because both belong to the same `PlaybackStackId` and exact occurrence. Direct STARTED authority is valid only for the adapter instance that owns the target runtime/occurrence.

### 4.7 Mutation causal handles

Some Media3 callbacks do not natively carry Mica's `MutationId`, especially same-occurrence seek. For these seams the dispatch path installs an immutable adapter-local causal handle **before** Exo dispatch. A seek handle contains at least stack id, mutation id, adapter instance id, source occurrence and target source position. The concrete adapter may consume it only through its qualified lifecycle sequence; it may never read the latest protocol `MutationId` after a callback arrives and attach that id retroactively.

The existing qualified Direct seek STOP/reset/session-generation ordering is preserved as the initial adapter proof while migration occurs. The common protocol replaces its global authority, not its causal correlation evidence.

### 4.8 `FamilyOwnershipId`, `ActivationId` and resource identity

`FamilyOwnershipId` identifies the currently committed PCM/DOP source ownership. `ActivationId` identifies one bounded attempt to create/configure/activate a successor. A side effect that creates or mutates a resource returns a resource identity/receipt bound to its `ActivationId`; late cleanup must use that identity and can never mean “close/reset whatever resource is current now”.

### 4.9 External observation reconciliation and provenance addendum

`mediaId`, timeline/window identity, `periodUid`, full `PlaybackOccurrence`, renderer `AdapterInstanceId`, format/family facts, semantic intent and USB output facts are **independent observations** until an explicit join proves that they refer to the same target and authority epoch. Adapter code must not depend on one callback arriving before another unless Media3/P2 explicitly guarantees that ordering and the architecture records the guarantee.

For every multi-observation authority join:

- each operand is stored as an immutable fact with enough provenance to reject stale timeline/adapter/mutation/stack/output epochs;
- arrival of **any** operand recomputes the join closure; a legal final state is order-independent (`STREAM -> PERIOD` and `PERIOD -> STREAM` converge identically);
- repetition is idempotent and a missing future callback is never required to make progress from facts that already arrived;
- unresolved renderer facts are keyed by exact adapter + occurrence, not only by a single "latest" slot per adapter;
- `mediaId` alone is never reverse-promoted to one period/occurrence when duplicates are possible; expected target period/window/index evidence is carried when the dispatch path knows it;
- Analytics/EventTime facts retain their own event-time timeline/window/period provenance until joined; a global player getter from a different logical instant may not be paired with an EventTime occurrence as proof;
- stale operands are retired on playback-topology replacement, mutation supersede, adapter replacement, stack retirement and output invalidation;
- when renderer stream facts bind a mutation destination, the bound destination retains the exact `AdapterInstanceId` that supplied those facts. A different adapter cannot later prepare/configure/arm that destination merely because family/facts/occurrence match, unless an explicit modeled handoff transfers authority;
- timeline provenance uses a **playback-topology epoch**, not raw `onTimelineChanged` callback count or reason. The sole epoch producer is the per-Exo-stack application/route topology seam: stack creation mints the initial epoch, and a canonical playback-relevant queue/source topology mutation mints the next epoch **before** the corresponding Exo dispatch. Presentation-only metadata replacement is explicitly non-topological and does not advance the epoch;
- `onTimelineChanged` never mints a topology epoch by itself. It publishes/reconciles immutable window/period mapping facts inside the already-current epoch. Placeholder/resolution and delayed period realization therefore may arrive after renderer stream facts without reclassifying those already-observed facts into a stale epoch;
- canonical topology-mutation evidence is an application command that changes playback-relevant ordered queue/source identity (set/clear/add/remove/move, or replacement whose playback-source identity changes). A metadata-only replacement/update that preserves playback-source identity is not such evidence. Exact representation of source identity is adapter-owned but excludes presentation `MediaMetadata` and cannot use `mediaId` alone when duplicates are possible;
- if Media3 exposes a structural window/period change that cannot be reconciled with the current application-minted epoch and no corresponding topology mutation/rebuild epoch exists, the adapter reports bounded `INSUFFICIENT_EVIDENCE`/fail-closed and requests an explicit source/stack reconciliation path; it must not heuristically mint an epoch from callback count, reason enum, Timeline object identity, mediaId change, or period-map first-match;
- EventTime/renderer/timeline facts carry the application-minted topology epoch under which they were observed. Advancing the epoch retires operands from the prior epoch; within one epoch, later mapping facts may complete joins with earlier renderer facts in either arrival order.

Callback semantics are facts, not request acknowledgements. In particular, renderer `onStreamChanged` is not assumed to recur after arbitrary application re-selection. If a supported path cannot obtain a required exact fact, the adapter exposes a bounded fail-closed/insufficient-evidence state rather than waiting forever.

Proof provenance follows the same rule. A protocol write-lease drain does not by itself prove Media3 delegate tail ordering; a P2 generation number does not prove a usable USB session; a current projection snapshot does not prove the identity of the runtime actually released. The layer that physically observes a boundary must produce the proof for that boundary.

## 5. Protocol state

The implementation should expose one immutable snapshot and mutate it only through synchronized reducer operations.

Conceptual state is intentionally algebraic rather than a bag of independent booleans:

```text
ProtocolState
  lifecycle: Active | Retiring(inFlightIds) | Retired
  stackId
  adoptedIntent: IntentSnapshot
  outputTarget: SharedPcm | UsbBound(usbGeneration) | Unavailable
  applicationCurrent: { mediaId, periodUid?, currentPlayerOccurrence? }
  adapters: registered AdapterInstanceId set / exact lifecycle facts
  mutation:
      Stable
      | Mutating(MutationEpoch, sourceRetirement, destination, activation)
  familyOwnership:
      None
      | PcmOwned(FamilyOwnershipId, occurrence, semanticPaused, writeLease)
      | DopOwned(FamilyOwnershipId, occurrence, semanticPaused, runtimeIdentity, writeLease)
```

`MutationEpoch` contains manual/auto/seek aliases only as metadata, plus exact source/target identity. `sourceRetirement`, `destination` and `activation` are phases inside that one mutation, not peer freshness owners.

Required invariants:

- at most one `FamilyOwnershipId` is current;
- a committed family ownership always has exactly one revocable active write lease;
- a successor conflicting activation cannot mint until the required source-retirement receipt is terminal/accepted;
- `Retiring` rejects new mutations/activation permits but continues to accept commit/cleanup completion for already-issued `ActivationId`s;
- `Retired` accepts no adapter event except diagnostic stale-drop accounting;
- a bound destination belongs to exactly one current `MutationId`; supersede detaches the old destination from authority even if its adapter callback later arrives;
- application intent and Media3 execution readiness are separate: `semanticPaused=false` does not imply renderer STARTED, and runtime suppression does not mutate the ledger intent;
- `SharedPcm`, `UsbBound` and `Unavailable` are distinct output targets; `Unbound` may not ambiguously mean normal SharedPcm and forbidden USB activation.

A snapshot is for diagnostics/tests. Production correctness must call reducer methods rather than reading a snapshot then making a later decision.

## 6. Atomic reducer / permit / receipt model

The current `hasGrant -> bind -> consume -> accept` pattern is forbidden because correctness spans several synchronized objects/calls. All authority checks that decide whether a new side effect may begin happen in one reducer transaction. Framework/native work executes outside the reducer lock and returns typed evidence.

### 6.1 Logical versus physical linearization

`ActivationPermit` mint is the **playback acceptance-authority** linearization point. It is not the USB-IO linearization point. Real USB IO still requires redemption of the matching `UsbOutputSessionOwner` request/session lease immediately before the hardware side effect.

Therefore every hardware-capable permit carries `UsbOutputGeneration`, but a numeric generation comparison inside the protocol is never itself authority to submit URBs/claim/release/restore.

### 6.2 Typed side-effect contract

Every permit authorizes one finite side-effect scope and one exact completion milestone. The executor returns a typed receipt:

```text
SideEffectReceipt
  NotStarted(activationId)
  Completed(activationId, resourceIdentity, facts)
  PartialNeedsCleanup(activationId, resourceIdentity, facts)
  TerminalFailure(activationId, resourceIdentity?, failure)
```

Protocol commit returns one disposition:

```text
CommitDisposition
  CurrentPlaying(familyOwnershipId, activeWriteLease)
  CurrentPaused(familyOwnershipId, activeWriteLease)
  RetryPendingSameMutation
  CurrentCleanupRequired(resourceIdentity, afterCleanup = RETRY_SAME_MUTATION | TERMINAL)
  StaleNoEffect
  StaleCleanupRequired(resourceIdentity)
  RetiringCleanupRequired(resourceIdentity)
  TerminalFailure
```

Receipt mapping is total; an adapter/model is not allowed to invent a fourth outcome:

| Receipt | Activation still current + protocol Active | Superseded/new mutation/output generation | Protocol Retiring |
|---|---|---|---|
| `NotStarted` | `RetryPendingSameMutation` | `StaleNoEffect` | `StaleNoEffect` and retire barrier decrements this attempt |
| `Completed` | commit `CurrentPlaying`/`CurrentPaused` from latest ledger intent | `StaleCleanupRequired(resourceIdentity)` when the completed resource can conflict; otherwise `StaleNoEffect` | `RetiringCleanupRequired(resourceIdentity)` when cleanup is required; otherwise no-effect completion |
| `PartialNeedsCleanup` | `CurrentCleanupRequired(resourceIdentity, RETRY_SAME_MUTATION)` | `StaleCleanupRequired(resourceIdentity)` | `RetiringCleanupRequired(resourceIdentity)` |
| `TerminalFailure` with no live resource / receipt guarantees cleanup already complete | `TerminalFailure` | `StaleNoEffect` | terminal/no-effect completion of that in-flight attempt |
| `TerminalFailure` with a live resource needing cleanup | `CurrentCleanupRequired(resourceIdentity, TERMINAL)` | `StaleCleanupRequired(resourceIdentity)` | `RetiringCleanupRequired(resourceIdentity)` |

`RetryPendingSameMutation` is reachable after `PartialNeedsCleanup` **only after** the exact identity-scoped cleanup-complete reducer event has closed `resourceIdentity`; it never means retry on top of a partially-created resource. `CurrentCleanupRequired(..., TERMINAL)` transitions to `TerminalFailure` only after cleanup completes. A conflicting successor permit/write remains blocked while any current/stale/retiring cleanup requirement is outstanding.

All cleanup requests are identity-scoped to the `ActivationId/resourceIdentity` that created or changed the resource. They never mean “reset/close current PCM/Direct”. Late cleanup for A therefore cannot destroy B.

### 6.3 PCM activation scope

Conceptual prepare:

```text
preparePcmConfigure(
    mutationId,
    adapterInstanceId,
    targetOccurrence,
    targetFacts,
    adoptedIntentRevision,
    outputTarget,
): PcmConfigurePermit?
```

The reducer atomically verifies:

- protocol lifecycle is Active;
- exact current stack and adapter instance;
- latest mutation/request and logical target currentness;
- expected target period UID and full occurrence;
- destination facts/family;
- required source-retirement receipt has the correct scope and source ownership id;
- the latest ledger snapshot is PLAY and the stack has adopted that exact revision;
- if output is USB, the bound USB generation matches;
- no conflicting activation/cleanup is in flight.

A `PcmConfigurePermit` authorizes **one delegate `AudioSink.configure` attempt only**. Successful configure is the activation side-effect completion milestone. `AudioSink.handleBuffer()` is not part of this unbounded activation permit.

If configure throws Media3 `AudioSink.ConfigurationException`, existing fatal/non-recoverable teardown semantics apply. If configure succeeds, commit creates a `PcmOwned` family ownership and an `ActiveWriteLease` even if the renderer is still BUFFERING/ENABLED and the underlying audio output has not yet initialized.

Subsequent PCM `handleBuffer()` calls require the committed write lease. When semantic state is PAUSED, the PCM lease denies new source/data submission even if Media3 continues calling `handleBuffer()` before STARTED/`AudioSink.pause()`. Later semantic PLAY re-enables the same current ownership if not superseded.

#### Retained PCM source handoff when no `AudioSink.configure()` occurs

A PCM occurrence change does **not** imply Media3 will call `AudioSink.configure()` again. When the decoder/sink configuration is reusable, ownership must still move from occurrence A to B without fabricating a configure side effect.

The retained handoff is a protocol transaction with no delegate configure:

```text
PcmOwned(A, leaseA, runtimeIdentity)
-> mutation B becomes exact/current
-> close A source intake / revoke leaseA for new A writes
-> wait already-entered A writers and any required A tail-order barrier
-> accept SourceRetirementReceipt(
       source=A,
       scope=SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED,
       familyProof=PcmRuntimeRetained(runtimeIdentity, compatibilityFacts, tailOrderingProof)
   )
-> prepareRetainedPcmHandoff(B, same runtimeIdentity)
-> mint new FamilyOwnershipId(B) + ActiveWriteLease(B)
-> B buffers may write
```

Rules:

- candidate/read-ahead B never receives a write lease; exact auto/manual mutation adoption and current-player occurrence B are required first;
- A's lease is revoked before B's lease is minted, so there is no interval with two source writers;
- the retained proof is emitted by the PCM adapter/runtime and must prove that delegate reconfiguration is unnecessary for B's exact format/runtime facts and that already-accepted A data has a defined ordering boundary before first B data;
- if the adapter cannot prove compatible retained runtime/tail ordering, retained handoff is forbidden and the transition must use a real PCM reconfiguration/rebuild path that guarantees a configure milestone before B writes;
- a newer B->C mutation before B ownership commit invalidates the B handoff exactly like any other uncommitted activation; B cannot inherit C's lease;
- semantic PAUSE may complete the retained handoff to `PcmOwned(B, semanticPaused=true)` but still denies B data submission until a later PLAY;
- retained handoff changes source ownership only; it does not mint a USB generation or bypass the final P2 IO lease.

This path is mandatory architecture coverage for Media3 renderer/sink reuse and is not an optimization-specific exception.

### 6.4 Commit intent rule

The commit result uses the **latest non-superseded ledger intent at commit**, not merely the intent that existed at permit mint:

- PLAY r1 -> permit -> PAUSE r2 -> commit => `CurrentPaused`;
- PLAY r1 -> permit -> PAUSE r2 -> PLAY r3 -> commit => `CurrentPlaying` if the same mutation/output/occurrence remains current;
- any newer navigation/seek/stack-retire/output-generation invalidation => the old activation cannot become current, regardless of latest PLAY/PAUSE.

A PAUSE after permit mint does not require impossible rollback of an already-started bounded side effect. It changes the semantic state that commit must publish. A later mutation/output invalidation is stronger: it makes the activation stale and routes it to no-effect or identity-scoped cleanup.

### 6.5 `ActiveWriteLease`

Successful activation yields a revocable long-lived write capability bound to:

```text
PlaybackStackId
UsbOutputGeneration? / SharedPcm target
MutationId
PlaybackOccurrence
AdapterInstanceId
FamilyOwnershipId
ActivationId
family
```

Every final PCM write and Direct CONTENT/GAP writer enters through this ownership capability or an equivalent session-local gate. This may be implemented with an efficient close/drain generation gate rather than a protocol lock per audio buffer, but it must have these properties:

- stale callbacks cannot acquire a newer source's lease;
- supersede/seek/stack retirement/output-generation invalidation closes the old lease before conflicting replacement writes;
- retirement can wait for already-entered writers to drain and produces an observable retirement receipt;
- Direct ordinary PAUSE may retain the same DOP write ownership while execution mode changes CONTENT -> GAP and source chronology freezes;
- actual USB write still redeems the P2 output-session lease at the IO seam.

### 6.6 Direct DSD staged activation

A fresh Direct activation is not one generic long permit. It is one `ActivationId` with bounded stage permits/commits:

```text
CreateRuntime
Prefill
Arm
SourceAccept
```

Each stage revalidates stack, mutation, adapter instance, exact occurrence, output binding and currentness. Direct-specific intent/lifecycle gates apply at every stage **and at every real side-effect boundary inside a stage**. A stage permit is bounded authority, not a durable capability that may be cached across later semantic/mutation/output changes:

- target occurrence/facts may be observed/bound while paused;
- for paused PCM->DOP and fresh paused DOP reconfigure, frozen `DEFER_UNTIL_RESUME` means **no fresh runtime create/prefill/arm/source acceptance while latest semantic intent is PAUSE**;
- every real PREFILL carrier/native write and the physical ARM operation must redeem/revalidate a revocable activation-stage fence bound to at least `ActivationId + stage + IntentRevision + MutationId + AdapterInstanceId + exact occurrence + output binding`; P2 request/session redemption remains additionally mandatory for USB/native side effects;
- `Arm` additionally requires STARTED from the exact `AdapterInstanceId` that owns the target occurrence/runtime;
- renderer STARTED alone never grants product PLAY authority;
- if PAUSE arrives before a fresh side effect begins, that side effect is denied/deferred and a later PLAY retries under current authority; if PAUSE/supersede/output invalidation arrives after a stage produced a partial side effect, exact identity-scoped cleanup/quiesce is required before returning to the paused/current state or allowing a successor;
- if PAUSE arrives only after the Direct runtime is fully armed/current, the existing current ownership may switch single-writer CONTENT -> GAP without creating a new runtime;
- supersede/detach/output-generation invalidation after any Direct side effect begins revokes source acceptance, closes/drains the active write lease where present, joins any content/GAP writer, performs required pre-invalidation quiesce, and cleans the exact runtime/resource receipt before a conflicting successor proceeds.

Direct runtime still owns `Dormant/Prefilled/ArmedContent/ArmedGap/Quiescing/Closed`, DoP marker/carry/session state and writer implementation. Protocol owns whether a transition between runtime states is authorized; it never encodes DoP or writes GAP itself.

### 6.7 Stack retirement and in-flight effects

Protocol lifecycle is `Active -> Retiring -> Retired`.

Entering `Retiring`:

- immediately rejects new mutation/activation permits;
- closes/revokes committed write leases as required by the rebuild scope;
- freezes the exact committed source ownership/resource identity that must be physically retired before any mutable projection can advance to a successor;
- marks all issued but uncommitted activations cancel-after-side-effect;
- continues accepting only their typed receipts, commit dispositions and cleanup-completion events;
- requires a teardown-scoped physical retirement proof for **both PCM and Direct** whenever the family/runtime remains live after write-lease drain. PCM may not reach `Retired` merely because its lease drained; successful delegate reset/release/tail terminal proof for the frozen old source is required. Direct retains its family-specific feeder/runtime/transport proof requirements;
- does not become unreachable until all in-flight effects are either rejected by stale P2 leases or have reached required serialized cleanup/retirement completion.

Only after that barrier may the old stack protocol become `Retired` and replacement hardware activation proceed. `Retired` therefore means no conflicting family runtime/delegate remains physically live under the retired source identity, not merely "no writer currently holds the protocol lease". This is the stack-level form of break-before-make and the existing Direct pre-invalidation quiesce rule.

## 7. Source retirement / release proof

Cross-family and conflicting same-family replacement require explicit terminal proof that the old source can no longer conflict with the successor. A boolean `clean` is not sufficient.

Conceptual proof:

```text
SourceRetirementReceipt
  receiptId
  retiringMutationId
  sourceFamilyOwnershipId
  sourceFamily
  sourceOccurrence              # mandatory for media transition; nullable only for non-media teardown
  sourceAdapterInstanceId
  usbGeneration? / outputTarget
  scope:
      SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED
      FAMILY_RUNTIME_RELEASED
      STACK_TEARDOWN_RELEASED
  semanticPausedAtRetirement
  familyProof
```

Rules:

- receipt is minted only after the old active write lease is closed and required in-flight writers have drained/joined;
- `semanticPausedAtRetirement` is the latest canonical ledger intent at the retirement-commit point, not proof that `AudioSink.pause()` or a Direct GAP callback executed;
- same-plan DOP->DOP may use `SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED` and keep the carrier session/marker chronology under the frozen retained-session contract;
- DOP->PCM, DOP rate/geometry change, output-stack replacement and any transition that cannot safely retain the runtime require `FAMILY_RUNTIME_RELEASED` or stronger;
- a receipt for one old `FamilyOwnershipId` cannot satisfy retirement of another source, even with identical media/facts;
- successor activation is blocked until the receipt scope required by that transition is accepted by the protocol.

### 7.1 Direct family proof

The Direct runtime/feeder/P5 layer produces the proof; the protocol only validates its identity/scope. Before DOP->PCM or old Direct runtime replacement, the proof must establish at least:

- source intake closed;
- CONTENT/GAP writer stopped and joined;
- active write lease drained/closed;
- ExactCarrierFeeder staged carrier bytes are zero;
- feeder/P5 upstream pending packed carrier bytes are zero;
- feeder contract error is null;
- P5 `pendingPackedCarrierBytes == 0`;
- `pendingPartialCanonicalFrameBytes == 0`;
- `hasPendingCanonicalHalfFrame == false`;
- runtime reached the required quiesced/closed or retained-safe state;
- any transition-required transport/alt/clock restore/release is complete.

These may be represented by an opaque family-issued proof object rather than exposing all counters to the protocol, but the semantics above are mandatory. **A method return, non-blank string, generic `Completed` receipt or protocol-derived boolean is not this proof.** The Direct runtime/feeder/transport layer must mint the immutable typed proof only when all required physical facts for that scope are actually green; a close/release that returns with any required native destroy, writer join, feeder/P5 zero, transport/alt/clock/interface/driver restore/release fact incomplete cannot produce `FAMILY_RUNTIME_RELEASED`.

For retained DOP handoff, the physical runtime must instead issue a typed `SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED` / `DirectRuntimeRetained` proof bound to old/new exact occurrences, runtime/source generation and adapter identity. It must carry or encapsulate feeder/P5 zero, source reset, no pending partial/half state and retained marker-continuity result. Generic completion text/booleans cannot replace that proof.

For Direct seek, position/causal-handle match may open a pending seek-reset phase but does not itself establish the carrier barrier. `carrierBarrierSatisfied` may be published only from the old runtime/feeder's actual zero/reset/release proof after the physical barrier completes. No old returned/staged carrier data may coexist with new-source acceptance.

DoP marker phase remains runtime state: it transfers only when the retained-session scope explicitly allows it.

### 7.2 Physical cut versus logical acceptance

References and existing Mica evidence agree that logical supersession is not physical retirement. The old writer/feeder/native work must produce a terminal retirement/cleanup result before a conflicting successor write lease can open. Irreversible effects need not be rolled back; they must be quiesced/cleaned and then the latest successor may take over.

## 8. Canonical scenario contracts

### 8.1 Event taxonomy: semantic intent vs technical execution

Only canonical product commands update `PlaybackIntentLedger`:

```text
IntentChanged(revision, PLAY | PAUSE)
```

The following are separate non-semantic events and never mint an intent revision:

- stack staging/retirement and output rebuild quiesce;
- pipeline flush `playWhenReady=false -> stop/seek/prepare -> restore`;
- service destruction/release;
- Media3 audio-focus/playback suppression;
- renderer ENABLED/STARTED/STOPPED/DISABLED lifecycle.

A Direct renderer STOP while desired intent is still PLAY may put execution into a source-frozen/GAP-safe state, but it does not relabel the product as semantically PAUSED. Activation gates may additionally require renderer execution readiness where specified.

#### Technical quiesce/restore intent fence

Any technical operation that temporarily changes Exo execution state and later restores it may capture an `IntentRevision` only as a **fence**, never as restore authority. Immediately before every technical restore of `playWhenReady`/resume execution, the route adapter must re-read the current `PlaybackIntentLedger` and adopt that snapshot into the current protocol:

- if the ledger revision is unchanged, the technical operation may restore execution consistent with that desired intent;
- if a newer semantic PLAY/PAUSE revision exists, the latest ledger desired state wins and any captured `playWhenReady`, `shouldResume` or `resumePlayback` value is discarded;
- restoring Exo execution must not reopen a PCM/Direct source write lease that the protocol still marks paused/stale/retiring;
- this rule applies to pipeline flush, in-stack restart/reprepare, temporary route quiesce and any future asynchronous technical false->true pair, not only full stack rebuild.

Canonical race result:

```text
ledger PLAY r1
-> technical quiesce captures fence r1 / forces Exo inert
-> user PAUSE publishes r2
-> technical work completes
-> restore re-reads ledger r2
-> Exo remains/returns paused; no source write authority reopens
```

A technical helper is forbidden to restore execution from an old boolean snapshot without this revision check.

### 8.2 Ordinary PCM PLAY/PAUSE

- PLAY/PAUSE first updates the ledger; the current protocol adopts the revision before acting.
- If PCM ownership is current, semantic PAUSE synchronously marks `PcmOwned.semanticPaused=true` and updates/closes the source-write capability for PCM data submission; it does not wait for `AudioSink.pause()`.
- sink `play/pause` is runtime observation only.
- a PCM renderer may remain BUFFERING/ENABLED with configured sink while semantic PAUSE is true; repeated `handleBuffer()` has no current data-plane write authority until PLAY resumes.

### 8.3 Ordinary Direct DSD PAUSE/RESUME

- for an already-current armed DOP ownership, semantic PAUSE freezes source chronology and changes the single writer from CONTENT to legal GAP;
- same activation/write ownership and retained session/marker chronology remain;
- semantic RESUME may return GAP -> CONTENT only when the current ownership, occurrence, output generation and adapter instance are still valid;
- renderer execution suppression with desired PLAY may also require GAP/source-freeze, but does not set semanticPaused=true;
- no second DoP writer/encoder exists.

### 8.4 PLAYING DOP -> DOP

```text
manual/auto MutationEpoch
-> close old source write lease / classify navigation retirement
-> no ordinary transition PAUSE-GAP start
-> terminal SourceRetirementReceipt
   scope = SOURCE_INTAKE_DRAINED_RUNTIME_RETAINED when exact same-plan retention is legal
   otherwise FAMILY_RUNTIME_RELEASED
-> exact target occurrence + adapter instance bind
-> Direct staged create/prefill/STARTED-arm/source-accept gates
-> commit new FamilyOwnershipId / ActiveWriteLease
-> content progress
```

The receipt's source `FamilyOwnershipId`/occurrence must match the source being retired; “current DOP is clean” is not sufficient.

### 8.5 PLAYING DOP -> PCM

```text
MutationEpoch
-> close old Direct source write lease
-> Direct FAMILY_RUNTIME_RELEASED receipt with family proof
-> exact PCM occurrence/adapter bind
-> one-shot PcmConfigurePermit while latest semantic intent is PLAY
-> delegate configure side effect
-> commit PcmOwned + ActiveWriteLease
-> PCM data submission under that lease
```

Full DOP runtime release proof precedes PCM configure permit.

### 8.6 PAUSED DOP -> DOP

- an ordinary PAUSE GAP may already exist before navigation;
- navigation closes/drains the old source ownership and obtains the appropriate typed retirement receipt;
- target read-ahead/current occurrence may bind as candidate/target facts while paused;
- frozen `DEFER_UNTIL_RESUME` forbids fresh Direct create/prefill/arm/source acceptance while semantic PAUSE remains current;
- later semantic PLAY advances ledger revision; target activation still requires exact occurrence, adapter instance and renderer STARTED at the arm stage;
- same-plan retained-carrier behavior may preserve only the runtime scope explicitly allowed by its retirement receipt; no stale source intake remains live.

### 8.7 PAUSED DOP -> PCM

- DOP retirement receipt records `semanticPausedAtRetirement=true` from the ledger and includes full Direct family proof;
- PCM occurrence may bind while paused, but no PCM configure permit is issued while latest semantic intent is PAUSE;
- later PLAY allows direct `preparePcmConfigure()`; **no grant/revoke state exists**;
- PLAY -> permit -> PAUSE -> configure success commits `PcmOwned.semanticPaused=true`, even if the renderer never reaches STARTED and no `AudioSink.pause()` occurs;
- PLAY -> permit -> PAUSE -> PLAY before commit commits playing if the same mutation/output/occurrence is still current;
- subsequent PCM buffers are governed by the committed write lease, not by the historical permit.

### 8.8 PAUSED PCM -> DOP

- semantic PAUSE marks current PCM ownership paused independent of sink callbacks;
- PCM source retirement therefore mints a receipt with paused=true even if PCM never reached STARTED;
- DOP target may bind while paused, but fresh Direct create/prefill/arm/source acceptance remains deferred;
- later PLAY + exact target/currentness + staged Direct gates permit activation;
- no stale PCM write may survive retirement because its active write lease is closed/drained before the conflicting Direct successor can write.

### 8.9 Seek

Seek is one `MutationEpoch`, not a second global authority. Same-occurrence seek additionally uses the adapter-local causal handle from §4.7; STOP/reset callbacks cannot simply read the latest protocol mutation id.

For Direct seek, the required carrier chronology barrier is:

```text
mint seek mutation / install adapter causal handle
-> freeze old source intake
-> stop incompatible CONTENT/GAP production
-> close/drain old source write lease
-> drain ExactCarrierFeeder staged bytes and P5 packed output to zero
-> apply qualified source/reset semantics for partial canonical + pending half + marker state
-> bind exact target source position / facts
-> prefill
-> qualified playing-seek re-arm / STARTED-currentness gate
-> mint/commit new source ownership
```

No old canonical/carrier byte may appear after new-source acceptance. A newer navigation/seek mutation invalidates any older uncommitted activation exactly like supersede.

PCM seek may remain Media3-owned when no exclusive family reactivation is required, but every adapter callback still carries adapter/occurrence identity and any stale active write ownership is revoked when the seek invalidates that source.

### 8.10 Automatic next

Automatic transition uses the candidate/adoption rule from §4.4. Renderer read-ahead B is facts-only until an application-looper observation proves logical target B plus exact current-player occurrence B and mints the auto mutation. Only that exact mutation may adopt the candidate. “Manual” and “automatic” differ only at mutation mint/adoption; retirement, family ownership, permits, receipts and write leases are identical afterward.

## 9. Output mode / rebuild / detach / reconnect

Playback protocol and USB output ownership are deliberately separate.

### 9.1 Stack rebuild

Break-before-make remains frozen, but semantic intent survives independently in `PlaybackIntentLedger`.

1. capture queue/position/facts as reconstruction data only; do **not** treat captured `playWhenReady` as authority;
2. move old protocol `Active -> Retiring`, reject new permits and close/drain required active write ownership;
3. keep old protocol reachable for in-flight side-effect receipts and identity-scoped cleanup until its retiring barrier is complete;
4. release/replace USB output through `UsbOutputSessionOwner` as required;
5. create new `PlaybackStackId` / protocol and register fresh `AdapterInstanceId`s;
6. new protocol adopts the **current** `PlaybackIntentLedger` snapshot after creation, so a PAUSE arriving during steps 2–5 wins over an older captured PLAY;
7. bind `SharedPcm`, `UsbBound(newGeneration)` or `Unavailable` explicitly;
8. restore queue/position and allow activation only after the new protocol has adopted current intent/currentness/output facts.

No stack-local protocol token survives across stack ids. Cross-stack continuity is only the service-level semantic intent ledger plus explicit reconstruction data/facts.

Rebuild supersession is itself versioned and must remain live while retirement is waiting. A rebuild/publication generation or epoch is minted/advanced in a short critical section **before** potentially blocking framework/native work. No monitor/lock that a newer rebuild needs in order to publish a newer epoch may be held across candidate staging, old-stack retirement, `ExoPlayer.release()`, USB teardown, renderer/native cleanup or any callback that can block. If R2 arrives while R1 is retiring, R2 must be able to make R1 stale; R1 may continue exact cleanup but may not publish after observing a newer epoch. Candidate publication is compare/current-epoch guarded and remains blocked on the required terminal old-stack physical retirement proof. A timeout/hang is a fail-closed Retiring/failure outcome, not positive release proof.

#### 9.1.1 Production rebuild publication (A20 / A31) — normative, no alternatives

This subsection closes the production wiring that D101/D103 left to implementer choice. It is coordinator-owned. P3 implements it as written. Do not invent a second timeout, a second authority plane, an off-main `ExoPlayer.release()`, or a “timeout means Retired” path.

Reference evidence (read-only; do not copy code, including GPLv3 NeriPlayer):

- RawS `FfmpegAudioPlayer` / `PlaybackWorkerController.ensureAvailableForReplacement`: wait a bounded time for the **old worker to actually exit**; if it did not exit, **refuse replacement**. Timeout is not success.
- sylvakru `UsbExclusiveAudioEngine.stopWorkerKeepingSession`: stop intake, `join` with a bound, escalate to `hardCloseSession` on timeout, **return false**. P6 already forbids copying sylvakru’s weaker “timeout/log and continue” drain; Mica timeout is `Failed` / still `Retiring`, never `Retired`.
- NeriPlayer `UsbExclusiveSessionController.stopInternalLocked` + `scheduleNativeClose`: close the write gate and capture the exact old handle under the session owner, then wait drain / native close **outside** the session lock. Session generation/availability stay distinct from “close returned”.

Mica stays stronger than all three on occurrence/adapter provenance, `IntentRevision`, and P2 IO redemption. Those strengths do not justify a different rebuild thread/timeout shape.

**Owners (unchanged):**

| Fact | Owner |
|---|---|
| Rebuild generation / publish-or-stale | `PlaybackOutputRebuildCoordinator` |
| `Active -> Retiring` and refuse-on-failure | `UsbExclusivePlaybackProtocol.beginRetiring` / `retireStack` (A21) |
| Old PCM/Direct terminal proof | existing A32 / A34 owner-invoked runtime endpoint; predicate `hasTerminalOldRuntimeProof()` |
| USB generation / native USB IO | frozen P2 `UsbOutputSessionOwner` |
| Framework player object disposal | `ExoPlayer.release()` on the main looper only |

**Bound (frozen name, frozen value):** production `retirementTimeoutMs` is exactly `MicaMediaService.USB_RECOVERY_ACTIVATION_TIMEOUT_MS` (`5_000L`). This is the only allowed hang watchdog. It is a fail-closed deadline, not a measured Exo/native teardown budget, and not recovery-activation success. Do not add another constant. Do not use `Long.MAX_VALUE` on the production constructor. Tests may inject a shorter bound.

**Threads (frozen):**

1. **Rebuild sequencer.** One dedicated single-thread executor, thread name `mica-output-rebuild`. Every `PlaybackOutputRebuildCoordinator.rebuild(...)` call from production (`scheduleOutputPathRebuild`, USB recovery rebuild, Direct-prototype rebuild) is submitted here. The main looper must never run `rebuild()` as a whole.
2. **Main looper.** Only discrete Exo-touching runnables, each posted separately: A21 `retireStack` on the published stack; owner-invoked old-runtime teardown request if that seam is already main-bound; `ExoPlayer.release()`; candidate Exo construct/stage/publish that touch `ExoPlayer`. A posted runnable may block main; it must not include generation mint or the watchdog wait.
3. **`ExoPlayer.release()`.** Main looper only. Never the sequencer. Never the watchdog thread. Never a daemon “retirement” worker.

**Normative sequence for one rebuild epoch R:**

```text
sequencer: generation++ ; onGenerationPublished(R)     // no teardown lock
sequencer: capture intent snapshot (reconstruction only)
sequencer: build candidate; Exo construct posted to main if the factory requires it
sequencer: if generation != R -> release candidate; Superseded
main discrete: stage candidate inert
sequencer: if generation != R -> release candidate; Superseded
main discrete: A21 retireStack / beginRetiring on the published stack
           refuse/exception -> Failed; do not ExoPlayer.release(); do not publish
sequencer: arm watchdog = USB_RECOVERY_ACTIVATION_TIMEOUT_MS
           request old-runtime terminal proof via the existing A32/A34 endpoint
           (not by treating ExoPlayer.release() as that wait)
if TerminalProof within bound:
  main discrete: previousExo.release()     // teardown only, still not proof
  sequencer: await that runnable at most remaining bound
if watchdog fires at any point in this wait:
  Failed(R, retirement-timed-out) is returned on the sequencer immediately
  candidate is released; never Published
  protocol remains Retiring; not Retired
  orphaned main release / native close may continue as exact cleanup only
  that late work cannot publish
if bounded wait returns without watchdog:
  evaluate hasTerminalOldRuntimeProof() on the retiring stack identity
  captured before clearing published references
  missing proof -> Failed; never Published
  TerminalProof + generation still R -> publishCandidate under publishClaimLock only
```

**Required production constructor shape:**

- `retirementTimeoutMs = USB_RECOVERY_ACTIVATION_TIMEOUT_MS`
- `awaitOldStackBarrier` must **not** be omitted (the coordinator default `TerminalProof` is test-only). Production must map `hasTerminalOldRuntimeProof()` on the captured retiring stack to `TerminalProof`, else `FailedWithoutProof`.
- `retirePublished` still runs A21 then framework `ExoPlayer.release()` on main, but coordinator publication must not treat that function’s return as proof.

**Forbidden (any one is a spec miss):**

- `mainHandler.post { outputRebuildCoordinator.rebuild(...) }` wrapping the whole rebuild, including `scheduleOutputPathRebuild`, `executeUsbRecovery` rebuild, and Direct-prototype rebuild
- waiting for `ExoPlayer.release()` to return before delivering `Failed` after the 5s bound
- calling `ExoPlayer.release()` off main
- mint waiting on a previous posted `rebuild()` / `release()`
- `publishClaimLock` spanning stage / retire / release / native teardown
- timeout, watchdog fire, `hardClose`, or `ExoPlayer.release()` returning minting `Retired` or substituting for A32/A34
- a second timeout constant, RawS 1.5s/2.5s, sylvakru 800ms, or Neri 1.5s copied as Mica policy
- A22/A23 recovery-vs-ledger work in the same slice
- a new public proof/receipt type

**Finite outcomes:**

| Situation | Rebuild result | Protocol | Candidate |
|---|---|---|---|
| A21 refuse | `Failed` immediately | stays `Active` or unchanged | not published |
| A32/A34 proof within 5s, then `ExoPlayer.release()` returns, generation current | `Published` | old stack `Retired` only if proof actually green | published |
| A32/A34 missing after in-time return | `Failed` | `Retiring` | not published |
| Native/proof/release still running when 5s elapses | `Failed` delivered now | `Retiring` | not published |
| R2 starts while R1 is inside retire/release | R2 mints; R1 cannot publish after seeing R2 | R1 cleanup may finish | only current epoch may publish, and only with terminal proof |

Break-before-make is unchanged: a newer epoch may mint while old cleanup hangs, but must not activate a second renderer without old-runtime terminal proof. A hung old `ExoPlayer.release()` on main may still occupy the main looper for framework disposal; it must not occupy the sequencer or block generation mint.

This slice does **not** add a new native-close API. If A32/A34 completion still occurs during renderer disable inside `ExoPlayer.release()`, keep that owner-invoked path. The sequencer times out the wait for that main runnable and delivers `Failed` without remaining blocked on it. Do not invent a parallel teardown owner.

### 9.2 Physical detach

`UsbOutputSessionOwner.deviceDetached()` advances USB generation immediately. Protocol marks `UsbBound(oldGeneration)` unavailable and invalidates hardware stage permits/write leases bound to the old generation. Any already-entered USB IO is resolved through the P2 lease/syscall/cleanup seam; protocol waits for the corresponding receipt/cleanup disposition before conflicting replacement hardware activation.

Logical target and ledger intent may remain known, but no stale renderer callback or old active write lease may write a replacement session.

### 9.3 Recovery

Recovery belongs to output owner/policy, not renderer callbacks. A recovered USB request/session has a new generation, but generation alone is only stale-authority invalidation. Hardware permits/writes require a fresh exact current ACTIVE/exclusive/exact output observation before protocol adopts `UsbBound(newGeneration)`. Logical mutation/current target is not converted into the new hardware generation by inference. Retry/backoff remains bounded.

Recovery resume state is fenced by `IntentRevision`, not by a captured `resumePlaybackRequested`/`requireFrameProgress` boolean. Interrupted queue/position/resume data is reconstruction metadata only. Immediately before deciding whether resumed frame progress is required or restoring execution, recovery re-reads/adopts the latest `PlaybackIntentLedger`: PLAY->PAUSE during recovery removes the resume/frame-progress requirement and keeps execution paused; PAUSE->PLAY applies the current PLAY requirement. Transport/session ACTIVE proof and semantic resumed-playback proof remain separate.

### 9.4 Fallback

Transport/session failure is typed and published upward. Product policy decides explicit SharedPcm fallback according to consent. Fallback rebuilds/cuts over the output target to `SharedPcm`; it is not an ambiguous `Unbound` state and not a hidden PCM downgrade inside USB transport.

SharedPcm does not redeem a USB lease. If the same protocol implementation is used for SharedPcm, output-target checks make USB-generation requirements inapplicable; if a release build chooses a lighter SharedPcm-only adapter, it must still consume the same cross-stack intent ledger and must not become a second authority for mixed USB transitions.

## 10. Format / signal policy

The architecture preserves existing exact-only rules:

- no silent PCM16 downgrade;
- signal processing that breaks exactness must be explicit and reflected in facts;
- DoP/Native DSD is a separate negotiated family, not a PCM fallback;
- DSD256 DoP feasibility and RAW_DATA framing qualification remain separate capability decisions;
- unsupported/ambiguous topology fails closed with typed rejection.

Playback protocol consumes negotiated destination facts but never invents them.

## 11. Runtime facts and observability

`PlaybackOutputFacts` remains a runtime/UI facts model, not a source of control authority.

Add a separate diagnostic-only protocol snapshot containing protocol lifecycle, stack id, adopted ledger intent revision, mutation id, adapter instance ids, exact occurrence, family ownership id, activation/effect state, write-lease state and output target/USB generation. Tests/logs may compare snapshots, but adapters must call protocol transactions rather than branch on snapshots.

Milestone logs should use stable vocabulary:

```text
intent-ledger-published
intent-adopted
mutation-begun
candidate-occurrence-observed
auto-mutation-adopted
target-occurrence-bound
source-retirement-begun
write-lease-revoked
source-retirement-receipt
activation-stage-permit-minted
activation-side-effect-receipt
activation-committed
activation-stale-cleanup-required
cleanup-complete
intent-paused-active-family
protocol-retiring
protocol-retired
output-generation-invalidated
```

## 12. Components to supersede or narrow

The target architecture intentionally reduces authority duplication.

- add `PlaybackIntentLedger`: service/product-lifetime semantic PLAY/PAUSE ledger only; it is not a family/navigation state machine.
- `ManualNavigationTransitionBridge`: superseded as an authority owner. Timeline/occurrence projection helpers may survive as adapter utilities and candidate observations.
- `DirectDsdTrackTransitionCoordinator`: superseded by the unified protocol. Family ownership/paused semantics move into algebraic protocol state, not a second state machine.
- request-scoped resume grant/revoke: removed from the target protocol; ledger intent plus atomic typed activation stages replace it.
- `DirectDsdSeekDiscontinuityCoordinator`: its global seek authority is superseded, but its qualified renderer/session/STOP/reset causal evidence remains as an adapter-local seek handle until the common mutation adapter proves equivalent correlation.
- `TransitionAwarePcmAudioSink`: remains an adapter but loses independent transition authority; it requests one-shot configure permits, executes receipts, commits ownership and gates later `handleBuffer()` through the active write lease.
- Direct renderer/runtime: remains adapter/runtime owner for STARTED readiness, DSD runtime stages and carrier execution; it does not own logical currentness or semantic product intent.
- `onPlaybackIntentChanged`: may continue feeding output-session/wake/background bookkeeping, but transition correctness uses the ledger/protocol directly and does not depend on this observer receiving the callback later.

## 13. Testing architecture

No further shared-state implementation is accepted from scenario-only tests.

### 13.1 Pure protocol model tests

Build the ledger + protocol as pure/synchronized models with fake typed side effects before production wiring. Required deterministic interleavings include:

- semantic PLAY -> PAUSE -> prepare activation => no permit;
- PLAY r1 -> permit -> PAUSE r2 -> configure receipt -> commit => current paused;
- PLAY r1 -> permit -> PAUSE r2 -> PLAY r3 -> commit => latest intent wins if mutation still current;
- current `PartialNeedsCleanup(A,R)` -> `CurrentCleanupRequired(R, RETRY_SAME_MUTATION)` -> cleanup complete -> retry, with no overlapping successor;
- current `TerminalFailure(A,R)` with live resource -> `CurrentCleanupRequired(R, TERMINAL)` -> cleanup complete -> terminal failure;
- permit -> newer navigation mutation -> side effect receipt -> stale cleanup/no current completion;
- permit -> same-occurrence seek mutation -> old callback/receipt -> stale;
- permit(gen5) -> detach/gen6 before USB IO => P2 lease rejects IO;
- detach during in-flight IO -> receipt -> serialized cleanup -> successor only after terminal barrier;
- old stack PLAY captured -> Retiring -> ledger PAUSE -> new stack creation => new stack adopts PAUSE;
- stack Retiring while activation is in flight -> old protocol remains reachable until receipt/cleanup terminal;
- renderer R1 STARTED/STOPPED -> R2 same occurrence -> stale R1 callback cannot authorize/retire R2;
- read-ahead candidate B while application current A -> application/current-player B -> exact candidate adoption;
- PCM A -> candidate B -> exact auto adopt B -> Media3 reuses sink/no configure -> retained PCM retirement proof -> leaseB -> B write;
- retained PCM B handoff -> C supersede before leaseB commit -> B cannot receive/adopt C ownership;
- paused retained PCM handoff -> ownership B may commit paused but B data remains denied until PLAY;
- read-ahead B -> C supersede before adoption -> B cannot be relabeled C;
- A->B->C with identical facts and stale B callbacks;
- same period UID with different window sequence;
- paused DOP->PCM and PCM->DOP;
- paused same-plan DOP->DOP with retained runtime receipt versus rate/geometry DOP->DOP with full release receipt;
- Direct fresh stage permit -> PAUSE before create/prefill/arm => stage denied/deferred;
- Direct arm/current -> PAUSE => existing ownership CONTENT->GAP;
- Direct seek: old write lease drain/P5 zero barrier before target source acceptance;
- technical pipeline false/true while semantic ledger stays PLAY;
- technical quiesce captures PLAY r1 -> user PAUSE r2 -> technical restore re-reads ledger and keeps Exo/source execution paused;
- Media3 execution suppression STOP while semantic intent remains PLAY;
- source retirement before/after target candidate/current occurrence projection;
- conflicting successor waits until old `SourceRetirementReceipt`/cleanup terminal;
- active write lease revocation prevents stale PCM/Direct final writes;
- SharedPcm target versus `UsbBound` versus `Unavailable` output semantics;
- R1 `retirePublished` blocks on main `ExoPlayer.release()` while R2 mints on the rebuild sequencer before that release returns;
- production-shaped hang: watchdog `USB_RECOVERY_ACTIVATION_TIMEOUT_MS` elapses, `Failed` is delivered on the sequencer while `release()` is still running, candidate is not `Published`, protocol is not `Retired`;
- late `ExoPlayer.release()` return after timeout, even with injected `TerminalProof`, cannot `Published`;
- production `scheduleOutputPathRebuild` / recovery rebuild / Direct-prototype rebuild do not post whole `rebuild()` onto `mainHandler`;
- production constructor passes `retirementTimeoutMs = USB_RECOVERY_ACTIVATION_TIMEOUT_MS` and a real `awaitOldStackBarrier` (not the default `TerminalProof`).

Each case must assert final algebraic protocol state, permit/receipt/lease disposition and whether physical/data-plane side effects are allowed, not only logs.

### 13.2 Model/state-space generation

P4 should generate bounded event permutations from the frozen event vocabulary. Illegal sequences must fail closed; legal sequences must converge to deterministic state. This is independent review, not P4 architecture ownership.

### 13.3 Adapter contract tests

Structure tests prove every product command publishes protocol intent/mutation before Exo dispatch and every renderer callback carries its exact occurrence before requesting a permit.

### 13.4 Physical qualification

Only after protocol + adapter review is green:

1. requalify existing green roots for regression confidence;
2. run paused DOP->PCM;
3. run paused PCM->DOP;
4. mixed queue/auto-next;
5. seek;
6. detach/reconnect;
7. soak/resource/release matrix.

Physical evidence validates implementation; it does not define the architecture.

## 14. Migration plan and one-writer cutover

At every phase each authority fact has exactly one writer. Shadow observers may duplicate observations, never decisions or side effects.

### M0 — Freeze and quarantine

- Baseline accepted production checkpoint before architecture redesign: `76095f05a61239e55db4a7816a44b54d3be9adf8`.
- P3 directive74 uncommitted intent-generation edits are **quarantined design evidence**, not an accepted baseline. They must not be committed/continued during the architecture freeze.
- P4/P5/P6 architecture work is read-only until V2 is frozen.

### M1 — Ledger + pure protocol model only

P3 implements `PlaybackIntentLedger`, the algebraic `UsbExclusivePlaybackProtocol`, typed permits/receipts/retirement/write-lease model and deterministic tests **without renderer/sink/USB wiring**. Existing production bridge/coordinator remain the only runtime authority because the new model has no side effects.

P4 independently runs bounded state-space permutations against the frozen event vocabulary. P5 validates the Direct family proof/staged activation/seek barrier model. No hardware run.

### M2 — Raw-event shadow adapters

Wire the new protocol only to the **raw canonical application/Media3/USB observation seams before legacy bridge/coordinator suppression/gating**. It logs candidate/adoption/retirement/permit decisions but cannot call delegate configure, create Direct runtime, claim USB or alter existing state.

Legacy code remains the sole production authority/writer. Shadow comparison is invalid if it observes only already-filtered legacy decisions.

### M3 — Atomic transition-authority cutover in software

Do not migrate “PCM authority” while leaving a legacy peer family state machine active. In one software checkpoint:

- `UsbExclusivePlaybackProtocol` becomes the sole writer of semantic adopted intent, mutation/currentness, family ownership, retirement, activation and active write-lease state;
- PCM and Direct adapters both consume the protocol contract;
- old `ManualNavigationTransitionBridge` / `DirectDsdTrackTransitionCoordinator` become facts/projection compatibility helpers only and may not accept/complete/release family state independently;
- Direct runtime/DoP/feeder and P2 `UsbOutputSessionOwner` keep their frozen execution ownership.

M3 is software/model/adapter qualification only. No physical mixed-transition claim until one-writer review proves no legacy writer remains.

### M4 — Real hardware lease redemption + physical transition qualification

Before the first protocol permit can cause real USB output, every USB-capable activation stage and final write path already carries/redeems the real `UsbOutputSessionOwner` generation/request or active-session lease. This fence cannot be deferred to a later phase.

Then requalify, in order:

1. existing PLAYING DOP->DOP and DOP->PCM roots;
2. ordinary Direct pause/resume/GAP;
3. PAUSED DOP->DOP;
4. PAUSED DOP->PCM;
5. PAUSED PCM->DOP;
6. seek and mixed/manual/auto transition.

No scenario may bypass typed retirement receipts or active write-lease revocation.

### M5 — Rebuild / detach / reconnect / recovery / fallback integration

Extend the already-present hardware generation fence into full lifecycle integration:

- `Active -> Retiring -> Retired` stack barrier;
- detach during in-flight side effects;
- fresh output generation rebind;
- recovery/backoff;
- explicit SharedPcm fallback / output-target cutover;
- process-death/startup ordering.

P2 owner semantics remain frozen; this phase adapts protocol lifecycle to them rather than redesigning them.

### M6 — Remove compatibility authority

Delete/de-authorize superseded bridge/coordinator/global seek authority only after:

- every production consumer uses protocol/adapter contracts;
- no legacy writer remains;
- all legacy-owned in-flight resources can drain before removal;
- P4 state-space and physical regression are green.

### 14.1 One-writer matrix

| Phase | Semantic intent | Mutation/currentness/family | Runtime/carrier | USB side effects |
|---|---|---|---|---|
| M0 | current product path | legacy path | legacy Direct/PCM | frozen P2 owner |
| M1 | ledger model only; production unchanged | protocol model only; production legacy | unchanged | unchanged |
| M2 | production legacy + read-only ledger shadow | legacy writer + protocol shadow | unchanged | unchanged |
| M3 | `PlaybackIntentLedger` + protocol adopted snapshot | **protocol sole writer**; legacy facts-only | adapters/runtime execute protocol | frozen P2 owner, no new physical claims |
| M4 | ledger | protocol | protocol adapters + runtime + active write leases | P2 leases redeemed before all USB IO |
| M5 | ledger survives stack lifecycle | protocol with Retiring/rebind | runtime cleanup/recovery | P2 owner/recovery/fallback |
| M6 | ledger | protocol only | final adapters/runtime | P2 owner |


## 15. Parallel-work plan after architecture freeze

The new design restores useful parallelism without shared-contract drift:

- **P3**: ledger/protocol core + production adapters, exactly to the frozen spec;
- **P4**: independent pure-model/state-space/Media3 concurrency validator; no production policy changes;
- **P5**: DSD reader/DoP/RAW_DATA/capability + family-proof boundary evidence; no playback policy ownership;
- **P6**: read-only reference-architecture comparison to catch missing retirement/write-boundary patterns; no Mica contract ownership.

Workers may propose contract changes, but only the coordinator can change this architecture document or mark a shared contract `FROZEN`/`SUPERSEDED`.

## 16. Architecture acceptance gates

`FROZEN_V1` was accepted after these gates were satisfied:

- P4 final V3 re-review finds no missing authority owner, undefined key interleaving or ambiguous receipt/retained-PCM/technical-restore result;
- P5 V2 re-review has confirmed typed Direct family proof, staged activation and seek carrier barrier preserve frozen DoP/feeder contracts;
- P6 V2 comparison has confirmed the reference-driven physical-retirement and final-write gaps are explicitly closed;
- every mutable playback fact has one owner in Section 3 and every legal algebraic state obeys Section 5 invariants;
- cross-stack semantic intent continuity is owned by `PlaybackIntentLedger`, while technical Exo controls cannot mutate it;
- every Media3 lifecycle callback that matters carries `AdapterInstanceId` plus exact occurrence/causal mutation evidence where required;
- auto-next read-ahead is candidate-only until authoritative application/current-player adoption;
- PCM activation permit is bounded to one configure attempt; Direct activation is staged; no permit becomes an unbounded data-plane capability;
- receipt mapping is total: current partial/terminal-with-resource outcomes cannot retry/terminate until their exact identity-scoped cleanup completes;
- PCM occurrence ownership can hand off safely under a retained reusable sink/runtime with no configure callback, or fail closed to a real reconfiguration/rebuild when compatibility/tail ordering cannot be proven;
- every technical quiesce/restore re-fences against the latest ledger `IntentRevision`; stale captured resume booleans are never restore authority;
- successful activation yields a revocable `ActiveWriteLease` carried to the final PCM/Direct write boundary;
- source replacement requires an identity-scoped terminal `SourceRetirementReceipt` with the transition-appropriate scope;
- side-effect receipts, stale/retiring commit dispositions and cleanup are bound to exact `ActivationId/resourceIdentity`;
- old protocol lifetime includes `Retiring` drain/cleanup before `Retired`/replacement activation;
- application PAUSE semantics do not depend solely on a future renderer/sink callback;
- real USB IO still redeems frozen P2 `UsbOutputSessionOwner` leases; output generation and playback occurrence remain distinct;
- SharedPcm/UsbBound/Unavailable output targets are unambiguous;
- M0-M6 one-writer matrix has no phase where legacy and protocol both own family/transition state;
- Direct STARTED/DoP GAP/single-writer/exact feeder/pending-half/retained-carrier contracts remain unchanged;
- fallback/exact-quality policy remains explicit and fail-closed.

All gates above are satisfied by P4 directive61, P5 directive28 and P6 directive02. Implementation therefore proceeds by the frozen M1→M6 migration phases; any future contradiction must reopen the coordinator contract explicitly rather than creating a local race patch.
