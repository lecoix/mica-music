package com.mica.music.data

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControllerQueueSyncTest {
    @Test
    fun canMoveItemIncrementallyRequiresAlignedSourceAndDestination() {
        val queue = SongFixtures.queue(3)
        val player = mockPlayer(queue.map { item(it.id) })
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true

        assertTrue(MediaControllerQueueSync.canMoveItemIncrementally(player, queue, 0, 2))
    }

    @Test
    fun canMoveItemIncrementallyRejectsMisalignedDestination() {
        val queue = SongFixtures.queue(3)
        val player = mockPlayer(listOf(item(queue[0].id), item(queue[1].id), item("stale")))
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true

        assertFalse(MediaControllerQueueSync.canMoveItemIncrementally(player, queue, 0, 2))
    }

    @Test
    fun syncToPlayerSkipsWhenPreservingAlreadyAlignedQueue() {
        val queue = SongFixtures.queue(2)
        val player = mockPlayer(queue.map { item(it.id) }, currentIndex = 0, currentPosition = 12_000L)

        MediaControllerQueueSync.syncToPlayer(
            player = player,
            queue = queue,
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
    }

    @Test
    fun syncToPlayerSetsMediaItemsWhenQueueIsMisaligned() {
        val queue = SongFixtures.queue(2)
        val player = mockPlayer(listOf(item(queue[0].id), item("stale")), currentIndex = 0, currentPosition = 12_000L)

        MediaControllerQueueSync.syncToPlayer(
            player = player,
            queue = queue,
            targetIndex = 1,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        verify(exactly = 1) {
            player.setMediaItems(match { it.map(MediaItem::mediaId) == queue.map(Song::id) }, 0, 12_000L)
        }
    }

    private fun item(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()

    private fun mockPlayer(
        items: List<MediaItem>,
        currentIndex: Int = 0,
        currentPosition: Long = 0L,
    ): Player {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns items.size
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.currentMediaItem } returns items.getOrNull(currentIndex)
        every { player.currentPosition } returns currentPosition
        return player
    }
}
