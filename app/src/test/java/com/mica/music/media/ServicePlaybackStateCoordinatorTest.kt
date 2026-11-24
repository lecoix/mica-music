package com.mica.music.media

import com.mica.music.data.playback.ServicePlaybackStateStore

import com.mica.music.data.playback.ServiceQueueSnapshot

import com.mica.music.data.playback.ServicePlaybackSnapshot

import com.mica.music.data.playback.ServiceExternalSongSnapshot
import com.mica.music.data.playback.ServiceRemoteSongSnapshot

import com.mica.music.audio.AudioQualityMode

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.SongSource
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
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
    fun timelinePersistenceIncludesCurrentRemoteSongSnapshot() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val track = RemoteTrackSummary(
            ref = RemoteTrackRef("nav-1", "track-9"),
            title = "Remote Nine",
            artist = "Artist",
            album = "Album",
            durationSec = 123,
            mimeTypeHint = "audio/flac",
            fileName = "nine.flac",
            suffix = "flac",
            sizeBytes = 12_345L,
            year = 2026,
            trackNumber = 9,
            discNumber = 1,
        )
        val item = RemoteMediaItemCodec.encode(track)
        val queueSnapshot = slot<ServiceQueueSnapshot>()

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } returns item
        every { player.currentMediaItem } returns item
        every { store.saveQueue(capture(queueSnapshot), any()) } returns Unit

        val coordinator = ServicePlaybackStateCoordinator(
            player = player,
            store = store,
            handler = handler,
            initialQualityMode = AudioQualityMode.HIFI,
        )
        coordinator.start()
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        coordinator.release()

        assertEquals(listOf(track.mediaId), queueSnapshot.captured.songIds)
        assertEquals(
            listOf(requireNotNull(ServiceRemoteSongSnapshot.fromMediaItem(item))),
            queueSnapshot.captured.remoteSongs,
        )
    }

    @Test
    fun untrustedRemoteQueueClearsCrossProcessSnapshot() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val mediaId = RemoteMediaIdCodec.encode(RemoteTrackRef("nav-1", "track-9"))
        val item = MediaItem.Builder().setMediaId(mediaId).build()

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
        )
        coordinator.start()
        listener.captured.onTimelineChanged(Timeline.EMPTY, 0)
        coordinator.release()

        verify(atLeast = 1) { store.clear(any()) }
        verify(exactly = 0) { store.saveQueue(any(), any()) }
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
    fun sourceUpdatesAndCursorChangesDoNotReadTheWholeRemoteQueue() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val tracks = List(5_000) { index ->
            RemoteTrackSummary(RemoteTrackRef("emby-qa", "$index"), "Track $index")
        }
        val items = tracks.map { RemoteMediaItemCodec.encode(it) }
        var index = 0
        var queueReads = 0
        var position = 0L
        val order = AcceptedPlaybackOrder(tracks.map { it.mediaId }, tracks.map { it.mediaId }, true)

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns items.size
        every { player.getMediaItemAt(any()) } answers {
            queueReads++
            items[firstArg<Int>()]
        }
        every { player.currentMediaItem } answers { items[index] }
        every { player.currentPosition } answers { position }

        val coordinator = ServicePlaybackStateCoordinator(
            player, store, handler, AudioQualityMode.HIFI, orderProvider = { order },
        )
        coordinator.start()
        try {
            listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            val readsAfterPlaylistChange = queueReads
            assertTrue(readsAfterPlaylistChange >= items.size)
            repeat(24) {
                listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            }
            index = 1
            position = 4_500L
            listener.captured.onMediaItemTransition(items[index], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            listener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            assertEquals(readsAfterPlaylistChange, queueReads)
        } finally {
            coordinator.release()
        }
        verify {
            store.saveCursor(match {
                it.currentSongId == items[1].mediaId && it.positionMs == 4_500L && it.shuffleEnabled
            }, true)
        }
    }

    @Test
    fun queuedMetadataReplacementAndCursorSurviveAnOlderInFlightWrite() {
        val store = mockk<ServicePlaybackStateStore>(relaxed = true)
        val handler = mockk<Handler>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val listener = slot<Player.Listener>()
        val oldTrack = RemoteTrackSummary(RemoteTrackRef("emby-qa", "song"), "Old title")
        val updatedTrack = oldTrack.copy(title = "New title")
        val oldSaveStarted = CountDownLatch(1)
        val allowOldSave = CountDownLatch(1)
        var mediaItem = RemoteMediaItemCodec.encode(oldTrack)
        var position = 0L
        val operations = mutableListOf<Pair<Long, String>>()

        every { store.load() } returns null
        every { player.addListener(capture(listener)) } returns Unit
        every { player.mediaItemCount } returns 1
        every { player.getMediaItemAt(0) } answers { mediaItem }
        every { player.currentMediaItem } answers { mediaItem }
        every { player.currentPosition } answers { position }
        every { store.saveQueue(any(), any()) } answers {
            val snapshot = firstArg<ServiceQueueSnapshot>()
            val title = snapshot.remoteSongs.single().title
            if (title == oldTrack.title) {
                oldSaveStarted.countDown()
                assertTrue(allowOldSave.await(5, TimeUnit.SECONDS))
            }
            operations += snapshot.revision to title
        }
        every { store.saveCursor(any(), any()) } answers {
            val cursor = firstArg<com.mica.music.data.playback.ServicePlaybackCursor>()
            operations += cursor.queueRevision to "cursor ${cursor.positionMs}"
        }

        val coordinator = ServicePlaybackStateCoordinator(player, store, handler, AudioQualityMode.HIFI)
        try {
            coordinator.start()
            listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            assertTrue(oldSaveStarted.await(5, TimeUnit.SECONDS))
            mediaItem = RemoteMediaItemCodec.encode(updatedTrack)
            position = 6_321L
            listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        } finally {
            allowOldSave.countDown()
            coordinator.release()
        }

        val oldIndex = operations.indexOfFirst { it.second == oldTrack.title }
        val updatedIndex = operations.indexOfFirst { it.second == updatedTrack.title }
        assertTrue(oldIndex >= 0 && updatedIndex > oldIndex)
        val updatedRevision = operations[updatedIndex].first
        assertTrue(updatedRevision > operations[oldIndex].first)
        assertEquals(-1, operations.take(updatedIndex).indexOfFirst { it.second == "cursor 6321" })
        assertEquals(updatedRevision to "cursor 6321", operations.last())
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
