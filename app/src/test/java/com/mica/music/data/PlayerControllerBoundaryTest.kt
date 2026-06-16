package com.mica.music.data

import androidx.media3.session.MediaController
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerControllerBoundaryTest {

    @Test
    fun duplicateConnectIsIgnoredAndFailureAllowsRetry() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)

        controller.connectIfNeeded()
        controller.connectIfNeeded()
        assertEquals(1, connector.requests.size)

        connector.requests.single().onFailure(IllegalStateException("service unavailable"))
        assertFalse(controller.isConnected)
        assertTrue(controller.userMessage != null)

        controller.connectIfNeeded()
        assertEquals(2, connector.requests.size)
        controller.release()
        assertTrue(connector.requests.last().connection.cancelled)
    }

    @Test
    fun retryCancelsPreviousConnectionBeforeStartingAnother() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)

        controller.connectIfNeeded()
        val first = connector.requests.single().connection
        controller.retryConnect()

        assertTrue(first.cancelled)
        assertEquals(2, connector.requests.size)
        controller.release()
    }

    @Test
    fun restoringMissingSongClearsPersistedSession() {
        val storage = FakeSessionStorage()
        val controller = controller(storage = storage)
        controller.setQueue(SongFixtures.queue(2))

        controller.restoreSession(PlaybackSession("missing", 5_000))

        assertEquals(1, storage.clearCount)
        assertEquals(0, controller.currentIndex)
        controller.release()
    }

    @Test
    fun restoredSessionSelectsSongAndPositionWithoutAutoPlay() {
        val storage = FakeSessionStorage()
        val controller = controller(storage = storage)
        controller.setQueue(SongFixtures.queue(3))

        controller.restoreSession(PlaybackSession("song-2", 12_345))
        controller.reconcileRestoredSessionIndex()

        assertEquals("song-2", controller.currentSong?.id)
        assertEquals(12_345, controller.uiPositionMs())
        assertFalse(controller.isPlaying)

        controller.persistPlaybackSessionNow()
        assertEquals(PlaybackSession("song-2", 12_345), storage.saved)
        assertTrue(storage.savedSynchronously)
        controller.release()
    }

    private fun controller(
        connector: FakeConnector = FakeConnector(),
        storage: FakeSessionStorage = FakeSessionStorage(),
    ): PlayerController = PlayerController(
        context = ApplicationProvider.getApplicationContext(),
        mediaControllerConnector = connector,
        sessionStorage = storage,
        dispatcher = StandardTestDispatcher(),
    )

    @Test
    fun disconnectedControllerCanReconnect() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)

        controller.connectIfNeeded()
        assertEquals(1, connector.requests.size)
        connector.requests.single().onDisconnected()
        controller.connectIfNeeded()

        assertEquals(2, connector.requests.size)
        controller.release()
    }

    @Test
    fun playCountIsPublishedOnceOnlyAfterActualPlaybackStarts() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.duration } returns 60_000L
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onMediaItemTransition(
            item,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )
        assertEquals(0, count)
        listener.captured.onIsPlayingChanged(true)
        listener.captured.onIsPlayingChanged(true)

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun insertingExistingSongNextReplacesAuthoritativeQueueWithoutDuplicatingServiceItem() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
        )
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[1].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 1
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 12_345L
        every { mediaController.playWhenReady } returns true
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.playSong(1)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.insertPlayNext(queue[0])

        val submittedItems = slot<List<MediaItem>>()
        verify(exactly = 1) {
            mediaController.setMediaItems(capture(submittedItems), 0, 12_345L)
        }
        verify(exactly = 0) {
            mediaController.addMediaItem(any<Int>(), any())
        }
        assertEquals(
            listOf("song-b", "song-a", "song-c"),
            submittedItems.captured.map(MediaItem::mediaId),
        )
        assertEquals("song-b", controller.currentSong?.id)
        controller.release()
    }

    @Test
    fun startingRestoredSongConsumesRestoreAnchorSoLaterSeekCanAdvance() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("song-1", durationSec = 60)
        var playerPositionMs = 0L
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(song.id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.currentPosition } answers { playerPositionMs }
        every { mediaController.duration } returns 60_000L
        controller.setQueue(listOf(song))
        controller.restoreSession(PlaybackSession(song.id, 12_000))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(0)
        playerPositionMs = 30_000L
        controller.seekToMs(30_000)
        controller.syncPosition()

        assertEquals(30_000, controller.uiPositionMs())
        controller.release()
    }

    @Test
    fun nextOnSingleItemQueueDoesNotRestartCurrentSong() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("only-song")
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(song.id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        controller.setQueue(listOf(song))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.next()

        verify(exactly = 0) { mediaController.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { mediaController.play() }
        controller.release()
    }

    @Test
    fun playerErrorSurfacesMessageAndReleasesPendingSelection() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
        )
        val firstItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        val secondItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        var currentItem = firstItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(1)
        listener.captured.onMediaItemTransition(
            firstItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        assertEquals("song-b", controller.currentSong?.id)

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onPlayerError(
            PlaybackException(
                "decoder failed",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
        )
        assertEquals("decoder failed", controller.playbackError)

        currentItem = firstItem
        currentIndex = 0
        listener.captured.onMediaItemTransition(
            firstItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )

        assertEquals("song-a", controller.currentSong?.id)
        controller.release()
    }

    @Test
    fun rapidNextIgnoresCallbacksFromSupersededSelection() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
        )
        val firstItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        val secondItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        val thirdItem = MediaItem.Builder().setMediaId(queue[2].id).build()
        var currentItem = firstItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.next()
        controller.next()
        assertEquals("song-c", controller.currentSong?.id)

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onMediaItemTransition(
            secondItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        listener.captured.onPlayerError(
            PlaybackException(
                "old request failed",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
        )
        assertEquals("song-c", controller.currentSong?.id)
        assertNull(controller.playbackError)

        currentItem = thirdItem
        currentIndex = 2
        listener.captured.onMediaItemTransition(
            thirdItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )

        assertEquals("song-c", controller.currentSong?.id)
        controller.release()
    }

    @Test
    fun targetMediaIdWinsWhenPlayerIndexIsStillStale() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
            SongFixtures.song("song-d"),
        )
        var currentItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        var currentIndex = 0
        every { mediaController.addListener(any()) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(3)
        currentItem = MediaItem.Builder().setMediaId(queue[3].id).build()
        currentIndex = 1
        controller.syncPlaybackState()

        assertEquals("song-d", controller.currentSong?.id)
        assertEquals(3, controller.currentIndex)
        controller.release()
    }

    private class FakeConnector : MediaControllerConnector {
        val requests = mutableListOf<Request>()

        override fun connect(
            onConnected: (MediaController) -> Unit,
            onDisconnected: () -> Unit,
            onFailure: (Throwable) -> Unit,
        ): MediaControllerConnection {
            val request = Request(onConnected, onDisconnected, onFailure, FakeConnection())
            requests += request
            return request.connection
        }

        data class Request(
            val onConnected: (MediaController) -> Unit,
            val onDisconnected: () -> Unit,
            val onFailure: (Throwable) -> Unit,
            val connection: FakeConnection,
        )
    }

    private class FakeConnection : MediaControllerConnection {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeSessionStorage : PlaybackSessionStorage {
        var saved: PlaybackSession? = null
        var savedSynchronously = false
        var clearCount = 0

        override fun save(session: PlaybackSession?, sync: Boolean) {
            saved = session
            savedSynchronously = sync
        }

        override fun load(): PlaybackSession? = saved

        override fun clear() {
            clearCount++
            saved = null
        }
    }
}
