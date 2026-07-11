package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicaCompositePlayerCommandRoutingTest {

    @Test
    fun trackSelectionCommandsRouteThroughCoordinator() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator

        player.seekTo(3, 1_234L)
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()
        player.seekToNext()
        player.seekToPrevious()
        player.seekTo(4_321L)

        verify(exactly = 1) { coordinator.onSelectMediaItem(3, 1_234L) }
        verify(exactly = 2) { coordinator.onSkipToNext() }
        verify(exactly = 2) { coordinator.onSkipToPrevious() }
        verify(exactly = 1) { exo.seekTo(4_321L) }
        verify(exactly = 0) { exo.seekTo(3, 1_234L) }
    }

    @Test
    fun withoutCoordinatorCommandsStayOnExoPlayer() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)

        player.seekTo(2, 987L)
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()

        verify(exactly = 1) { exo.seekTo(2, 987L) }
        verify(exactly = 1) { exo.seekToNextMediaItem() }
        verify(exactly = 1) { exo.seekToPreviousMediaItem() }
    }

    @Test
    fun rapidIndexedSelectionsPreserveOrder() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator

        player.seekTo(3, 0L)
        player.seekTo(4, 125L)
        player.seekTo(1, 250L)
        player.seekTo(5, 375L)

        verifySequence {
            coordinator.onSelectMediaItem(3, 0L)
            coordinator.onSelectMediaItem(4, 125L)
            coordinator.onSelectMediaItem(1, 250L)
            coordinator.onSelectMediaItem(5, 375L)
        }
        verify(exactly = 0) { exo.seekTo(any<Int>(), any<Long>()) }
    }

    @Test
    fun queueEditsUpdateSnapshotRevision() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val queue = items(4)
        every { exo.mediaItemCount } returnsMany listOf(4, 5, 5, 4)
        every { exo.getMediaItemAt(any()) } answers {
            queue.getOrNull(firstArg()) ?: MediaItem.Builder().setMediaId("inserted").build()
        }
        every { exo.currentMediaItemIndex } returns 1
        val player = MicaCompositePlayer(exo)
        val initialRevision = player.playbackQueueSnapshot().revision

        player.addMediaItem(2, MediaItem.Builder().setMediaId("inserted").build())
        player.moveMediaItem(4, 0)
        player.removeMediaItem(1)

        assertTrue(player.playbackQueueSnapshot().revision > initialRevision)
    }

    @Test
    fun flushPlaybackPipeline_stopsSeeksAndResumes() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)

        player.flushPlaybackPipeline(positionMs = 12_345L, resumePlayback = true)

        verifySequence {
            exo.playWhenReady = false
            exo.stop()
            exo.seekTo(12_345L)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    @Test
    fun startPlaybackRunsPreStartHook() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.playbackState } returns Player.STATE_READY
        every { exo.currentMediaItem } returns null
        every { exo.mediaItemCount } returns 0
        var starts = 0
        val player = MicaCompositePlayer(exo) { starts++ }

        player.startExoPlayback(items(1), startIndex = 0)

        assertEquals(1, starts)
    }

    @Test
    fun replayGainMultipliesRequestedVolume() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)

        player.volume = 0.8f
        player.setReplayGainVolume(0.5f)
        player.volume = 0.6f

        verifySequence {
            exo.volume = 0.8f
            exo.volume = 0.4f
            exo.volume = 0.3f
        }
    }

    private fun items(count: Int): List<MediaItem> =
        List(count) { index ->
            MediaItem.Builder().setMediaId("song-$index").build()
        }
}
