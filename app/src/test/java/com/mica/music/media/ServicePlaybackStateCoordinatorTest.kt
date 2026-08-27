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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackStateCoordinatorTest {

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

    @Test
    fun clearingActiveQueueClearsSnapshotAfterOlderQueueSaveCompletes() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("one").build()
        val oldSaveStarted = CountDownLatch(1)
        val releaseOldSave = CountDownLatch(1)
        var mediaItemCount = 1
        var persistedSongIds = listOf("stale")

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } answers { mediaItemCount }
        every { player.getMediaItemAt(0) } returns item
        every { player.currentMediaItem } answers { if (mediaItemCount > 0) item else null }
        every { store.saveQueue(any(), any()) } answers {
            oldSaveStarted.countDown()
            assertTrue(releaseOldSave.await(5, TimeUnit.SECONDS))
            persistedSongIds = firstArg<ServiceQueueSnapshot>().songIds
        }
        every { store.clear(any()) } answers {
            persistedSongIds = emptyList()
        }

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        )
        coordinator.start()
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        assertTrue(oldSaveStarted.await(5, TimeUnit.SECONDS))

        mediaItemCount = 0
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        releaseOldSave.countDown()
        coordinator.release()

        assertTrue(persistedSongIds.isEmpty())
        verifyOrder {
            store.saveQueue(any(), any())
            store.clear(any())
        }
    }
}
