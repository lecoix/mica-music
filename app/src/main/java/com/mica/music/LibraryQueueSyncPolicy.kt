package com.mica.music

import com.mica.music.data.Song

internal sealed class LibraryQueueSyncPlan {
    data object SkipEmpty : LibraryQueueSyncPlan()
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
    data class RefreshMetadata(
        val songs: List<Song>,
        val previousLibraryIdsSize: Int,
        val currentQueueWasLibrary: Boolean,
    ) : LibraryQueueSyncPlan()
}

internal class LibraryQueueSyncPolicy {
    private var previousLibraryIds: List<String> = emptyList()

    fun plan(
        songs: List<Song>,
        libraryIds: List<String>,
        currentQueueIds: List<String>,
    ): LibraryQueueSyncPlan {
        if (songs.isEmpty()) return LibraryQueueSyncPlan.SkipEmpty
        val previousIds = previousLibraryIds
        val currentQueueWasLibrary = previousIds.isNotEmpty() && currentQueueIds == previousIds
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
            currentQueueWasLibrary || currentQueueHasRemovedLibrarySongs -> LibraryQueueSyncPlan.SetQueue(
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
}
