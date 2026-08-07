package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaybackQueueCoordinatorTest {
    private class FakeTarget : LibraryPlaybackQueueCoordinator.Target {
        var queuedSongs: List<Song> = emptyList()
        var connectCount = 0
        var bootstrapResult = false
        var bootstrapResolver: ((String) -> Song?)? = null
        val setQueueCalls = mutableListOf<List<Song>>()
        val refreshCalls = mutableListOf<List<Song>>()

        override val currentQueueIds: List<String>
            get() = queuedSongs.map { it.id }

        override val queueSize: Int
            get() = queuedSongs.size

        override fun connectIfNeeded() {
            connectCount++
        }

        override fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean {
            bootstrapResolver = resolveSong
            return bootstrapResult
        }

        override fun setQueue(newQueue: List<Song>) {
            queuedSongs = newQueue
            setQueueCalls += newQueue
        }

        override fun refreshQueueMetadata(songs: List<Song>) {
            refreshCalls += songs
        }
    }

    private fun libraryInput(
        songs: List<Song>,
        songIds: List<String> = songs.map { it.id },
        hasScanned: Boolean = true,
    ): LibraryQueueSyncInput = LibraryQueueSyncInput(
        songs = songs,
        songIds = songIds,
        hasScanned = hasScanned,
        songById = { id -> songs.firstOrNull { it.id == id } },
    )

    @Test
    fun emptyLibraryConnectsAndAttemptsExternalQueueBootstrap() {
        val target = FakeTarget().apply { bootstrapResult = true }

        LibraryPlaybackQueueCoordinator().sync(
            reason = "test",
            library = libraryInput(emptyList(), hasScanned = false),
            player = target,
        )

        assertEquals(1, target.connectCount)
        assertTrue(target.bootstrapResolver != null)
        assertTrue(target.setQueueCalls.isEmpty())
    }

    @Test
    fun emptyLibraryBootstrapFailureKeepsQueueEmpty() {
        val target = FakeTarget().apply { bootstrapResult = false }

        LibraryPlaybackQueueCoordinator().sync(
            reason = "test",
            library = libraryInput(emptyList(), hasScanned = true),
            player = target,
        )

        assertEquals(1, target.connectCount)
        assertTrue(target.bootstrapResolver != null)
        assertTrue(target.setQueueCalls.isEmpty())
        assertTrue(target.queuedSongs.isEmpty())
    }

    @Test
    fun bootstrapSuccessDoesNotSetFullLibraryQueue() {
        val songs = SongFixtures.queue(3)
        val target = FakeTarget().apply { bootstrapResult = true }

        LibraryPlaybackQueueCoordinator().sync(
            reason = "test",
            library = libraryInput(songs),
            player = target,
        )

        assertEquals(1, target.connectCount)
        assertEquals(songs.first().id, target.bootstrapResolver?.invoke(songs.first().id)?.id)
        assertTrue(target.setQueueCalls.isEmpty())
    }

    @Test
    fun bootstrapFailureSetsLibraryQueue() {
        val songs = SongFixtures.queue(2)
        val target = FakeTarget().apply { bootstrapResult = false }

        LibraryPlaybackQueueCoordinator().sync(
            reason = "test",
            library = libraryInput(songs),
            player = target,
        )

        assertEquals(songs, target.setQueueCalls.single())
        assertEquals(songs, target.queuedSongs)
    }

    @Test
    fun removedLibrarySongTriggersSetQueue() {
        val coordinator = LibraryPlaybackQueueCoordinator()
        val oldSongs = listOf(SongFixtures.song("keep"), SongFixtures.song("removed"))
        val newSongs = listOf(SongFixtures.song("keep"))
        val target = FakeTarget().apply {
            queuedSongs = oldSongs
            bootstrapResult = false
        }

        coordinator.sync("seed", libraryInput(oldSongs), target)
        target.setQueueCalls.clear()
        target.connectCount = 0

        coordinator.sync(
            reason = "test",
            library = libraryInput(newSongs),
            player = target,
        )

        assertEquals(1, target.connectCount)
        assertEquals(newSongs, target.setQueueCalls.single())
    }

    @Test
    fun unchangedLibraryIdsRefreshMetadataOnly() {
        val coordinator = LibraryPlaybackQueueCoordinator()
        val songs = SongFixtures.queue(2)
        val target = FakeTarget().apply {
            queuedSongs = songs
            bootstrapResult = false
        }

        coordinator.sync("seed", libraryInput(songs), target)
        target.refreshCalls.clear()
        target.setQueueCalls.clear()

        coordinator.sync(
            reason = "test",
            library = libraryInput(songs),
            player = target,
        )

        assertTrue(target.setQueueCalls.isEmpty())
        assertEquals(songs, target.refreshCalls.single())
        assertFalse(target.bootstrapResult)
    }

    @Test
    fun coldStartBootstrapSuccessDoesNotReplaceRestoredServiceQueue() {
        val coordinator = LibraryPlaybackQueueCoordinator()
        val songs = SongFixtures.queue(3)
        val target = FakeTarget().apply { bootstrapResult = true }

        coordinator.sync("libraryIds", libraryInput(songs), target)

        assertEquals(1, target.connectCount)
        assertTrue(target.setQueueCalls.isEmpty())
        target.queuedSongs = songs
        target.refreshCalls.clear()

        coordinator.sync("libraryIds", libraryInput(songs), target)

        assertTrue(target.setQueueCalls.isEmpty())
        assertEquals(songs, target.refreshCalls.single())
    }

    @Test
    fun librarySortReorderOnlyRefreshesMetadata() {
        val coordinator = LibraryPlaybackQueueCoordinator()
        val songA = SongFixtures.song("a")
        val songB = SongFixtures.song("b")
        val songC = SongFixtures.song("c")
        val original = listOf(songA, songB, songC)
        val reordered = listOf(songC, songB, songA)
        val target = FakeTarget().apply {
            queuedSongs = original
            bootstrapResult = false
        }

        coordinator.sync("seed", libraryInput(original), target)
        target.refreshCalls.clear()
        target.setQueueCalls.clear()

        coordinator.sync(
            reason = "libraryIds",
            library = libraryInput(
                songs = reordered,
                songIds = reordered.map { it.id },
            ),
            player = target,
        )

        assertTrue(target.setQueueCalls.isEmpty())
        assertEquals(reordered, target.refreshCalls.single())
        assertEquals(original, target.queuedSongs)
    }

    @Test
    fun deleteSongLibrarySyncRefreshesWithoutSecondSetQueue() {
        val coordinator = LibraryPlaybackQueueCoordinator()
        val songA = SongFixtures.song("a")
        val songB = SongFixtures.song("b")
        val songC = SongFixtures.song("c")
        val all = listOf(songA, songB, songC)
        val remaining = listOf(songB, songC)
        val target = FakeTarget().apply {
            queuedSongs = all
            bootstrapResult = false
        }

        coordinator.sync("seed", libraryInput(all), target)
        target.queuedSongs = remaining
        target.setQueueCalls.clear()
        target.refreshCalls.clear()

        coordinator.sync("libraryIds", libraryInput(remaining), target)

        assertTrue(target.setQueueCalls.isEmpty())
        assertEquals(remaining, target.refreshCalls.single())
        assertEquals(remaining, target.queuedSongs)
    }
}
