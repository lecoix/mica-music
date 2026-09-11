# Library Auto-Sync Refactor Plan

> Date: 2026-09-11
> Scope: structural refactor only. Preserve the accepted behavior of ADR-0006 and the current production auto-sync algorithms.

## 1. Goal

The auto-sync feature is functionally mature, but execution responsibilities have accumulated in two large authority objects:

- `LibraryScanOrchestrator`: FULL scan, DEVICE auto-sync, SAF auto-sync, publication, retry scheduling, targeted refresh and artwork repair.
- `MusicLibraryBacking`: runtime state, source/token authority, publication locks, store revision sequencing, commit gates and catalog adoption.

The refactor must improve dependency direction and ownership without changing discovery, deletion safety, retry semantics, publication ordering or scheduler timing.

## 2. Frozen behavioral contracts

These rules are not refactor opportunities. A stage that changes one of them is a behavior change and must stop for separate review.

1. One scheduler owns launch ordering for FULL, AUTO_SYNC, TARGETED_REFRESH and ARTWORK_REPAIR.
2. Observers only produce dirty signals. They never scan or write Room directly.
3. AUTO never creates the first library and never repopulates `CLEARED_BY_USER`.
4. Dirty arriving during an AUTO pass does not cancel that pass; it is retained for bounded follow-up.
5. User FULL scan may preempt current work; ordinary AUTO does not.
6. Source activation, library generation, config fingerprint and operation token remain the stale-result authority.
7. Incomplete discovery cannot produce destructive absence conclusions.
8. DEVICE and SAF retain separate discovery/probe implementations.
9. DEVICE/SAF may share publication protocol, but not source-specific correctness assumptions.
10. No visible AUTO delta means no full snapshot rebuild; checkpoint/retry-only mutation remains allowed.
11. Visible publication performs Room commit before in-memory catalog adoption and cannot be split by cancellation/release.
12. User mutations made after scan start must win during publication rebase; AUTO may not resurrect removed songs.
13. Retry/checkpoint/outbox side effects remain durable and bounded.
14. AUTO queue/session reconciliation remains selective; it must not rebuild playback from the whole library.
15. Current debounce, max-debounce, cooldown, retry wake and artwork fairness behavior is frozen during R1-R6.

## 3. Target dependency direction

```text
LibraryDirtySignalObserver
        |
        v
LibrarySyncScheduler
        |
        v
LibraryOperationExecutor
        |
        +-- FullLibraryScanExecutor
        +-- TargetedMetadataRefreshExecutor
        +-- ArtworkRepairExecutor
        +-- LibraryAutoSyncExecutor
                  |
          +-------+-------+
          v               v
 DeviceAutoSyncPipeline  SafAutoSyncPipeline
          |               |
          +-------+-------+
                  v
       AutoSyncPublicationAuthority
                  |
                  v
           LibraryRepository
                  |
                  v
          Room commit / catalog adopt
                  |
                  v
       AutoSyncPostCommitDispatcher
                  |
                  v
          LibrarySyncScheduler
```

A source pipeline returns an outcome. It does not call the scheduler directly.

## 4. Package direction

```text
com.mica.music.data.library
    LibraryOperationExecutor
    LibrarySyncScheduler
    LibraryOperationAuthority
    LibraryPublicationAuthority

com.mica.music.data.library.autosync
    LibraryAutoSyncExecutor
    AutoSyncOutcome
    AutoSyncPostCommit
    AutoSyncPublicationAuthority
    AutoSyncPostCommitDispatcher

com.mica.music.data.library.autosync.device
    DeviceAutoSyncPipeline

com.mica.music.data.library.autosync.saf
    SafAutoSyncPipeline
```

Existing pure planners stay separate unless a later audit proves they are duplicates. Do not merge DEVICE and SAF planners for file-count reduction.

## 5. Migration stages

> Status (2026-09-11): R0-R7 complete. Final structural regression: 35 suites / 343 tests, 0 failures / 0 errors.
> Production `LibraryScanOrchestrator` has been retired; `LibraryOperationExecutor`, `LibraryOperationAuthority`, and `LibraryPublicationAuthority` are now the active ownership boundaries.

### R0 - Behavior freeze ✅

- Run the existing auto-sync/scheduler/repository/migration/queue test set.
- Record this plan.
- No production code changes.

Gate: baseline green.

### R1 - Extract auto publication authority ✅

Move the AUTO publication bridge out of `LibraryScanOrchestrator` behind `AutoSyncPublicationAuthority`.

Initial implementation may delegate to existing `MusicLibraryBacking` commit gates. The purpose of R1 is ownership and call direction, not rewriting atomic commit code.

Must remain unchanged:

- final token validation;
- staging cleanup;
- checkpoint-only path;
- visible delta/snapshot publication;
- rebase behavior;
- Room -> memory adopt ordering;
- `LibraryChangeSet` semantics.

Gate: publication/readiness/orchestrator tests green.

### R2 - Extract DEVICE pipeline ✅

Move DEVICE observation -> candidate -> membership -> probe -> retry -> publication-plan construction into `DeviceAutoSyncPipeline`.

The pipeline returns a result value. During the first mechanical extraction it may still expose post-commit requests explicitly; it must not gain new behavior.

Gate: all DEVICE shadow/readiness/canonical/retry tests green.

### R3 - Extract SAF pipeline ✅

Move SAF inventory/fast-verify/canonical/debt/probe/retry/publication-plan construction into `SafAutoSyncPipeline`.

Gate: all SAF shadow/readiness/provider-recovery/backoff tests green.

### R4 - Remove pipeline -> scheduler callbacks ✅

Introduce `AutoSyncOutcome` and `AutoSyncPostCommit` so pipelines describe:

- retry wake request;
- continuation request;
- dirty follow-up request;
- artwork hydration targets;
- stale/deferred/no-content/publish outcome.

`AutoSyncPostCommitDispatcher` is the only AUTO execution component allowed to translate these results back into scheduler actions.

Gate: `LibraryScanOrchestrator`/new executors contain zero direct scheduler calls from DEVICE/SAF pipeline logic.

### R5 - Retire `LibraryScanOrchestrator` ✅

Extract:

- `FullLibraryScanExecutor`;
- `TargetedMetadataRefreshExecutor`;
- `ArtworkRepairExecutor`.

Replace the old orchestrator with a small `LibraryOperationExecutor` dispatcher.

Target: dispatcher under roughly 150 lines.

Gate: full scan, targeted refresh, artwork maintenance and source-switch tests green.

### R6 - Split backing authority ✅

Only after R1-R5 are stable, extract from `MusicLibraryBacking`:

- `LibraryOperationAuthority`: generation/source activation/token/lifecycle invalidation;
- `LibraryPublicationAuthority`: publication mutex/store revision/final commit/adopt gates.

`MusicLibraryBacking` remains runtime state + component wiring.

Gate: cancellation/release/clear/source-switch atomicity tests green.

### R7 - Layer cleanup ✅

- Move retry key construction out of planner classes so `LibraryRepository` does not depend on planners.
- Normalize package names and diagnostics categories.
- Remove compatibility seams made obsolete by R1-R6.
- Update ADR/docs with the final ownership diagram.

## 6. Stage rules

Every stage is a separate commit.

Before each stage:

- working tree changes unrelated to auto-sync stay unstaged;
- run the smallest relevant baseline test slice.

After each stage:

1. `git diff --check`;
2. targeted unit tests for the moved boundary;
3. `:app:compileDebugKotlin`;
4. inspect dependency direction with repository search;
5. only then commit.

If a mechanical extraction requires changing an algorithm to make the code fit, stop the extraction and redesign the boundary instead.

## 7. Success criteria

- `LibraryScanOrchestrator` removed.
- `LibraryOperationExecutor` is a small dispatcher.
- DEVICE and SAF discovery remain independent.
- AUTO publication has one structural authority entry point.
- Pipeline -> scheduler direct calls: zero.
- `LibraryRepository` -> retry planner dependencies: zero.
- Scheduler remains the single launch owner.
- No regression in existing destructive-safety, token, retry, provider recovery, publication atomicity or playback reconciliation tests.

## 8. Completion checkpoint

Completed on 2026-09-11 with the frozen behavior preserved.

- `LibraryScanOrchestrator`: removed from production and test naming.
- `LibraryOperationExecutor`: operation dispatcher; source-specific work is delegated to dedicated executors/pipelines.
- `MusicLibraryBacking`: reduced from ~899 lines to ~457 lines after operation/publication authority extraction.
- DEVICE and SAF pipelines do not call `LibrarySyncScheduler` directly; follow-up requests flow through `AutoSyncPostCommit`.
- `LibraryRepository` no longer depends on DEVICE/SAF retry planners. Retry-key construction is centralized in `LibraryRetryKey`.
- Publication/store revision locks are owned by `LibraryPublicationAuthority`.
- Final auto-sync regression: 35 suites / 343 tests / 0 failures / 0 errors.

A later scheduler cleanup may extract only pure wake-time calculations. Job ownership, locking, pending-state transitions, fairness, and launch ordering remain owned by `LibrarySyncScheduler`.
