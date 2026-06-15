package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicaCompositePlayerCommandRoutingTest {
    @Test
    fun softwareSessionRoutesEveryTrackSelectionCommandToCoordinator() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator
        player.startSoftwarePlaybackSession(
            mediaItems = items(4),
            startIndex = 1,
            state = softwareState(),
        )

        player.seekTo(3, 1_234L)
        player.seekToNextMediaItem()
        player.seekToPreviousMediaItem()
        player.seekToNext()
        player.seekToPrevious()
        player.seekTo(4_321L)

        verify(exactly = 1) { coordinator.onSelectMediaItem(3, 1_234L) }
        verify(exactly = 2) { coordinator.onSkipToNext() }
        verify(exactly = 2) { coordinator.onSkipToPrevious() }
        verify(exactly = 1) { coordinator.onSeekTo(4_321L) }
        verify(exactly = 0) { exo.seekTo(3, 1_234L) }
        val commands = player.availableCommands
        assertTrue("software commands=$commands", commands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
    }

    @Test
    fun enteringSoftwareSessionPublishesSeekCommandsToMediaSessionListener() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val listener = mockk<Player.Listener>(relaxed = true)
        val commands = slot<Player.Commands>()
        val player = MicaCompositePlayer(exo)
        player.addListener(listener)

        player.startSoftwarePlaybackSession(
            mediaItems = items(2),
            startIndex = 0,
            state = softwareState(),
        )

        verify(atLeast = 1) {
            listener.onAvailableCommandsChanged(capture(commands))
        }
        assertTrue(
            "published software commands=${commands.captured}",
            commands.captured.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        )
    }

    @Test
    fun media3SessionKeepsTrackSelectionCommandsOnExoPlayer() {
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
    fun rapidIndexedSelectionsPreserveEveryTargetAndPositionInOrder() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator
        player.startSoftwarePlaybackSession(
            mediaItems = items(6),
            startIndex = 2,
            state = softwareState(),
        )

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
    fun softwareQueueEditsUpdateSingleAuthoritativeSnapshot() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.startSoftwarePlaybackSession(
            mediaItems = items(4),
            startIndex = 1,
            state = softwareState(),
        )
        val initialRevision = player.playbackQueueSnapshot().revision

        player.addMediaItem(2, MediaItem.Builder().setMediaId("inserted").build())
        player.moveMediaItem(4, 0)
        player.removeMediaItem(1)

        val snapshot = player.playbackQueueSnapshot()
        assertEquals(
            listOf("song-3", "song-1", "inserted", "song-2"),
            snapshot.items.map { it.mediaId },
        )
        assertEquals("song-1", snapshot.currentItem?.mediaId)
        assertTrue(snapshot.revision > initialRevision)
    }

    @Test
    fun softwareTrackSwitchWithSameQueueRevisionDoesNotRebroadcastWholeTimeline() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val listener = mockk<Player.Listener>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        val queue = items(200)
        player.addListener(listener)

        player.startSoftwarePlaybackSession(
            mediaItems = queue,
            startIndex = 10,
            state = softwareState(),
            snapshotRevision = 7L,
        )
        player.startSoftwarePlaybackSession(
            mediaItems = queue,
            startIndex = 11,
            state = softwareState(),
            snapshotRevision = 7L,
        )

        verify(exactly = 1) {
            listener.onTimelineChanged(any(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
        verify(exactly = 2) {
            listener.onMediaItemTransition(
                any(),
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            )
        }
        assertEquals("song-11", player.currentMediaItem?.mediaId)
    }

    @Test
    fun removingCurrentSoftwareItemDelegatesSuccessorPlaybackToCoordinator() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator
        player.startSoftwarePlaybackSession(
            mediaItems = items(3),
            startIndex = 1,
            state = softwareState(),
        )

        player.removeMediaItem(1)

        assertEquals("song-2", player.playbackQueueSnapshot().currentItem?.mediaId)
        verify(exactly = 1) { coordinator.onCurrentQueueItemRemoved(true) }
    }

    @Test
    fun removingOnlySoftwareItemStopsSession() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val coordinator = mockk<ServicePlaybackEngineCoordinator>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        player.playbackCoordinator = coordinator
        player.startSoftwarePlaybackSession(
            mediaItems = items(1),
            startIndex = 0,
            state = softwareState(),
        )

        player.removeMediaItem(0)

        assertTrue(player.playbackQueueSnapshot().items.isEmpty())
        verify(exactly = 1) { coordinator.stopSoftwareSession() }
    }

    private fun items(count: Int): List<MediaItem> =
        List(count) { index ->
            MediaItem.Builder().setMediaId("song-$index").build()
        }

    private fun softwareState(): AlacSessionState =
        AlacSessionState(
            playWhenReady = true,
            buffering = false,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 60_000L,
        )
}
