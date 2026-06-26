package com.mica.music.data

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun item(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()

    private fun mockPlayer(items: List<MediaItem>): Player {
        val player = mockk<Player>()
        every { player.mediaItemCount } returns items.size
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        return player
    }
}
