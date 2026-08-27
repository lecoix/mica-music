package com.mica.music.data

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.playback.PlaybackQueueMirror
import com.mica.music.playback.PlaybackQueueMirrorCoordinator
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueMirrorTest {
    @Test
    fun snapshotItemsReadsAvailablePlayerItemsInOrder() {
        val items = listOf(item("a"), item("b"))
        val player = mockPlayer(items)

        val snapshot = PlaybackQueueMirror.snapshotItems(player)

        assertEquals(listOf("a", "b"), snapshot.map(MediaItem::mediaId))
    }

    @Test
    fun buildIfChangedSkipsRebuildWhenSignatureMatchesPrevious() {
        val items = listOf(item("a"), item("b"))
        val previous = PlaybackQueueMirror.orderSignature(items)

        val build = PlaybackQueueMirror.buildIfChanged(
            items = items,
            previousSignature = previous,
            localQueue = emptyList(),
            fallbackResolver = { error("resolver should not be called") },
        )

        assertEquals(previous, build.signature)
        assertNull(build.songs)
    }

    @Test
    fun buildIfChangedPrefersLocalSongsBeforeFallbackResolver() {
        val local = SongFixtures.song("local")
        val fallback = SongFixtures.song("fallback")

        val build = PlaybackQueueMirror.buildIfChanged(
            items = listOf(item("local"), item("fallback")),
            previousSignature = null,
            localQueue = listOf(local),
            fallbackResolver = { id -> fallback.takeIf { id == it.id } },
        )

        assertEquals(listOf(local, fallback), build.songs)
    }

    @Test
    fun coordinatorRebuildNowAppliesMirrorAndRemembersSignature() {
        val dispatcher = StandardTestDispatcher()
        val coordinator = PlaybackQueueMirrorCoordinator(
            scope = TestScope(dispatcher),
            workerDispatcher = dispatcher,
            debounceMs = 0L,
        )
        val song = SongFixtures.song("song-a")
        val items = listOf(item(song.id))
        var applied: List<Song>? = null
        var appliedIndex: Int? = null

        val result = coordinator.rebuildNow(
            player = mockPlayer(items, currentIndex = 0),
            resolver = { id -> song.takeIf { it.id == id } },
        ) { songs, playerIndex ->
            applied = songs
            appliedIndex = playerIndex
        }

        assertEquals(1, result.itemsCount)
        assertEquals(1, result.resolvedCount)
        assertEquals(listOf(song), applied)
        assertEquals(0, appliedIndex)
        assertTrue(coordinator.hasSignature(PlaybackQueueMirror.orderSignature(items)))
    }

    @Test
    fun coordinatorRejectsPartialMirrorAndAllowsRetry() {
        val dispatcher = StandardTestDispatcher()
        val coordinator = PlaybackQueueMirrorCoordinator(
            scope = TestScope(dispatcher),
            workerDispatcher = dispatcher,
            debounceMs = 0L,
        )
        val first = SongFixtures.song("first")
        val missing = SongFixtures.song("missing")
        val last = SongFixtures.song("last")
        val items = listOf(item(first.id), item(missing.id), item(last.id))
        var applied: List<Song>? = null

        val failed = coordinator.rebuildNow(
            player = mockPlayer(items, currentIndex = 1),
            resolver = { id -> listOf(first, last).firstOrNull { it.id == id } },
        ) { songs, _ -> applied = songs }

        assertEquals(3, failed.itemsCount)
        assertEquals(2, failed.resolvedCount)
        assertTrue(!failed.applied)
        assertNull(applied)
        assertTrue(!coordinator.hasSignature(PlaybackQueueMirror.orderSignature(items)))

        coordinator.rebuildNow(
            player = mockPlayer(items, currentIndex = 1),
            resolver = { id -> listOf(first, missing, last).firstOrNull { it.id == id } },
        ) { songs, _ -> applied = songs }

        assertEquals(listOf(first, missing, last), applied)
        assertTrue(coordinator.hasSignature(PlaybackQueueMirror.orderSignature(items)))
    }

    @Test
    fun snapshotItemsRejectsIncompletePlayerSnapshot() {
        val player = mockk<Player>()
        every { player.mediaItemCount } returns 3
        every { player.getMediaItemAt(0) } returns item("first")
        every { player.getMediaItemAt(1) } throws IndexOutOfBoundsException()
        every { player.getMediaItemAt(2) } returns item("last")

        assertTrue(PlaybackQueueMirror.snapshotItems(player).isEmpty())
    }

    private fun item(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()

    private fun mockPlayer(items: List<MediaItem>, currentIndex: Int = 0): Player {
        val player = mockk<Player>()
        every { player.mediaItemCount } returns items.size
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.currentMediaItemIndex } returns currentIndex
        return player
    }
}
