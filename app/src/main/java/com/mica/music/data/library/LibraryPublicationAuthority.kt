package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.scanner.AutoSyncPublicationDecision
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.publicationDecision
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns snapshot publication revisioning and final Room -> memory commit gates. */
internal class LibraryPublicationAuthority(
    private val backing: MusicLibraryBacking,
) {
    internal val publicationMutex = Mutex()
    private val storeSyncMutex = Mutex()
    private val latestStoreRevision = AtomicLong(0L)

    fun nextStoreRevision(): Long = latestStoreRevision.incrementAndGet()

    fun isLatestStoreRevision(revision: Long): Boolean =
        revision == latestStoreRevision.get()

    suspend fun <T : Any> snapshotStoreWriteIfCurrent(
        generation: Int,
        block: suspend () -> T,
    ): T? {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!backing.isActiveGeneration(generation)) return@withLock null
            if (!isLatestStoreRevision(storeRevision)) return@withLock null
            withContext(backing.ioDispatcher) { block() }
        }
    }

    suspend fun <T : Any> commitSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T) -> Unit,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false) return@withLock null
                if (!backing.isCurrentOperationToken(token)) return@withLock null
                if (
                    expectedCatalogRevision != null &&
                    backing.catalogRevision != expectedCatalogRevision
                ) return@withLock null
                if (
                    expectedPresentationRevision != null &&
                    backing.presentationRevision != expectedPresentationRevision
                ) return@withLock null

                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        null
                    } else {
                        val result = withContext(backing.ioDispatcher) { storeBlock() }
                        publishBlock(result)
                        result
                    }
                }
            }
        }
    }

    suspend fun <T : Any> commitAutoSyncSnapshotAndPublishIfCurrent(
        token: LibraryOperationToken,
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        changeSetForRevision: (Long) -> LibraryChangeSet,
        storeBlock: suspend (LibraryChangeSet) -> T,
        publishBlock: (T, LibraryChangeSet) -> Unit,
    ): T? {
        require(token.mode == LibraryOperationMode.AUTO_SYNC) {
            "Visible AUTO publication requires an AUTO_SYNC operation token"
        }
        val waitStartedNs = SystemClock.elapsedRealtimeNanos()
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                val gateAcquiredNs = SystemClock.elapsedRealtimeNanos()
                if (callerJob?.isActive == false) return@withLock null
                if (!backing.isCurrentOperationToken(token)) return@withLock null
                if (
                    expectedCatalogRevision != null &&
                    backing.catalogRevision != expectedCatalogRevision
                ) return@withLock null
                if (
                    expectedPresentationRevision != null &&
                    backing.presentationRevision != expectedPresentationRevision
                ) return@withLock null

                val revision = backing.libraryChangeRevision + 1L
                val changeSet = changeSetForRevision(revision)
                require(changeSet.libraryRevision == revision)
                require(changeSet.cause == token.cause)
                require(
                    changeSet.addedIds.isNotEmpty() ||
                        changeSet.updatedIds.isNotEmpty() ||
                        changeSet.membershipChanges.isNotEmpty(),
                ) {
                    "No-change AUTO operations must use checkpoint-only publication"
                }
                require(
                    changeSet.membershipChanges.all { it.sourceIdentity == token.sourceIdentity },
                ) {
                    "AUTO membership evidence must belong to the operation source"
                }

                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) {
                        null
                    } else {
                        val storeStartedNs = SystemClock.elapsedRealtimeNanos()
                        val result = withContext(backing.ioDispatcher) { storeBlock(changeSet) }
                        val storeFinishedNs = SystemClock.elapsedRealtimeNanos()
                        val memoryAdoptStartedNs = storeFinishedNs
                        publishBlock(result, changeSet)
                        backing.libraryChangeRevision = revision
                        backing.lastLibraryChangeSet = changeSet
                        val finishedNs = SystemClock.elapsedRealtimeNanos()
                        backing.recordAutoPublicationTiming(
                            MusicLibraryBacking.AutoPublicationTiming(
                                waitMs = nanosToMillis(gateAcquiredNs - waitStartedNs),
                                holdMs = nanosToMillis(finishedNs - gateAcquiredNs),
                                storeMs = nanosToMillis(storeFinishedNs - storeStartedNs),
                                memoryAdoptMs = nanosToMillis(finishedNs - memoryAdoptStartedNs),
                            ),
                        )
                        result
                    }
                }
            }
        }
    }

    suspend fun <T> withPublicationGenerationIfCurrent(
        generation: Int,
        block: () -> T,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || !backing.isActiveGeneration(generation)) null else block()
            }
        }
    }

    suspend fun <T> withCurrentOperationIfCurrent(
        token: LibraryOperationToken,
        block: () -> T,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || !backing.isCurrentOperationToken(token)) null else block()
            }
        }
    }

    suspend fun <T> withCurrentCatalogPublication(
        expectedCatalogRevision: Long,
        block: suspend () -> T,
    ): T? = publicationMutex.withLock {
        if (backing.released || backing.catalogRevision != expectedCatalogRevision) return@withLock null
        block()
    }

    suspend fun storeWriteIfCurrentCatalog(
        expectedCatalogRevision: Long,
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (backing.released || backing.catalogRevision != expectedCatalogRevision || !isCurrent() || !isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(backing.ioDispatcher) { block() }
            !backing.released && backing.catalogRevision == expectedCatalogRevision && isCurrent() && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun storeWriteIfCurrentGeneration(
        expectedGeneration: Int,
        isCurrent: () -> Boolean = { true },
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (!backing.isActiveGeneration(expectedGeneration) || !isCurrent() || !isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(backing.ioDispatcher) { block() }
            backing.isActiveGeneration(expectedGeneration) && isCurrent() && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun storeWriteIfCurrentObjectState(
        isCurrent: () -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = publicationMutex.withLock {
        val storeRevision = nextStoreRevision()
        storeSyncMutex.withLock {
            if (backing.released || backing.releaseRequested || !isCurrent() || !isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(backing.ioDispatcher) { block() }
            !backing.released && !backing.releaseRequested && isCurrent() && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun commitAutoSyncStateIfCurrent(
        token: LibraryOperationToken,
        mutation: LibraryAutoSyncStateMutation,
    ): Boolean {
        require(mutation.sourceIdentity == token.sourceIdentity)
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || !backing.isCurrentOperationToken(token)) return@withLock false
                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) false else {
                        withContext(backing.ioDispatcher) { backing.libraryStore.applyAutoSyncState(mutation) }
                        true
                    }
                }
            }
        }
    }

    suspend fun commitAutoSyncCheckpointOnlyIfCurrent(
        token: LibraryOperationToken,
        visibleDelta: AutoSyncVisibleDelta,
        mutation: LibraryAutoSyncStateMutation,
    ): Boolean {
        require(token.mode == LibraryOperationMode.AUTO_SYNC) { "Checkpoint-only auto state writes require an AUTO_SYNC operation token" }
        require(visibleDelta.publicationDecision() == AutoSyncPublicationDecision.CHECKPOINT_ONLY) { "Visible AUTO changes must use snapshot publication" }
        return commitAutoSyncStateIfCurrent(token, mutation)
    }

    suspend fun storeWriteIfCurrentOperation(
        token: LibraryOperationToken,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!backing.isCurrentOperationToken(token) || !isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(backing.ioDispatcher) { block() }
            backing.isCurrentOperationToken(token) && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun storeWriteIfCurrent(
        generation: Int,
        block: suspend () -> Unit,
    ): Boolean {
        val storeRevision = nextStoreRevision()
        return storeSyncMutex.withLock {
            if (!backing.isActiveGeneration(generation) || !isLatestStoreRevision(storeRevision)) return@withLock false
            withContext(backing.ioDispatcher) { block() }
            backing.isActiveGeneration(generation) && isLatestStoreRevision(storeRevision)
        }
    }

    suspend fun discardOperationStaging(stagingId: String) {
        if (stagingId.isBlank()) return
        withContext(NonCancellable) {
            storeSyncMutex.withLock {
                withContext(backing.ioDispatcher) {
                    backing.libraryStore.discardStagedLyrics(stagingId)
                }
            }
        }
    }

    suspend fun updateAccessState(state: LibraryAccessState) {
        publicationMutex.withLock {
            if (backing.released || backing.releaseRequested) return@withLock
            if (backing.accessState == state) return@withLock
            backing.accessState = state
            backing.syncScheduler.onEligibilityChanged()
            val snapshot = backing.persistedState()
            val storeRevision = nextStoreRevision()
            storeSyncMutex.withLock {
                if (!isLatestStoreRevision(storeRevision)) return@withLock
                withContext(backing.ioDispatcher) {
                    backing.libraryStore.saveLibraryState(snapshot)
                }
            }
        }
    }

    suspend fun <T : Any> replaceSnapshotAuthority(
        expectedCatalogRevision: Long? = null,
        expectedPresentationRevision: Long? = null,
        expectedSourceIdentity: SourceIdentityKey? = null,
        storeBlock: suspend () -> T,
        publishBlock: (T, Int) -> Unit,
    ): T? {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            publicationMutex.withLock {
                if (callerJob?.isActive == false || backing.released || backing.releaseRequested) return@withLock null
                if (expectedCatalogRevision != null && backing.catalogRevision != expectedCatalogRevision) return@withLock null
                if (expectedPresentationRevision != null && backing.presentationRevision != expectedPresentationRevision) return@withLock null
                if (expectedSourceIdentity != null && backing.sourceState.active?.sourceIdentity != expectedSourceIdentity) return@withLock null
                val generation = ++backing.scanGeneration
                val storeRevision = nextStoreRevision()
                storeSyncMutex.withLock {
                    if (!isLatestStoreRevision(storeRevision)) null else {
                        val result = withContext(backing.ioDispatcher) { storeBlock() }
                        publishBlock(result, generation)
                        result
                    }
                }
            }
        }
    }

    private fun nanosToMillis(nanos: Long): Double =
        nanos.coerceAtLeast(0L) / 1_000_000.0
}