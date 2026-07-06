package com.mica.music

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueueSyncPolicyTest {
    @Test
    fun emptyLibraryIsSkippedWithoutChangingPreviousLibrary() {
        val policy = LibraryQueueSyncPolicy()

        assertTrue(
            policy.plan(
                songs = emptyList(),
                libraryIds = emptyList(),
                currentQueueIds = emptyList(),
            ) is LibraryQueueSyncPlan.SkipEmpty,
        )

        val songs = SongFixtures.queue(2)
        val plan = policy.plan(
            songs = songs,
            libraryIds = songs.map { it.id },
            currentQueueIds = emptyList(),
        )

        assertTrue(plan is LibraryQueueSyncPlan.BootstrapOrSetQueue)
        assertEquals(0, (plan as LibraryQueueSyncPlan.BootstrapOrSetQueue).previousLibraryIdsSize)
    }

    @Test
    fun emptyPlayerQueueBootstrapsOrSetsLibraryQueue() {
        val songs = SongFixtures.queue(2)
        val plan = LibraryQueueSyncPolicy().plan(
            songs = songs,
            libraryIds = songs.map { it.id },
            currentQueueIds = emptyList(),
        )

        assertTrue(plan is LibraryQueueSyncPlan.BootstrapOrSetQueue)
        assertEquals(songs, (plan as LibraryQueueSyncPlan.BootstrapOrSetQueue).songs)
    }

    @Test
    fun previousLibraryQueueIsReplacedWithNewLibraryQueue() {
        val policy = LibraryQueueSyncPolicy()
        val oldSongs = listOf(SongFixtures.song("old-a"), SongFixtures.song("old-b"))
        val newSongs = listOf(SongFixtures.song("new-a"), SongFixtures.song("new-b"))
        policy.plan(
            songs = oldSongs,
            libraryIds = oldSongs.map { it.id },
            currentQueueIds = emptyList(),
        )

        val plan = policy.plan(
            songs = newSongs,
            libraryIds = newSongs.map { it.id },
            currentQueueIds = oldSongs.map { it.id },
        )

        assertTrue(plan is LibraryQueueSyncPlan.SetQueue)
        assertEquals(newSongs, (plan as LibraryQueueSyncPlan.SetQueue).songs)
        assertEquals(2, plan.previousLibraryIdsSize)
        assertTrue(plan.currentQueueWasLibrary)
    }

    @Test
    fun unchangedLibraryQueueOnlyRefreshesMetadataAfterStartupSync() {
        val policy = LibraryQueueSyncPolicy()
        val songs = listOf(SongFixtures.song("lib-a"), SongFixtures.song("lib-b"))
        policy.plan(
            songs = songs,
            libraryIds = songs.map { it.id },
            currentQueueIds = emptyList(),
        )

        val plan = policy.plan(
            songs = songs,
            libraryIds = songs.map { it.id },
            currentQueueIds = songs.map { it.id },
        )

        assertTrue(plan is LibraryQueueSyncPlan.RefreshMetadata)
        assertEquals(songs, (plan as LibraryQueueSyncPlan.RefreshMetadata).songs)
    }

    @Test
    fun specialQueueOnlyRefreshesMatchingMetadata() {
        val policy = LibraryQueueSyncPolicy()
        val librarySongs = listOf(SongFixtures.song("lib-a"), SongFixtures.song("lib-b"))
        policy.plan(
            songs = librarySongs,
            libraryIds = librarySongs.map { it.id },
            currentQueueIds = emptyList(),
        )

        val plan = policy.plan(
            songs = librarySongs,
            libraryIds = librarySongs.map { it.id },
            currentQueueIds = listOf("playlist-only", "lib-a"),
        )

        assertTrue(plan is LibraryQueueSyncPlan.RefreshMetadata)
        assertEquals(librarySongs, (plan as LibraryQueueSyncPlan.RefreshMetadata).songs)
    }

    @Test
    fun librarySortReorderDoesNotReplacePlayerQueue() {
        val policy = LibraryQueueSyncPolicy()
        val songA = SongFixtures.song("a")
        val songB = SongFixtures.song("b")
        val songC = SongFixtures.song("c")
        val originalOrder = listOf(songA, songB, songC)
        val reorderedIds = listOf(songC.id, songB.id, songA.id)
        val reorderedSongs = listOf(songC, songB, songA)
        policy.plan(
            songs = originalOrder,
            libraryIds = originalOrder.map { it.id },
            currentQueueIds = emptyList(),
        )
        policy.plan(
            songs = originalOrder,
            libraryIds = originalOrder.map { it.id },
            currentQueueIds = originalOrder.map { it.id },
        )

        val plan = policy.plan(
            songs = reorderedSongs,
            libraryIds = reorderedIds,
            currentQueueIds = originalOrder.map { it.id },
        )

        assertTrue(plan is LibraryQueueSyncPlan.RefreshMetadata)
        assertEquals(reorderedSongs, (plan as LibraryQueueSyncPlan.RefreshMetadata).songs)
        assertTrue(plan.currentQueueWasLibrary)
    }

    @Test
    fun librarySongRemovedAfterDeleteRefreshesWithoutReplacingQueue() {
        val policy = LibraryQueueSyncPolicy()
        val songA = SongFixtures.song("a")
        val songB = SongFixtures.song("b")
        val songC = SongFixtures.song("c")
        val all = listOf(songA, songB, songC)
        policy.plan(
            songs = all,
            libraryIds = all.map { it.id },
            currentQueueIds = emptyList(),
        )
        policy.plan(
            songs = all,
            libraryIds = all.map { it.id },
            currentQueueIds = all.map { it.id },
        )

        val remaining = listOf(songB, songC)
        val plan = policy.plan(
            songs = remaining,
            libraryIds = remaining.map { it.id },
            currentQueueIds = remaining.map { it.id },
        )

        assertTrue(plan is LibraryQueueSyncPlan.RefreshMetadata)
        assertEquals(remaining, (plan as LibraryQueueSyncPlan.RefreshMetadata).songs)
    }

    @Test
    fun librarySongRemovedFromMiddleOfQueueRefreshesWithoutReplacingQueue() {
        val policy = LibraryQueueSyncPolicy()
        val songA = SongFixtures.song("a")
        val songB = SongFixtures.song("b")
        val songC = SongFixtures.song("c")
        val all = listOf(songA, songB, songC)
        policy.plan(
            songs = all,
            libraryIds = all.map { it.id },
            currentQueueIds = emptyList(),
        )
        policy.plan(
            songs = all,
            libraryIds = all.map { it.id },
            currentQueueIds = all.map { it.id },
        )

        val remaining = listOf(songA, songC)
        val plan = policy.plan(
            songs = remaining,
            libraryIds = remaining.map { it.id },
            currentQueueIds = listOf(songA.id, songC.id),
        )

        assertTrue(plan is LibraryQueueSyncPlan.RefreshMetadata)
        assertEquals(remaining, (plan as LibraryQueueSyncPlan.RefreshMetadata).songs)
    }

    @Test
    fun queueContainingRemovedLibrarySongIsRebuiltFromCurrentLibrary() {
        val policy = LibraryQueueSyncPolicy()
        val oldSongs = listOf(
            SongFixtures.song("keep"),
            SongFixtures.song("removed"),
        )
        val newSongs = listOf(SongFixtures.song("keep"))
        policy.plan(
            songs = oldSongs,
            libraryIds = oldSongs.map { it.id },
            currentQueueIds = emptyList(),
        )

        val plan = policy.plan(
            songs = newSongs,
            libraryIds = newSongs.map { it.id },
            currentQueueIds = listOf("removed", "keep"),
        )

        assertTrue(plan is LibraryQueueSyncPlan.SetQueue)
        assertEquals(newSongs, (plan as LibraryQueueSyncPlan.SetQueue).songs)
    }
}
