# USB Parallel Work Coordination

> Status: experimental but active
> Started: 2026-08-13
> Scope: Mica USB Exclusive P3 / P4 / P5 implementation plus P6 reference-architecture audit
> Coordination authority: the active coordinator conversation
> Dynamic state root: `/.scratch/agent-coordination/`

## 1. Purpose

Mica currently develops USB Exclusive P3, P4 and P5 in parallel ChatGPT conversations and separate Git worktrees. ChatGPT conversations cannot directly act as built-in child agents of another conversation, so this protocol emulates parent/worker coordination with four primitives:

1. **AgentDock** for reading/writing the device-local repository and inspecting all worktrees;
2. **Git worktrees/branches** for code isolation;
3. **versioned coordination rules** in this document;
4. **single-writer Markdown mailboxes** under `.scratch/agent-coordination/` for current state and messages.

The objective is not to simulate hidden agent IPC. The objective is to make dependencies, contract ownership, blockers, test evidence and integration decisions explicit enough that one coordinator can safely direct several independent implementation conversations.

## 2. Authority hierarchy

When sources disagree, use this order:

1. user instruction in the active conversation;
2. repository hard rules (`AGENTS.md`, `CONTEXT.md`, ADRs, audio-quality consent);
3. accepted USB architecture / status decisions in the coordinator-maintained USB status document;
4. this coordination protocol;
5. `.scratch/agent-coordination/CURRENT.md` current orchestration state;
6. worker INBOX directives;
7. worker OUTBOX reports;
8. local notes inside an individual worktree.

A worker must not silently override a higher-level source. Report the conflict to the coordinator.

## 3. Current topology

Initial workers:

| Worker | Branch | Worktree | Primary role |
|---|---|---|---|
| P3 | `codex/usb-exclusive-p1` | `D:/AI/3/mica-music-a8fa312e3b45477f922d0dde3ca38e99d203cebc/.scratch/usb-exclusive-p1-worktree` | Generic UAC1/UAC2 PCM capability, negotiation and transport contracts |
| P4 | `codex/usb-exclusive-p4-a1` | `D:/AI/3/mica-usb-exclusive-p4-a1` | Host stress/projection/sanitizer/state-space/release hardening infrastructure |
| P5 | `codex/usb-exclusive-p5-prep` | `D:/AI/3/mica-usb-exclusive-p5-prep` | DSD preparation: source abstraction, DSF/DFF readers, DoP/Native encoders and RAW_DATA evidence |
| P6 | read-only | main repo + `.codex-tmp/*usb-ref*` snapshots | Reference-architecture audit: compare ownership/generation/transition/recovery patterns against the coordinator architecture; no production implementation ownership |

P3/P4/P5/P6 are worker identifiers, not the old sequential roadmap phases.

The coordinator may replace a worker/worktree later. `CURRENT.md` is the live topology authority.

## 4. Single-writer mailbox model

Dynamic files live only in the **main worktree** at:

```text
.scratch/agent-coordination/
├─ CURRENT.md                 # coordinator writes, workers read
├─ p3/
│  ├─ INBOX.md                # coordinator writes, P3 reads
│  └─ OUTBOX.md               # P3 writes, coordinator reads
├─ p4/
│  ├─ INBOX.md                # coordinator writes, P4 reads
│  └─ OUTBOX.md               # P4 writes, coordinator reads
├─ p5/
│  ├─ INBOX.md                # coordinator writes, P5 reads
│  └─ OUTBOX.md               # P5 writes, coordinator reads
└─ p6/
   ├─ INBOX.md                # coordinator writes, P6 reads
   └─ OUTBOX.md               # P6 writes, coordinator reads
```

Rules:

- **Coordinator is the only writer of `CURRENT.md` and all `INBOX.md` files.**
- **Each worker is the only writer of its own `OUTBOX.md`.**
- A worker must not edit another worker's mailbox.
- Do not use a shared append-only file with multiple writers.
- Dynamic mailbox files are coordination state, not durable product documentation; they should normally remain under `.scratch` and out of release commits.

## 5. Coordinator responsibilities

The coordinator conversation must:

1. inspect worker worktrees directly through AgentDock instead of relying only on conversational claims;
2. maintain `CURRENT.md` with current worker focus, dependencies, blockers and conflict risks;
3. issue directives through worker INBOX files;
4. read OUTBOX reports and verify important claims against Git/tests/artifacts when possible;
5. own cross-worker contract decisions and integration order;
6. prevent two workers from independently redefining the same shared contract;
7. decide when a contract becomes `FROZEN` and when consumers may integrate it;
8. decide merge/cherry-pick/rebase order across worker branches;
9. keep long-term architecture/status docs separate from high-frequency orchestration state;
10. surface unresolved user/product decisions instead of letting workers guess;
11. own the shared architecture model, authority ownership, state-machine boundaries and linearization points before implementation directives are issued;
12. declare an `ARCHITECTURE_FREEZE` when repeated blockers show that local repairs are exposing undefined cross-layer semantics;
13. require model/state-space review before re-opening shared transition implementation after such a freeze.

The coordinator should not rewrite worker implementation code merely to avoid sending a directive. If a change belongs to a worker's ownership area, route it to that worker unless urgent integration repair requires otherwise.

### 5.1 Architecture authority gate

For shared USB playback behavior, workers do not own architecture by accumulation. The coordinator must first define the durable contract in ADR/status/architecture documentation when a change affects any of:

- application PLAY/PAUSE authority;
- navigation/seek/currentness generation;
- Media3 playback occurrence identity;
- PCM/DOP family ownership or handoff;
- USB session/output generation;
- activation/commit linearization;
- reconnect/fallback/output-stack rebuild semantics.

A worker may choose implementation details inside a frozen contract, but may not add a new shared authority flag/generation/grant/state owner merely because it closes the current failing test. Such a need is a `CONTRACT_CHANGE` and returns to the coordinator.

When `CURRENT.md` says `ARCHITECTURE_FREEZE`, P3/P4/P5 must stop production evolution of the affected shared contract. Read-only analysis and preservation of evidence are allowed; implementation resumes only after the coordinator publishes a candidate architecture, independent review completes, and the coordinator marks the contract `FROZEN`.

The current USB playback architecture authority is `docs/USB_EXCLUSIVE_AUDIO_ARCHITECTURE.md` plus accepted ADRs. Worker OUTBOX notes are evidence, not architecture authority.

## 6. Worker responsibilities

Every worker conversation must:

1. read `AGENTS.md` and relevant domain/ADR/status docs;
2. read this protocol;
3. read `.scratch/agent-coordination/CURRENT.md`;
4. read its own `INBOX.md` before starting a new slice;
5. stay inside its ownership boundary unless the coordinator explicitly authorizes a cross-boundary change;
6. report shared contract changes before consumers integrate them;
7. write a structured OUTBOX update at meaningful milestones, before waiting on another worker, and before declaring a slice complete;
8. include exact branch/worktree, changed files, commits if any, tests run, failures, blockers and required actions;
9. never merge another worker branch or rewrite another worker's history without a coordinator directive;
10. never mark a dependency as stable merely because the local implementation compiles.

## 7. Contract states

Shared contracts use these states:

- `PROPOSED`: shape discussed, consumers must not code against it as stable.
- `EVOLVING`: owner is implementing it; consumers may inspect but should avoid deep integration.
- `CANDIDATE`: owner believes the shape is ready; consumers may build adapters/tests with coordinator approval.
- `FROZEN`: coordinator accepts the contract for the current integration phase; incompatible changes require a `CONTRACT_CHANGE` report.
- `SUPERSEDED`: replaced by a newer contract; consumers must migrate.

`FROZEN` does not mean permanent API stability. It means parallel workers may safely depend on that version until the coordinator reopens it.

## 8. Initial ownership map

### Frozen P2 contracts

These are shared invariants, not owned for redesign by P3/P4/P5:

- `UsbOutputSessionOwner` single-owner/generation semantics;
- permission/attach/detach lifecycle;
- playback-intent migration;
- break-before-make rebuild publication;
- bounded recovery/backoff and explicit SharedPcm fallback;
- wake/background policy;
- exact-only / fail-closed audio-quality policy.

Workers may add tests around them but must not rewrite them without an explicit coordinator decision.

### P3 owns

- generic UAC1/UAC2 descriptor parsing;
- capability model and typed rejection model;
- UAC1/UAC2 clock/rate-control contracts;
- candidate builder / exact-only negotiator;
- generic `UsbTransportConfig` shape;
- identity/reconnect and generic provider/open boundaries;
- final integration of generic Native USB transport into production.

P4/P5 are consumers of these contracts unless explicitly delegated.

### P4 owns

- deterministic native host test infrastructure;
- scheduler/feedback long-duration projection and fixed-seed stress;
- sanitizer/ABI/warning gates;
- fake completion/control streams;
- release state-space/soak/resource matrices and P4-specific evidence docs.
- reference-derived synthetic boundary corpus infrastructure: replayable fixtures, capacity/clock edge cases, metamorphic cases and deterministic generators. P4 does not define production acceptance policy.

P4 may extract generic algorithm helpers inside its worktree, but **P3 owns production integration/API shape**. Before P4 helpers are adopted into generic production transport, coordinator + P3 must review their assumptions.

### P5 owns

- `SeekableByteSource` abstraction and local-file adapter for P5;
- DSF/DFF raw DSD container readers;
- canonical DSD byte-stream contract;
- DoP encoder and marker/carry/idle behavior;
- Native DSD payload encoders/framing evidence;
- DSD-specific golden vectors and RAW_DATA evidence fixtures.
- reference-project capability evidence audit for adversarial-corpus inputs and provenance.

P5 does **not** own generic USB capability/transport contracts. DSD fields needed in P3 capability should be proposed via OUTBOX until P3's relevant seam is coordinated.

### P6 owns

- read-only architecture comparison against approved local reference snapshots;
- exact reference code-path evidence for ownership, generation, transition, recovery and cleanup patterns;
- cross-reference comparison tables and provenance/license notes;
- identification of coordinator-architecture gaps supported by concrete reference behavior.

P6 owns **no Mica production contract or implementation**. It must not turn a reference implementation into policy by itself, must not copy GPL/reference code, and must route every architecture implication back to the coordinator as evidence.

## 9. Known cross-worker collision zones

### P3 ↔ P4: Native transport/scheduler

P4 is currently extracting scheduler/feedback helpers and modifying the SK02 prototype while P3 will later parameterize generic Native transport. Therefore:

- P4 can continue host-test extraction;
- P3 can continue Kotlin capability/candidate work;
- before P3 begins broad Native transport edits (P3.4), both workers must report current helper/API assumptions;
- coordinator decides whether P4 commits are cherry-picked first, reimplemented, or used only as test references.

### P3 ↔ P5: capability / RAW_DATA

P5 may discover required DSD/RAW_DATA capability facts before P3 freezes its model. Therefore:

- P5 records required facts and evidence in OUTBOX/tests;
- P5 must not independently fork the generic `UsbAudioCapability` contract;
- P3 incorporates generic facts where appropriate;
- runtime DSD integration waits until the relevant P3 capability/transport seam reaches at least `CANDIDATE`, normally `FROZEN`.

### Cross-phase status documentation

`docs/USB_EXCLUSIVE_AUDIO_STATUS.md` is cross-phase architecture/status documentation, not a worker scratchpad. During parallel work it is coordinator-owned for cross-phase edits. Workers should put phase-specific evidence in their own docs/OUTBOX and propose status changes rather than independently evolving three copies.

Existing local worker edits to copies of the status document must be reported and reconciled before integration; do not silently discard them.

## 10. Contract change protocol

When a worker needs to change a shared contract, OUTBOX must include:

```text
CONTRACT_CHANGE
Owner: P3 | P4 | P5
Contract: <name>
Current state: PROPOSED | EVOLVING | CANDIDATE | FROZEN
Old shape/assumption:
New shape/assumption:
Reason:
Evidence/tests:
Affected workers:
Migration required:
Requested coordinator action:
Commit/files:
```

If the contract is `FROZEN`, the worker must stop dependent cross-worker integration until the coordinator accepts/rejects the change.

## 11. Worker update protocol

OUTBOX milestone reports should use:

```text
WORKER_UPDATE
Worker: P3 | P4 | P5
Timestamp:
Branch:
Worktree:
Status: RUNNING | BLOCKED | READY_FOR_REVIEW | DONE_SLICE
Current slice:

Completed:
- ...

Changed files/contracts:
- ...

Commits:
- <hash> <subject>

Tests/evidence:
- command/result

Blockers:
- ...

Needs from coordinator:
- ...

Needs from other workers:
- ...

Risks / assumptions:
- ...

Next intended step:
- ...
```

A worker that has no commit yet should say so explicitly. Dirty working state is valid but must be reported.

## 12. Coordinator directive protocol

INBOX directives should use:

```text
COORDINATOR_DIRECTIVE
Worker: P3 | P4 | P5
Directive id: <date-sequence>
Priority: HIGH | NORMAL | LOW

Goal:
- ...

Do:
- ...

Do not:
- ...

Dependencies / contract states:
- ...

Report back when:
- ...
```

The latest non-superseded directive wins. The coordinator should mark obsolete directives as `SUPERSEDED` rather than deleting history during an active slice.

## 13. Integration queue

`CURRENT.md` maintains an integration queue with states:

- `DISCOVERED`: candidate change exists;
- `WAITING_DEPENDENCY`: cannot integrate yet;
- `READY_FOR_REVIEW`: tests/evidence available;
- `APPROVED`: coordinator picked integration order;
- `INTEGRATED`: merged/cherry-picked/reimplemented and verified;
- `REJECTED`: intentionally not integrated;
- `SUPERSEDED`: newer work replaces it.

No worker should infer `APPROVED` from another worker saying “done”.

## 14. Merge / Git safety rules

- Worker branches remain isolated until coordinator decides integration order.
- Do not use `git add -A` in dirty multi-purpose worktrees when committing coordination or docs changes.
- Preserve pre-existing untracked/unrelated files.
- Before cross-worker merge/cherry-pick, coordinator inspects source status, commit boundaries and overlapping files.
- Prefer small, phase-scoped commits that can be cherry-picked independently.
- If two workers changed the same contract/file for different reasons, resolve semantically from ownership/contract state; do not blindly take one side of a textual merge conflict.

## 15. Test evidence rules

A worker report must distinguish:

- compile success;
- deterministic unit/host test success;
- emulator/device smoke;
- real hardware qualification;
- long-duration/resource evidence;
- user remote QA evidence.

Do not promote one evidence class into another. In particular:

- descriptor parsing success is not real-device playback qualification;
- scheduler projection is not OEM background qualification;
- a DAC playing once is not generic UAC compatibility proof;
- user remote validation must identify the device/format/scenario without leaking credentials or unnecessary private data.

## 16. Worker bootstrap

For an existing or new P3/P4/P5 conversation, the user/coordinator can give this instruction:

```text
你现在是 Mica USB 并行开发的 <P3/P4/P5> worker。
通过 AgentDock 读取主工作树：
1. docs/agents/USB_PARALLEL_WORK_COORDINATION.md
2. .scratch/agent-coordination/CURRENT.md
3. .scratch/agent-coordination/<p3|p4|p5>/INBOX.md
继续你当前 worktree 的工作。不要改 CURRENT 或其他 worker mailbox；阶段性完成、遇到 blocker、需要共享 contract 变更或准备集成时，把结构化 WORKER_UPDATE 写到你自己的 OUTBOX.md，然后在对话中告诉我已更新 OUTBOX。
```

The worker should not require the user to manually relay code status that AgentDock can inspect.

## 17. Coordinator operating loop

The coordinator repeats:

```text
read CURRENT
   ↓
read changed OUTBOX files
   ↓
inspect worker git status/diff/tests as needed
   ↓
resolve dependency/contract conflicts
   ↓
update CURRENT
   ↓
write worker INBOX directives
   ↓
workers continue independently
```

This is intentionally asynchronous at the human-conversation level: workers run only when their conversations are actively prompted. The Markdown protocol provides durable coordination state between those turns; it does not create background execution.

## 18. Graduation criteria

Keep this system project-specific until it proves useful. Consider generalizing it beyond USB only if it demonstrates that it can:

- prevent at least one real cross-worktree contract conflict;
- reduce manual copy/paste status relays;
- preserve clear ownership during parallel changes;
- support reproducible integration decisions;
- remain low-maintenance enough that mailbox bookkeeping does not exceed the coordination benefit.

If it becomes noisy, simplify the schema before adding automation.
