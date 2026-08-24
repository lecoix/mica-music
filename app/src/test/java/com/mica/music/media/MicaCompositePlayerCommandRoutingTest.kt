package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
        assertEquals(0.6f, player.volume, 0f)
    }

    @Test
    fun playPauseCommandsPublishSemanticIntentEvenWhenUnderlyingStateDoesNotChange() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        val intents = mutableListOf<Boolean>()
        player.onUserPlayIntentChanged = intents::add

        player.pause()
        player.play()
        player.playWhenReady = false

        assertEquals(listOf(false, true, false), intents)
    }

    @Test
    fun technicalPipelineFlushDoesNotRewriteSemanticPlayIntent() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        val intents = mutableListOf<Boolean>()
        player.onUserPlayIntentChanged = intents::add

        player.flushPlaybackPipeline(positionMs = 4_321L, resumePlayback = false)

        assertTrue(intents.isEmpty())
    }

    @Test
    fun deferredPlayPausePublishesIntentWithoutTouchingPhysicalPlayer() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        val intents = mutableListOf<Boolean>()
        player.playbackCoordinator = coordinator
        player.onUserPlayIntentChanged = intents::add
        player.shouldDeferUserPlayIntent = { true }

        player.play()
        player.pause()
        player.playWhenReady = true

        assertEquals(listOf(true, false, true), intents)
        verify(exactly = 0) { coordinator.playCurrent() }
        verify(exactly = 0) { exo.play() }
        verify(exactly = 0) { exo.pause() }
        verify(exactly = 0) { exo.playWhenReady = any() }
    }

    @Test
    fun retiredPlayerDropsLateMediaSessionMutationsBeforeTheyReachReleasedExo() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator
        player.retireForReplacement()

        player.setMediaItems(items(2), 0, 0L)
        player.addMediaItem(0, items(1).single())
        player.removeMediaItem(0)
        player.play()
        player.pause()
        player.playWhenReady = true
        player.seekTo(1, 123L)
        player.seekTo(456L)
        player.prepare()
        player.stop()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = true
        player.playbackParameters = PlaybackParameters(1.25f)
        player.volume = 0.5f

        assertTrue(player.isRetiredForReplacement())
        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 0) { exo.addMediaItem(any<Int>(), any()) }
        verify(exactly = 0) { exo.removeMediaItem(any()) }
        verify(exactly = 0) { exo.play() }
        verify(exactly = 0) { exo.pause() }
        verify(exactly = 0) { exo.playWhenReady = any() }
        verify(exactly = 0) { exo.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { exo.seekTo(any<Long>()) }
        verify(exactly = 0) { exo.prepare() }
        verify(exactly = 0) { exo.stop() }
        verify(exactly = 0) { exo.repeatMode = any() }
        verify(exactly = 0) { exo.shuffleModeEnabled = any() }
        verify(exactly = 0) { exo.playbackParameters = any() }
        verify(exactly = 0) { exo.volume = any() }
        verify(exactly = 0) { coordinator.onSelectMediaItem(any(), any()) }
        verify(exactly = 0) { coordinator.playCurrent() }
    }

    private fun items(count: Int): List<MediaItem> =
        List(count) { index ->
            MediaItem.Builder().setMediaId("song-$index").build()
        }
}
