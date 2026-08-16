package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mica.music.media.dsd.DirectDsdSeekDiscontinuityCoordinator
import com.mica.music.media.dsd.DirectDsdSessionGeneration
import com.mica.music.media.usb.protocol.FamilyOwnership
import com.mica.music.media.usb.protocol.PlaybackIntent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicaCompositePlayerCommandRoutingTest {

    @Before
    fun resetDirectSeekStateBeforeTest() {
        DirectDsdSeekDiscontinuityCoordinator.resetForTest()
    }

    @After
    fun resetDirectSeekStateAfterTest() {
        DirectDsdSeekDiscontinuityCoordinator.resetForTest()
    }

    @Test
    fun trackSelectionCommandsRouteThroughCoordinator() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo, testPlaybackStack())
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
    fun withoutBoundProtocolDestinationCommandsFailClosedBeforeExo() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo, testPlaybackStack())

        player.seekTo(2, 987L)
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()

        verify(exactly = 0) { exo.seekTo(2, 987L) }
        verify(exactly = 0) { exo.seekToNextMediaItem() }
        verify(exactly = 0) { exo.seekToPreviousMediaItem() }
    }

    @Test
    fun rapidIndexedSelectionsPreserveOrder() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo, testPlaybackStack())
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
    fun ordinaryPlayingPositionSeekPublishesDirectIntentBeforeExoSeek() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.isPlaying } returns true
        activateDirectSession()
        every { exo.seekTo(12_345L) } answers {
            assertNotNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
        }
        val player = MicaCompositePlayer(exo, testPlaybackStack())

        player.seekTo(12_345L)

        val pending = requireNotNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
        assertEquals(12_345_000L, pending.targetSourcePositionUs)
        verify(exactly = 1) { exo.seekTo(12_345L) }
    }

    @Test
    fun startExistingSamePlayingItemPublishesDirectIntentBeforeIndexedSeek() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns 1
        every { exo.currentMediaItemIndex } returns 0
        every { exo.isPlaying } returns true
        every { exo.playbackState } returns Player.STATE_READY
        activateDirectSession()
        every { exo.seekTo(0, 45_000L) } answers {
            assertNotNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
        }
        val player = MicaCompositePlayer(exo, testPlaybackStack())

        player.startExistingItem(index = 0, positionMs = 45_000L, playWhenReady = true)

        val pending = requireNotNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
        assertEquals(45_000_000L, pending.targetSourcePositionUs)
        verify(exactly = 1) { exo.seekTo(0, 45_000L) }
    }

    @Test
    fun crossItemAndPausedExistingSeeksDoNotPublishDirectPlayingIntent() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns 2
        every { exo.currentMediaItemIndex } returns 0
        every { exo.isPlaying } returns true
        every { exo.playbackState } returns Player.STATE_READY
        activateDirectSession()
        val player = MicaCompositePlayer(exo, testPlaybackStack())

        player.startExistingItem(index = 1, positionMs = 1_000L, playWhenReady = true)
        assertNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())

        every { exo.currentMediaItemIndex } returns 0
        every { exo.isPlaying } returns false
        player.startExistingItem(index = 0, positionMs = 2_000L, playWhenReady = false)
        assertNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
    }

    @Test
    fun explicitPauseCancelsStaleDirectSeekIntentBeforeExoPause() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        activateDirectSession()
        assertNotNull(DirectDsdSeekDiscontinuityCoordinator.publishPlayingSeek(5_000L))
        val player = MicaCompositePlayer(exo, testPlaybackStack())

        player.dispatchSemanticPause()

        assertNull(DirectDsdSeekDiscontinuityCoordinator.pendingForTest())
        verify(exactly = 1) { exo.pause() }
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
        val player = MicaCompositePlayer(exo, testPlaybackStack())
        val initialRevision = player.playbackQueueSnapshot().revision

        player.addMediaItem(2, MediaItem.Builder().setMediaId("inserted").build())
        player.moveMediaItem(4, 0)
        player.removeMediaItem(1)

        assertTrue(player.playbackQueueSnapshot().revision > initialRevision)
    }

    @Test
    fun setMediaItemsMintsProtocolDestinationBeforeExoDispatch() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        val player = MicaCompositePlayer(exo, stack)
        val destination = MediaItem.Builder().setMediaId("destination-b").build()
        every { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) } answers {
            assertEquals("destination-b", stack.snapshot().mutation?.targetMediaId)
            assertFalse(stack.snapshot().mutation?.destinationBound ?: true)
        }

        player.setMediaItems(listOf(destination), 0, 0L)

        verify(exactly = 1) { exo.setMediaItems(listOf(destination), 0, 0L) }
    }

    @Test
    fun replacingQueueSupersedesStaleDestinationBeforeEachExoDispatch() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        val player = MicaCompositePlayer(exo, stack)
        val b = MediaItem.Builder().setMediaId("destination-b").build()
        val c = MediaItem.Builder().setMediaId("destination-c").build()
        val dispatched = mutableListOf<String>()
        every { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) } answers {
            dispatched += stack.snapshot().mutation?.targetMediaId.orEmpty()
        }

        player.setMediaItems(listOf(b), 0, 0L)
        player.setMediaItems(listOf(c), 0, 0L)

        assertEquals(listOf("destination-b", "destination-c"), dispatched)
        assertEquals("destination-c", stack.snapshot().mutation?.targetMediaId)
    }

    @Test
    fun clearMediaItemsRevokesSourceLeaseAndFencesTeardownBeforeExoDispatch() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        val player = MicaCompositePlayer(exo, stack)
        every { exo.clearMediaItems() } answers {
            val snapshot = stack.snapshot()
            assertNull(snapshot.applicationCurrent.mediaId)
            assertTrue((snapshot.familyOwnership as FamilyOwnership.PcmOwned).writeLease.isRevoked())
            assertTrue(snapshot.mutation?.targetMediaId?.startsWith("__queue_clear__:") == true)
        }

        player.clearMediaItems()

        verify(exactly = 1) { exo.clearMediaItems() }
    }

    @Test
    fun t1TechnicalFlushUnchangedPlayKeepsRevisionAndRestoresPlay() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        val before = stack.snapshot().adoptedIntent
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(positionMs = 12_345L))

        assertEquals(before.revision, restored.revision)
        assertEquals(PlaybackIntent.PLAY, restored.desired)
        verifySequence {
            exo.playWhenReady = false
            exo.stop()
            exo.seekTo(12_345L)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    @Test
    fun t2PlayToPauseDuringTechnicalWindowRestoresLatestPause() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        val play = stack.snapshot().adoptedIntent
        every { exo.stop() } answers { stack.publishSemanticIntent(false) }
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(2_000L))

        assertEquals(PlaybackIntent.PAUSE, restored.desired)
        assertTrue(restored.revision.value > play.revision.value)
        verify(exactly = 2) { exo.playWhenReady = false }
        verify(exactly = 0) { exo.playWhenReady = true }
    }

    @Test
    fun t3PauseToPlayDuringTechnicalWindowRestoresLatestPlay() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        val pause = stack.snapshot().adoptedIntent
        every { exo.stop() } answers { stack.publishSemanticIntent(true) }
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(3_000L))

        assertEquals(PlaybackIntent.PLAY, restored.desired)
        assertTrue(restored.revision.value > pause.revision.value)
        verify(exactly = 1) { exo.playWhenReady = true }
    }

    @Test
    fun t4PlayPausePlayBeforeRestoreUsesLatestPlay() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        val r1 = stack.snapshot().adoptedIntent.revision.value
        every { exo.stop() } answers { stack.publishSemanticIntent(false) }
        every { exo.seekTo(4_000L) } answers { stack.publishSemanticIntent(true) }
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(4_000L))

        assertEquals(PlaybackIntent.PLAY, restored.desired)
        assertEquals(r1 + 2L, restored.revision.value)
        verify(exactly = 1) { exo.playWhenReady = true }
    }

    @Test
    fun t5PausePlayPauseBeforeRestoreUsesLatestPause() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        val r1 = stack.snapshot().adoptedIntent.revision.value
        every { exo.stop() } answers { stack.publishSemanticIntent(true) }
        every { exo.seekTo(5_000L) } answers { stack.publishSemanticIntent(false) }
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(5_000L))

        assertEquals(PlaybackIntent.PAUSE, restored.desired)
        assertEquals(r1 + 2L, restored.revision.value)
        verify(exactly = 2) { exo.playWhenReady = false }
        verify(exactly = 0) { exo.playWhenReady = true }
    }

    @Test
    fun t6DuplicateSemanticPlayDuringWindowIsLedgerIdempotent() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        val r1 = stack.snapshot().adoptedIntent.revision
        every { exo.stop() } answers { stack.publishSemanticIntent(true) }
        val player = MicaCompositePlayer(exo, stack)

        val restored = requireNotNull(player.flushPlaybackPipeline(6_000L))

        assertEquals(r1, restored.revision)
        assertEquals(PlaybackIntent.PLAY, restored.desired)
    }

    @Test
    fun t10InactiveStackAtRestoreCannotRegrantPlay() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        every { exo.stop() } answers { stack.protocol.beginRetiring() }
        val player = MicaCompositePlayer(exo, stack)

        val restored = player.flushPlaybackPipeline(10_000L)

        assertNull(restored)
        verify(exactly = 2) { exo.playWhenReady = false }
        verify(exactly = 0) { exo.playWhenReady = true }
    }
    @Test
    fun t8SemanticDirectHelpersPublishBeforeExoAndNotifyBookkeeping() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.playbackState } returns Player.STATE_READY
        val stack = testPlaybackStack()
        val intents = mutableListOf<Boolean>()
        val player = MicaCompositePlayer(exo, stack).also {
            it.onPlaybackIntentChanged = intents::add
        }
        every { exo.play() } answers {
            assertEquals(PlaybackIntent.PLAY, stack.snapshot().adoptedIntent.desired)
            assertEquals(listOf(true), intents)
        }
        every { exo.pause() } answers {
            assertEquals(PlaybackIntent.PAUSE, stack.snapshot().adoptedIntent.desired)
            assertEquals(listOf(true, false), intents)
        }

        player.dispatchSemanticPlay()
        player.dispatchSemanticPause()

        assertEquals(listOf(true, false), intents)
        verify(exactly = 1) { exo.play() }
        verify(exactly = 1) { exo.pause() }
    }

    @Test
    fun t9TechnicalFlushNeverPublishesSemanticIntentOrBookkeeping() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val stack = testPlaybackStack()
        assertTrue(stack.publishSemanticIntent(true))
        val before = stack.snapshot().adoptedIntent
        var explicitCallbacks = 0
        val player = MicaCompositePlayer(exo, stack).also {
            it.onPlaybackIntentChanged = { explicitCallbacks++ }
        }

        val restored = requireNotNull(player.flushPlaybackPipeline(9_000L))

        assertEquals(before, restored)
        assertEquals(0, explicitCallbacks)
    }

    @Test
    fun startPlaybackRunsPreStartHook() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.playbackState } returns Player.STATE_READY
        every { exo.currentMediaItem } returns null
        every { exo.mediaItemCount } returns 0
        var starts = 0
        val player = MicaCompositePlayer(exo, testPlaybackStack(), beforePlaybackStart = { starts++ })

        player.startExoPlayback(items(1), startIndex = 0)

        assertEquals(1, starts)
    }

    @Test
    fun replayGainMultipliesRequestedVolume() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo, testPlaybackStack())

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

    private fun items(count: Int): List<MediaItem> =
        List(count) { index ->
            MediaItem.Builder().setMediaId("song-$index").build()
        }

    private fun activateDirectSession() {
        DirectDsdSeekDiscontinuityCoordinator.activateSession(
            DirectDsdSessionGeneration(rendererGeneration = 99L, sessionGeneration = 1L),
        )
    }
}
