package com.mica.music.data.library

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns snapshot publication revisioning and final Room -> memory commit gates. */
internal class LibraryPublicationAuthority(
    private val backing: MusicLibraryBacking,
) {
    private val latestStoreRevision = AtomicLong(0L)

    fun nextStoreRevision(): Long = latestStoreRevision.incrementAndGet()

    fun isLatestStoreRevision(revision: Long): Boolean =
        revision == latestStoreRevision.get()

    suspend fun <T : Any> snapshotStoreWriteIfCurrent(
        generation: Int,
        block: suspend () -> T,
    ): T? {
        val storeRevision = nextStoreRevision()
        return backing.storeSyncMutex.withLock {
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
            backing.publicationMutex.withLock {
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
                backing.storeSyncMutex.withLock {
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
            backing.publicationMutex.withLock {
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
                backing.storeSyncMutex.withLock {
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

    private fun nanosToMillis(nanos: Long): Double =
        nanos.coerceAtLeast(0L) / 1_000_000.0
}