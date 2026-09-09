package com.mica.music.data.library

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.PlayStats
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.scanner.ScanResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibrarySyncSchedulerTest {

    @Test
    fun continuationGetsTwoTurnsThenArtworkGetsBoundedTurn() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<LibraryOperationRequest>()
        val gate = CompletableDeferred<Unit>()
        lateinit var scheduler: LibrarySyncScheduler
        scheduler = testSchedulerOwner(backing, LibrarySyncSchedulerTiming(0, 0, 0)) { op ->
            executed += op.request
            if (executed.size == 1) gate.await()
            if (op.request is LibraryOperationRequest.AutoSync && executed.size < 6) {
                scheduler.requestAutoContinuation(LibraryOperationCause.SAF_BUDGET_CONTINUATION)
            }
        }
        scheduler.markDirty()
        runCurrent()
        scheduler.submit(LibraryOperationRequest.TargetedRefresh(
            (1..100).mapTo(linkedSetOf()) { "art-$it" }, LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
        ))
        gate.complete(Unit)
        runCurrent()
        assertTrue(executed.take(3).all { it is LibraryOperationRequest.AutoSync })
        val firstArtwork = executed[3] as LibraryOperationRequest.TargetedRefresh
        assertEquals(32, firstArtwork.songIds.size)
        val allArtwork = executed.filterIsInstance<LibraryOperationRequest.TargetedRefresh>()
        assertTrue(allArtwork.all { it.songIds.size <= 32 })
        assertEquals(100, allArtwork.flatMap { it.songIds }.toSet().size)
        backing.release()
        runCurrent()
    }

    @Test
    fun userRefreshOutranksContinuationWithoutPromotingUnrelatedArtwork() = runTest {
        val backing = activeBacking()
        val gate = CompletableDeferred<Unit>()
        val executed = mutableListOf<LibraryOperationRequest>()
        val scheduler = testSchedulerOwner(backing, LibrarySyncSchedulerTiming(0, 0, 0)) {
            executed += it.request
            if (executed.size == 1) gate.await()
        }
        scheduler.markDirty()
        runCurrent()
        scheduler.submit(LibraryOperationRequest.TargetedRefresh(setOf("art"), LibraryOperationCause.AUTO_ARTWORK_HYDRATE))
        scheduler.submit(LibraryOperationRequest.TargetedRefresh(setOf("user"), LibraryOperationCause.TAG_EDITOR_RETURN))
        scheduler.requestAutoContinuation(LibraryOperationCause.SAF_BUDGET_CONTINUATION)
        gate.complete(Unit)
        runCurrent()
        assertEquals(LibraryOperationRequest.TargetedRefresh(setOf("user"), LibraryOperationCause.TAG_EDITOR_RETURN), executed[1])
        assertTrue(executed[2] is LibraryOperationRequest.AutoSync)
        assertEquals(setOf("art"), (executed[3] as LibraryOperationRequest.TargetedRefresh).songIds)
        backing.release()
        runCurrent()
    }

    @Test
    fun dirtyUsesTrailingDebounceBeforeStartingAutoSync() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 1_500L,
                cooldownMs = 60_000L,
                maxDebounceMs = 5_000L,
            ),
        ) { executed += it }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        runCurrent()
        assertTrue(executed.isEmpty())

        advanceTimeBy(1_499L)
        runCurrent()
        assertTrue(executed.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, executed.size)
        assertTrue(executed.single().request is LibraryOperationRequest.AutoSync)
        assertEquals(1L, executed.single().dirtySequenceAtStart)
        assertEquals(
            AutoSyncShadowDiagnostic(
                requestSequence = 1L,
                dirtySequenceAtStart = 1L,
                coalescedEventCount = 1,
                causeCounts = mapOf(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY to 1),
                wakeReason = AutoSyncWakeReason.TRAILING_DEBOUNCE,
            ),
            scheduler.lastAutoShadowDiagnostic,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }


    @Test
    fun autoArtworkHydrateTargetedRefreshRetainsCauseAndCoalescesIds() = runTest {
        val backing = activeBacking()
        val firstGate = CompletableDeferred<Unit>()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(backing = backing) { operation ->
            executed += operation
            if (executed.size == 1) firstGate.await()
        }

        scheduler.submit(LibraryOperationRequest.ScanLibraryFolder)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.submit(
            LibraryOperationRequest.TargetedRefresh(
                songIds = setOf("auto-art", ""),
                cause = LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
            ),
        )
        scheduler.submit(
            LibraryOperationRequest.TargetedRefresh(
                songIds = setOf("tag-edit"),
                cause = LibraryOperationCause.TAG_EDITOR_RETURN,
            ),
        )
        runCurrent()
        assertEquals(1, executed.size)

        firstGate.complete(Unit)
        runCurrent()

        assertEquals(3, executed.size)
        val targeted = executed[1].request as LibraryOperationRequest.TargetedRefresh
        assertEquals(setOf("tag-edit"), targeted.songIds)
        assertEquals(LibraryOperationCause.TAG_EDITOR_RETURN, targeted.cause)
        assertEquals(setOf("auto-art"), (executed[2].request as LibraryOperationRequest.TargetedRefresh).songIds)

        backing.release()
        runCurrent()
    }

    @Test
    fun continuousDirtyCannotStarvePastMaxDebounceDeadline() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 1_000L,
                cooldownMs = 0L,
                maxDebounceMs = 3_000L,
            ),
        ) { executed += it }

        scheduler.markDirty()
        repeat(3) {
            advanceTimeBy(900L)
            runCurrent()
            scheduler.markDirty()
        }
        assertTrue(executed.isEmpty())

        advanceTimeBy(299L)
        runCurrent()
        assertTrue(executed.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, executed.size)
        assertEquals(4L, executed.single().dirtySequenceAtStart)
        assertEquals(4, scheduler.lastAutoShadowDiagnostic?.coalescedEventCount)
        assertEquals(AutoSyncWakeReason.MAX_DEBOUNCE, scheduler.lastAutoShadowDiagnostic?.wakeReason)

        backing.release()
        runCurrent()
    }

    @Test
    fun foregroundCatchUpDuringCooldownWakesAtCooldownEndInsteadOfBeingDropped() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 1_500L,
                cooldownMs = 60_000L,
                maxDebounceMs = 5_000L,
            ),
        ) { executed += it }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(1, executed.size)
        assertTrue(scheduler.pendingDirty)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, executed.size)
        assertTrue(executed[1].request is LibraryOperationRequest.AutoSync)
        assertEquals(AutoSyncWakeReason.COOLDOWN, scheduler.lastAutoShadowDiagnostic?.wakeReason)
        assertEquals(
            mapOf(LibraryOperationCause.FOREGROUND_CATCH_UP to 1),
            scheduler.lastAutoShadowDiagnostic?.causeCounts,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }


    @Test
    fun mediaStoreDirtyBypassesCooldownButStillUsesTrailingDebounce() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 1_500L,
                cooldownMs = 60_000L,
                maxDebounceMs = 5_000L,
            ),
        ) { executed += it }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(1, executed.size)
        assertTrue(scheduler.pendingDirty)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(
            LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            executed[1].request.cause,
        )
        assertEquals(
            AutoSyncWakeReason.TRAILING_DEBOUNCE,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertEquals(
            mapOf(LibraryOperationCause.MEDIASTORE_FILES_DIRTY to 1),
            scheduler.lastAutoShadowDiagnostic?.causeCounts,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun realDirtyPromotesThrottledCatchUpWithFreshDebounceWindow() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 1_500L,
                cooldownMs = 60_000L,
                maxDebounceMs = 5_000L,
            ),
        ) { executed += it }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, executed.size)
        assertTrue(scheduler.pendingDirty)

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
        advanceTimeBy(1_499L)
        runCurrent()
        assertEquals(1, executed.size)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(
            AutoSyncWakeReason.TRAILING_DEBOUNCE,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertEquals(
            mapOf(
                LibraryOperationCause.FOREGROUND_CATCH_UP to 1,
                LibraryOperationCause.MEDIASTORE_FILES_DIRTY to 1,
            ),
            scheduler.lastAutoShadowDiagnostic?.causeCounts,
        )
        assertEquals(
            LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            executed[1].request.cause,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun dirtyDuringAutoDoesNotCancelPassAndRunsImmediateFollowUp() = runTest {
        val backing = activeBacking()
        val firstPassGate = CompletableDeferred<Unit>()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 60_000L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            if (executed.size == 1) firstPassGate.await()
        }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(1, executed.size)
        val firstJob = backing.scanJob
        assertTrue(firstJob?.isActive == true)

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
        runCurrent()

        assertEquals(1, executed.size)
        assertTrue(firstJob?.isActive == true)
        assertTrue(scheduler.pendingDirty)

        firstPassGate.complete(Unit)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(1L, executed[0].dirtySequenceAtStart)
        assertEquals(2L, executed[1].dirtySequenceAtStart)
        assertEquals(
            LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            executed[1].request.cause,
        )
        assertEquals(
            AutoSyncWakeReason.IN_PASS_FOLLOW_UP,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertEquals(1, scheduler.lastAutoShadowDiagnostic?.coalescedEventCount)
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun schedulerOwnedBudgetContinuationRunsAsAutoFollowUpWithoutExternalObserver() = runTest {
        val backing = activeBacking()
        val firstPassGate = CompletableDeferred<Unit>()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 60_000L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            if (executed.size == 1) firstPassGate.await()
        }

        assertFalse(
            scheduler.requestAutoContinuation(
                LibraryOperationCause.SAF_BUDGET_CONTINUATION,
            ),
        )

        scheduler.markDirty(LibraryOperationCause.SAF_PERIODIC_VERIFY)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, executed.size)

        assertTrue(
            scheduler.requestAutoContinuation(
                LibraryOperationCause.SAF_BUDGET_CONTINUATION,
            ),
        )
        assertTrue(scheduler.pendingDirty)

        firstPassGate.complete(Unit)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(
            LibraryOperationCause.SAF_BUDGET_CONTINUATION,
            executed[1].request.cause,
        )
        assertEquals(
            AutoSyncWakeReason.IN_PASS_FOLLOW_UP,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun schedulerOwnedRetryWakeRunsAtDeadlineWithoutExternalDirty() = runTest {
        val backing = activeBacking().also { it.isAutoSyncForeground = true }
        val executed = mutableListOf<ScheduledLibraryOperation>()
        lateinit var scheduler: LibrarySyncScheduler
        scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 60_000L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            if (executed.size == 1) {
                val active = requireNotNull(backing.sourceState.active)
                assertTrue(
                    scheduler.replaceAutoRetryWake(
                        cause = LibraryOperationCause.SAF_RETRY_DUE,
                        delayMs = 500L,
                        sourceIdentity = active.sourceIdentity,
                        activationEpoch = active.activationEpoch,
                    ),
                )
            }
        }

        scheduler.markDirty(LibraryOperationCause.SAF_PERIODIC_VERIFY)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, executed.size)

        advanceTimeBy(499L)
        runCurrent()
        assertEquals(1, executed.size)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(LibraryOperationCause.SAF_RETRY_DUE, executed[1].request.cause)
        assertEquals(AutoSyncWakeReason.RETRY_DUE, scheduler.lastAutoShadowDiagnostic?.wakeReason)
        assertEquals(2L, scheduler.dirtySequence)
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun schedulerOwnedRetryWakeDoesNotStartBackgroundIo() = runTest {
        val backing = activeBacking().also { it.isAutoSyncForeground = true }
        val executed = mutableListOf<ScheduledLibraryOperation>()
        lateinit var scheduler: LibrarySyncScheduler
        scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 0L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            if (executed.size == 1) {
                val active = requireNotNull(backing.sourceState.active)
                assertTrue(
                    scheduler.replaceAutoRetryWake(
                        cause = LibraryOperationCause.SAF_RETRY_DUE,
                        delayMs = 500L,
                        sourceIdentity = active.sourceIdentity,
                        activationEpoch = active.activationEpoch,
                    ),
                )
            }
        }

        scheduler.markDirty(LibraryOperationCause.SAF_PERIODIC_VERIFY)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, executed.size)

        backing.isAutoSyncForeground = false
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, executed.size)
        assertEquals(1L, scheduler.dirtySequence)

        backing.isAutoSyncForeground = true
        scheduler.markDirty(LibraryOperationCause.FOREGROUND_CATCH_UP)
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(LibraryOperationCause.FOREGROUND_CATCH_UP, executed[1].request.cause)

        backing.release()
        runCurrent()
    }

    @Test
    fun cancelAllCancelsSchedulerOwnedRetryWake() = runTest {
        val backing = activeBacking().also { it.isAutoSyncForeground = true }
        val executed = mutableListOf<ScheduledLibraryOperation>()
        lateinit var scheduler: LibrarySyncScheduler
        scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 0L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            if (executed.size == 1) {
                val active = requireNotNull(backing.sourceState.active)
                assertTrue(
                    scheduler.replaceAutoRetryWake(
                        cause = LibraryOperationCause.SAF_RETRY_DUE,
                        delayMs = 500L,
                        sourceIdentity = active.sourceIdentity,
                        activationEpoch = active.activationEpoch,
                    ),
                )
            }
        }

        scheduler.markDirty(LibraryOperationCause.SAF_PERIODIC_VERIFY)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.cancelAll()
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(1, executed.size)
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun dirtyDuringImmediateFollowUpReentersDebounceInsteadOfHotLoop() = runTest {
        val backing = activeBacking()
        val firstPassGate = CompletableDeferred<Unit>()
        val followUpGate = CompletableDeferred<Unit>()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 60_000L,
                maxDebounceMs = 1_000L,
            ),
        ) { operation ->
            executed += operation
            when (executed.size) {
                1 -> firstPassGate.await()
                2 -> followUpGate.await()
            }
        }

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, executed.size)

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_FILES_DIRTY)
        firstPassGate.complete(Unit)
        runCurrent()

        assertEquals(2, executed.size)
        assertEquals(
            AutoSyncWakeReason.IN_PASS_FOLLOW_UP,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )

        scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        followUpGate.complete(Unit)
        runCurrent()

        assertEquals(2, executed.size)
        assertTrue(scheduler.pendingDirty)

        advanceTimeBy(99L)
        runCurrent()
        assertEquals(2, executed.size)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(3, executed.size)
        assertEquals(
            AutoSyncWakeReason.TRAILING_DEBOUNCE,
            scheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertEquals(
            mapOf(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY to 1),
            scheduler.lastAutoShadowDiagnostic?.causeCounts,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun thousandDirtySignalsCoalesceIntoSingleAutoPass() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(
            backing = backing,
            timing = LibrarySyncSchedulerTiming(
                debounceMs = 100L,
                cooldownMs = 0L,
                maxDebounceMs = 1_000L,
            ),
        ) { executed += it }

        repeat(1_000) {
            scheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        }
        runCurrent()
        assertTrue(executed.isEmpty())

        advanceTimeBy(100L)
        runCurrent()

        assertEquals(1, executed.size)
        assertEquals(1_000L, executed.single().dirtySequenceAtStart)
        assertEquals(1_000, scheduler.lastAutoShadowDiagnostic?.coalescedEventCount)
        assertEquals(
            mapOf(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY to 1_000),
            scheduler.lastAutoShadowDiagnostic?.causeCounts,
        )
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    @Test
    fun dirtyCannotAutoPopulateUninitializedOrUserClearedLibrary() = runTest {
        val backing = activeBacking()
        val executed = mutableListOf<ScheduledLibraryOperation>()
        val scheduler = testSchedulerOwner(backing = backing) { executed += it }

        backing.restorePersistedState(
            PersistedLibraryState(intent = LibraryIntentState.UNINITIALIZED),
        )
        scheduler.markDirty()
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(executed.isEmpty())
        assertFalse(scheduler.pendingDirty)

        backing.restorePersistedState(
            PersistedLibraryState(intent = LibraryIntentState.CLEARED_BY_USER),
        )
        scheduler.markDirty()
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(executed.isEmpty())
        assertFalse(scheduler.pendingDirty)

        backing.release()
        runCurrent()
    }

    private fun TestScope.testSchedulerOwner(
        backing: MusicLibraryBacking,
        timing: LibrarySyncSchedulerTiming = LibrarySyncSchedulerTiming(
            debounceMs = 100L,
            cooldownMs = 1_000L,
            maxDebounceMs = 1_000L,
        ),
        execute: suspend (ScheduledLibraryOperation) -> Unit,
    ): LibrarySyncScheduler = LibrarySyncScheduler(
        backing = backing,
        execute = execute,
        timing = timing,
        nowMs = { testScheduler.currentTime },
    )

    private fun TestScope.activeBacking(): MusicLibraryBacking {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MusicLibraryBacking(
            context = ApplicationProvider.getApplicationContext(),
            libraryScanner = NoopScanner,
            libraryStore = NoopStore,
            scanEnvironment = NoopEnvironment,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        ).also { backing ->
            backing.restorePersistedState(
                PersistedLibraryState(
                    intent = LibraryIntentState.ACTIVE,
                    access = LibraryAccessState.AVAILABLE,
                    sourceState = LibrarySourceState(
                        active = SourceActivation(SourceIdentityKey.device(), activationEpoch = 1L),
                    ),
                    configFingerprint = backing.configFingerprint,
                ),
            )
        }
    }

    private object NoopScanner : LibraryScanner {
        override suspend fun scanDevice(
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult = ScanResult(emptyList(), 0)

        override suspend fun scanFolder(
            treeUri: Uri,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult = ScanResult(emptyList(), 0)
    }

    private object NoopStore : LibraryStore {
        override suspend fun loadCached(): CachedLibrary? = null

        override suspend fun save(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult = LibrarySyncResult(0, 0, 0, songs.size)

        override suspend fun syncIncremental(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult = LibrarySyncResult(0, 0, 0, songs.size)

        override suspend fun clear() = Unit
    }

    private object NoopEnvironment : ScanEnvironment {
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean = true
        override fun currentTimeMillis(): Long = 0L
        override fun playStats(songId: String): PlayStats = PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun pruneAlbumArtCache(songs: List<Song>) = Unit
        override fun persistLastScanSource(source: ScanSource) = Unit
    }
}
