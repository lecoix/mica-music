package com.mica.music.media

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.SongSource
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackStateCoordinatorTest {

    @Test
    fun t7RuntimeObservationCannotOverrideExplicitPlayBeforeRestoreCompletion() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one"),
            currentIndex = 0,
            positionMs = 5_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = PlaybackTuning(),
        )

        every { store.load() } returns snapshot
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 0

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        )
        coordinator.start()

        // This is the process-recovery ordering seen on SK02: composite PLAY records
        // intent before the delayed timeline can change the underlying playWhenReady.
        player.play()
        coordinator.onExplicitPlaybackIntent(true)
        listener.captured.onPlayWhenReadyChanged(false, 0)
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentMediaItem } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentPosition } returns 5_000L
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)

        verify(exactly = 1) { player.playWhenReady = true }
        verify(exactly = 1) { player.repeatMode = Player.REPEAT_MODE_OFF }
        verify(exactly = 1) { player.shuffleModeEnabled = false }
        verify(exactly = 1) { player.playbackParameters = snapshot.playbackTuning.toPlaybackParameters() }
        verify(exactly = 1) { player.seekTo(0, 5_000L) }
        verify(exactly = 1) { player.prepare() }
        coordinator.release()
    }

    @Test
    fun explicitPauseAfterPlayBeforeRestoreCompletionRemainsPaused() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one"),
            currentIndex = 0,
            positionMs = 5_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = PlaybackTuning(),
        )

        every { store.load() } returns snapshot
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 0

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        )
        coordinator.start()
        coordinator.onExplicitPlaybackIntent(true)
        coordinator.onExplicitPlaybackIntent(false)

        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentMediaItem } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentPosition } returns 5_000L
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)

        verify(exactly = 1) { player.playWhenReady = false }
        coordinator.release()
    }

    @Test
    fun technicalPlayWhenReadyObservationDoesNotOverwriteExplicitRestoreIntent() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one"),
            currentIndex = 0,
            positionMs = 5_000L,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playWhenReady = false,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = PlaybackTuning(),
        )

        every { store.load() } returns snapshot
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 0

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        )
        coordinator.start()
        coordinator.onExplicitPlaybackIntent(true)

        listener.captured.onPlayWhenReadyChanged(false, 0)

        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentMediaItem } returns MediaItem.Builder().setMediaId("one").build()
        every { player.currentPosition } returns 5_000L
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)

        verify(exactly = 1) { player.playWhenReady = true }
        coordinator.release()
    }

    @Test
    fun restorePreparesCurrentItemWithoutAutoPlay() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val snapshot = ServicePlaybackSnapshot(
            queueSongIds = listOf("one", "two"),
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = true,
            playWhenReady = true,
            qualityMode = AudioQualityMode.HIFI,
            playbackTuning = PlaybackTuning(speed = 1.5f, pitchSemitones = -12f),
        )

        every { store.load() } returns snapshot
        every { player.mediaItemCount } returns 2
        every { player.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("one").build()
        every { player.getMediaItemAt(1) } returns MediaItem.Builder().setMediaId("two").build()
        every { player.currentMediaItemIndex } returns 1
        every { player.currentPosition } returns 12_345L

        ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        ).start()

        verifyOrder {
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = false
            player.playWhenReady = false
            player.playbackParameters = snapshot.playbackTuning.toPlaybackParameters()
            player.seekTo(1, 12_345L)
            player.prepare()
        }
    }

    @Test
    fun timelinePersistenceIncludesCurrentExternalSongSnapshot() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val external = SongFixtures.song("external_test").copy(source = SongSource.TRANSIENT_EXTERNAL)
        val queueSnapshot = slot<ServiceQueueSnapshot>()
        val item = ExternalMediaItemCodec.encode(
            ApplicationProvider.getApplicationContext(),
            external,
        )

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns item
        every { store.saveQueue(capture(queueSnapshot), any()) } returns Unit

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
            externalSongResolver = { external },
        )
        coordinator.start()
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        coordinator.release()

        assertEquals(listOf(external.id), queueSnapshot.captured.songIds)
        assertEquals(
            listOf(ServiceExternalSongSnapshot.from(external)),
            queueSnapshot.captured.externalSongs,
        )
    }

    @Test
    fun nonRestorableExternalQueueClearsCrossProcessSnapshot() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val external = SongFixtures.song("external_unstable")
            .copy(source = SongSource.TRANSIENT_EXTERNAL)
        val item = ExternalMediaItemCodec.encode(
            ApplicationProvider.getApplicationContext(),
            external,
        )

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns item
        every { player.currentMediaItem } returns item

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
            externalSongResolver = { null },
        )
        coordinator.start()
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        coordinator.release()

        verify(atLeast = 1) { store.clear(any()) }
        verify(exactly = 0) { store.saveQueue(any(), any()) }
    }
}
