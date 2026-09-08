package com.mica.music

import com.mica.music.data.Song
import com.mica.music.data.library.LibraryChangeSet
import com.mica.music.data.library.LibraryOperationCause
import com.mica.music.data.library.MembershipRemovalReason
import com.mica.music.data.library.isAutoSyncCause

internal sealed class LibraryQueueSyncPlan {
    data object SkipEmpty : LibraryQueueSyncPlan()
    data object BootstrapOnly : LibraryQueueSyncPlan()
    data class BootstrapOrSetQueue(
        val songs: List<Song>,
        val previousLibraryIdsSize: Int,
        val currentQueueWasLibrary: Boolean,
    ) : LibraryQueueSyncPlan()
    data class SetQueue(
        val songs: List<Song>,
        val previousLibraryIdsSize: Int,
        val currentQueueWasLibrary: Boolean,
    ) : LibraryQueueSyncPlan()
    data class ReconcileQueue(
        val removeIds: Set<String>,
        val songs: List<Song>,
        val preservedCurrentOrphanId: String?,
        val previousLibraryIdsSize: Int,
        val currentQueueWasLibrary: Boolean,
    ) : LibraryQueueSyncPlan()
    data class RefreshMetadata(
        val songs: List<Song>,
        val previousLibraryIdsSize: Int,
        val currentQueueWasLibrary: Boolean,
    ) : LibraryQueueSyncPlan()
}

internal class LibraryQueueSyncPolicy {
    private var previousLibraryIds: List<String> = emptyList()
    private var pendingCurrentOrphanId: String? = null

    fun plan(
        songs: List<Song>,
        libraryIds: List<String>,
        currentQueueIds: List<String>,
        changeSet: LibraryChangeSet? = null,
        currentSongId: String? = null,
        isPlaying: Boolean = false,
    ): LibraryQueueSyncPlan {
        val previousIds = previousLibraryIds
        val currentQueueWasLibrary = previousIds.isNotEmpty() && currentQueueIds == previousIds

        val evidenceRemovedIds = changeSet
            ?.membershipChanges
            ?.asSequence()
            ?.filter { it.reason.removesFromVisibleLibrary() }
            ?.mapNotNull { it.songId }
            ?.toSet()
            .orEmpty()

        if (changeSet?.cause == LibraryOperationCause.LOCAL_USER_DELETE) {
            previousLibraryIds = libraryIds
            return when {
                currentQueueIds.isEmpty() && songs.isEmpty() -> LibraryQueueSyncPlan.SkipEmpty
                currentQueueIds.isEmpty() -> LibraryQueueSyncPlan.BootstrapOrSetQueue(
                    songs = songs,
                    previousLibraryIdsSize = previousIds.size,
                    currentQueueWasLibrary = currentQueueWasLibrary,
                )
                else -> LibraryQueueSyncPlan.RefreshMetadata(
                    songs = songs,
                    previousLibraryIdsSize = previousIds.size,
                    currentQueueWasLibrary = currentQueueWasLibrary,
                )
            }
        }

        if (changeSet?.cause?.isAutoSyncCause() == true && evidenceRemovedIds.isNotEmpty()) {
            val currentOrphan = currentSongId?.takeIf { currentId ->
                isPlaying &&
                    currentId in evidenceRemovedIds &&
                    currentId in currentQueueIds
            }
            if (currentOrphan != null) pendingCurrentOrphanId = currentOrphan
            val removeIds = evidenceRemovedIds
                .asSequence()
                .filter { it in currentQueueIds && it != currentOrphan }
                .toSet()
            previousLibraryIds = libraryIds
            return LibraryQueueSyncPlan.ReconcileQueue(
                removeIds = removeIds,
                songs = songs,
                preservedCurrentOrphanId = currentOrphan,
                previousLibraryIdsSize = previousIds.size,
                currentQueueWasLibrary = currentQueueWasLibrary,
            )
        }

        if (songs.isEmpty()) {
            return if (currentQueueIds.isEmpty()) {
                LibraryQueueSyncPlan.BootstrapOnly
            } else {
                LibraryQueueSyncPlan.SkipEmpty
            }
        }

        val previousIdSet = previousIds.toSet()
        val libraryIdSet = libraryIds.toSet()
        val currentQueueHasRemovedLibrarySongs = previousIds != libraryIds &&
            currentQueueIds.any { it in previousIdSet && it !in libraryIdSet }
        previousLibraryIds = libraryIds
        return when {
            currentQueueIds.isEmpty() -> LibraryQueueSyncPlan.BootstrapOrSetQueue(
                songs = songs,
                previousLibraryIdsSize = previousIds.size,
                currentQueueWasLibrary = currentQueueWasLibrary,
            )
            currentQueueHasRemovedLibrarySongs -> LibraryQueueSyncPlan.SetQueue(
                songs = songs,
                previousLibraryIdsSize = previousIds.size,
                currentQueueWasLibrary = currentQueueWasLibrary,
            )
            else -> LibraryQueueSyncPlan.RefreshMetadata(
                songs = songs,
                previousLibraryIdsSize = previousIds.size,
                currentQueueWasLibrary = currentQueueWasLibrary,
            )
        }
    }

    /**
     * Returns a previously preserved playing orphan once playback has naturally moved away from it.
     */
    fun orphanToPurgeAfterPlaybackTransition(
        currentSongId: String?,
        currentQueueIds: List<String>,
    ): String? {
        val orphan = pendingCurrentOrphanId ?: return null
        if (orphan !in currentQueueIds) {
            pendingCurrentOrphanId = null
            return null
        }
        if (currentSongId == orphan) return null
        pendingCurrentOrphanId = null
        return orphan
    }
}

private fun MembershipRemovalReason.removesFromVisibleLibrary(): Boolean =
    when (this) {
        MembershipRemovalReason.CONFIRMED_MISSING,
        MembershipRemovalReason.FILTERED_OUT,
        MembershipRemovalReason.TRASHED,
        MembershipRemovalReason.SOURCE_REPLACED,
        MembershipRemovalReason.USER_EXCLUDED,
        -> true
        MembershipRemovalReason.UNAVAILABLE -> false
    }
