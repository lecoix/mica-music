package com.mica.music.playback

import com.mica.music.data.Song
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        val player = mockPlayer(queue.map(SongMediaItemCodec::encode), currentIndex = 0, currentPosition = 12_000L)

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
    fun planSyncSeparatesAlignedSkipFromExecution() {
        val queue = SongFixtures.queue(2)
        val player = mockPlayer(queue.map(SongMediaItemCodec::encode), currentIndex = 0, currentPosition = 12_000L)

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = queue,
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.Skip)
        assertEquals(0, plan?.result?.startIndex)
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

    @Test
    fun setMediaItemsPlanRunsOnlyWhenExecuted() {
        val queue = SongFixtures.queue(2)
        val player = mockPlayer(listOf(item(queue[0].id), item("stale")), currentIndex = 0, currentPosition = 12_000L)

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = queue,
            targetIndex = 1,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.SetMediaItems)
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }

        MediaControllerQueueSync.executeSyncPlan(player, plan!!)

        verify(exactly = 1) {
            player.setMediaItems(match { it.map(MediaItem::mediaId) == queue.map(Song::id) }, 0, 12_000L)
        }
    }

    @Test
    fun sameIdentityWithChangedMetadataUsesIncrementalReplacement() {
        val oldSong = SongFixtures.song(id = "same-id", title = "old")
        val refreshed = oldSong.copy(title = "new")
        val player = mockPlayer(listOf(SongMediaItemCodec.encode(oldSong)))
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = listOf(refreshed),
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.ReplaceMediaItems)
        MediaControllerQueueSync.executeSyncPlan(player, plan!!)
        verify(exactly = 1) {
            player.replaceMediaItem(0, match { SongMediaItemCodec.decode(it)?.title == "new" })
        }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
    }

    @Test
    fun reorderedSameQueueUsesIncrementalMovesInsteadOfResettingPlayback() {
        val source = SongFixtures.queue(4)
        val desired = listOf(source[2], source[0], source[3], source[1])
        val player = mockPlayer(
            source.map(SongMediaItemCodec::encode),
            currentIndex = 2,
            currentPosition = 34_567L,
        )
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = desired,
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.MoveMediaItems)
        assertEquals(
            listOf(
                QueueMove(fromIndex = 2, toIndex = 0),
                QueueMove(fromIndex = 3, toIndex = 2),
            ),
            (plan as PlaybackQueueSyncPlan.MoveMediaItems).moves,
        )
        assertEquals(0, plan.result.startIndex)

        MediaControllerQueueSync.executeSyncPlan(player, plan)

        verify(exactly = 1) { player.moveMediaItem(2, 0) }
        verify(exactly = 1) { player.moveMediaItem(3, 2) }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
    }

    @Test
    fun reorderedQueueWithChangedMetadataStillUsesFullSyncFallback() {
        val source = SongFixtures.queue(3)
        val desired = listOf(source[1].copy(title = "refreshed"), source[0], source[2])
        val player = mockPlayer(source.map(SongMediaItemCodec::encode), currentIndex = 1)
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = desired,
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.SetMediaItems)
    }

    @Test
    fun playbackStatsDoNotInvalidateQueueMetadata() {
        val song = SongFixtures.song(id = "same-id")
        val player = mockPlayer(listOf(SongMediaItemCodec.encode(song)))

        val plan = MediaControllerQueueSync.planSync(
            player = player,
            queue = listOf(song.copy(playCount = 99, totalListenSeconds = 1_234L, lastPlayedAtMs = 5_678L)),
            targetIndex = 0,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )

        assertTrue(plan is PlaybackQueueSyncPlan.Skip)
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
