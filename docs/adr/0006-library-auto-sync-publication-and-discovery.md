# ADR-0006: Library Auto-Sync Publication and Discovery Protocol

- Status: Accepted
- Date: 2026-09-06
- Owners: Mica library/data + playback integration
- Extends: ADR-0002 Library Snapshot Publication
- Execution plan: `docs/LIBRARY_AUTO_SYNC_P_AND_P_EXECUTION_PLAN.md`

## Context

Mica has one authoritative local-library snapshot owned by
`MusicLibraryBacking -> LibraryScanOrchestrator -> LibraryStore`.

Automatic synchronization makes operations frequent and concurrent with playback, sorting, playlist
editing, source/permission changes, cache hydrate and explicit user scans. The existing manual-scan
path assumes the user intentionally started a heavyweight operation and therefore contains behavior
that is unsafe as a background default.

PixelPlayer/Poweramp are used only as discovery/scheduling references. They do not replace Mica's
snapshot authority or ADR-0002 commit order.

## Decision

### 1. One writer, one scheduler

All FULL, AUTO_SYNC, TARGETED_REFRESH and ARTWORK_REPAIR work is owned by
`MusicLibraryBacking` and scheduled through one `LibrarySyncScheduler`.

Observers introduced later only mark work dirty. They do not call scanners or write Room directly.

2026-09-10 clarification: automatic artwork hydration is queued separately from user-targeted
refreshes. Pending SAF budget continuations may take two turns ahead of automatic artwork, then
one bounded artwork batch (up to 32 targets) must run. User FULL, targeted refresh, and explicit
artwork repair retain priority. This is a fairness bound, not a measured latency guarantee.

AUTO publication applies the same minimum-duration predicate as FULL, including its unknown-duration
behavior. Rejected existing members use FILTERED_OUT, not physical absence evidence; this must not
enqueue playlist deletion. Source-local provider failure/slow-success cooldowns use monotonic elapsed
time, separately from persisted RetryLedger/checkpoint wall-clock timestamps.

### 2. Long operation execution and short publication are separate

Provider/file discovery and probe work is cancellable and serialized separately from final
publication.

Final publication is:

```text
pure preparation
-> final validation
-> bounded cancellation shield
-> Publication gate
-> one Room authority transaction
-> in-memory adopt/revision publish
-> release gate
```

Once final validation has linearized a commit, later Job cancellation, clear, release or source
replacement waits behind that short commit. Cancellation before final validation produces no
authority commit.

Room commit is the durable linearization point; restart restores that committed snapshot.

### 3. Preparation outside the publication gate is pure

Preparation may compute stats, sort/browse presentation and proposed derived writes, but may not
mutate Room, SharedPreferences, caches or Compose state.

Presentation mutations have a revision. A stale prepared result is discarded and boundedly
rebased; exhausting the retry budget preserves pending work instead of looping or silently losing it.

### 4. Source identity, activation and access are distinct

A long-lived source identity is separate from an activation epoch and from scan configuration.

User exclusions bind to the long-lived source/object identity. Operation tokens bind to the current
activation. Checkpoints also bind to configuration/provider version state.

Source replacement is two-phase: a pending source cannot alter the active snapshot/resources before
its first authoritative commit succeeds.

Temporary permission/storage loss does not mean user-cleared library.

### 5. Unknown is not absent

Discovery completeness is partition-aware. Partial/unavailable discovery may publish only facts it
actually proved.

Fields/relations use tri-state semantics: Unknown, Present(value), AbsentConfirmed. Unknown preserves
the current authoritative value.

Presence (object exists/observable) and Eligibility (object belongs in Mica under current filters)
are logically distinct. Filtered, trashed, pending, unavailable and confirmed-missing are different
membership outcomes.

### 6. Destructive absence requires evidence

A single successful-but-empty inventory cannot clear a previously non-empty library.

Mass-deletion protection quarantines inventory collapse and large unexplained removal batches.
Independently revalidated object-level missing evidence may still remove the final song and produce a
legitimate zero-song active library.

MEDIA_MOUNTED, MEDIA_SCANNER_FINISHED, permission restoration and foreground resume are dirty signals,
not deletion proof.

### 7. Checkpoint and retry are causally atomic

If a checkpoint advances past a failed object/partition, the corresponding retry/deferred record is
persisted in the same transaction. An unresolved retry is never dropped solely because a TTL expired.

UNKNOWN SAF fingerprints enter a budgeted periodic deep-verify path; they are not assumed unchanged
forever.

### 8. User exclusion is durable and restoration schedules rediscovery

A failed physical file deletion may still create USER_EXCLUDED according to existing product
semantics. FULL and AUTO honor that tombstone.

Restoring exclusions also supersedes stale exclusion follow-up and schedules targeted rediscovery or
source reconciliation. In CLEARED_BY_USER state, restoring exclusion does not automatically rebuild
the library.

### 9. Cross-owner durable effects use an outbox

`LibraryChangeSet` is transient process-local information. Persistent playlist cleanup/resource GC
is recorded durably in the same MicaDatabase authority transaction.

Consumer validation + playlist mutation + ack is one coordinated database transaction with fixed
lock order. Relevant playlist mutations are suspend-based; the main thread must not runBlocking while
waiting for publication/playlist/resource locks.

### 10. Playback is not rebuilt by AUTO

AUTO never fills an empty playback queue from the whole library and never fixes local membership
changes by `setQueue(allLibrarySongs)`.

Current audio source plus ReplayGain/loudness/applied gain are frozen for the active playback
instance. New values apply to the next playback instance.

Lyrics are different: immutable resource revisions guarantee cache/storage correctness, but normal
lyrics/sidecar updates may refresh the current playback/notification/desktop lyrics live without a
queue or Media3 timeline rebuild.

### 11. Derived resource revisions are content identities

Observed file/provider revision and derived resource revision are separate.

A lyrics resource revision is derived from canonical resource content plus schema/semantic inputs so
one revision can never mean two different payloads. Equivalent re-parses reuse the same revision.

Staging stores references to immutable resource rows rather than another full copy of lyrics JSON.

### 12. AUTO does not inherit manual-scan side effects

AUTO does not:

- publish manual scan snackbar/error fields;
- run global parser/lyrics maintenance;
- call scan-start global transient-cache clearing;
- run album-art prune or bulk poster prefetch;
- turn maintenance-only cache misses into whole-library probes;
- heavy-probe the currently playing object;
- touch remote-library authority.

When playback is active, real AUTO starts with conservative heavy-probe concurrency and must pass the
stage-specific playback/performance gate before being enabled.

### 13. Platform facts are capability-scoped

MediaStore projection columns do not prove row-inclusion semantics. Destructive conclusions require
verified API/query/permission/volume coverage.

MediaStore IDs are valid only inside a proven identity domain; database/version rebuilds invalidate
that domain and trigger conservative identity reconciliation.

Size/mtime equality is a practical fingerprint on reliable providers, not byte-level snapshot proof.

For foreground FOLDER authority on Android R+, MediaStore version/generation is also sampled every
3 seconds as a cheap missed-callback accelerator. A generation change only marks the existing
`MEDIASTORE_FILES_DIRTY` path and therefore still goes through scheduler debounce and authoritative
SAF verification; an unchanged generation performs no tree walk. The watcher stops outside foreground
FOLDER authority and does not replace the periodic SAF verify. In particular, generation movement is
not deletion evidence. The 2026-09-10 physical Gate showed an `IS_TRASHED` transition that advanced
`external_primary` generation while provider callbacks were not reliably sufficient, which is the
specific failure mode this accelerator covers.

### 14. First-version MV invalidation is directory-scoped

For SAF local video/MV changes, the first version recomputes the affected directory using the current
matcher, covering exact and normalized-name collisions. It does not introduce a fine-grained
dependency graph before profiling proves it necessary.

## Consequences

- S0 changes the synchronization/publication seam before any observer is registered.
- S1 removes destructive/manual-scan defaults that are unsafe in background operation.
- S2 is detector-only shadow mode.
- DEVICE and SAF real AUTO remain gated behind canonical-equivalence, 10k-library, disk/RSS,
  publication-gate and playback coexistence measurements.
- No stage may introduce another library writer or bypass ADR-0002/this ADR.

## Non-goals

This ADR does not define exact debounce milliseconds, mass-removal percentage, SAF verify interval,
probe concurrency while idle, retry backoff or performance thresholds. Those constants are frozen
from measurements at their specified stage gate before real AUTO is enabled.
