package com.mica.music.data

import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.mica.music.audio.AudioQualityMode
import com.mica.music.media.ConfirmedPlaybackBoundary
import com.mica.music.media.PendingPlaybackNavigation
import com.mica.music.media.PlaybackOutputAvailability
import com.mica.music.media.PlaybackOutputStatus
import com.mica.music.data.playback.ServiceExternalSongSnapshot
import com.mica.music.data.playback.ServicePlaybackSnapshot
import com.mica.music.data.playback.ServicePlaybackStateStore
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.playback.MediaControllerConnection
import com.mica.music.playback.PlayerController
import com.mica.music.playback.MediaControllerConnector
import com.mica.music.playback.PlaybackSessionStorage
import com.mica.music.playback.PlaybackExecutionState
import com.mica.music.testutil.SongFixtures
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayerControllerBoundaryTest {

    @Test
    fun playSingleSongWaitsForColdControllerConnectionThenStartsPlayback() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("external")
        every { mediaController.addListener(any()) } returns Unit
        every { mediaController.currentMediaItem } returns null
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 0L

        controller.playSingleSong(song)

        assertEquals(listOf(song.id), controller.playbackQueueState.queue.map { it.id })
        assertEquals(1, connector.requests.size)
        verify(exactly = 0) { mediaController.play() }

        connector.requests.single().onConnected(mediaController)

        verify(exactly = 1) { mediaController.play() }
        assertEquals(song.id, controller.playbackSurfaceState.currentSong?.id)
        controller.release()
    }

    @Test
    fun metadataRefreshBeforeConnectionReplacesSameIdPendingQueue() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val submittedItems = slot<List<MediaItem>>()
        val cached = SongFixtures.song("same-id").copy(title = "Cached title")
        val refreshed = cached.copy(title = "Refreshed title")
        every { mediaController.currentMediaItem } returns null
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 0L

        controller.setQueue(listOf(cached))
        controller.connectIfNeeded()
        controller.setQueue(listOf(refreshed))
        connector.requests.single().onConnected(mediaController)

        verify(exactly = 1) {
            mediaController.setMediaItems(capture(submittedItems), 0, 0L)
        }
        assertEquals("Refreshed title", SongMediaItemCodec.decode(submittedItems.captured.single())?.title)
        controller.release()
    }

    @Test
    fun playbackRefreshBeforeConnectionReplacesSameIdPendingQueue() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val submittedItems = slot<List<MediaItem>>()
        val cached = SongFixtures.song("same-id").copy(mediaUri = "content://cached")
        val refreshed = cached.copy(
            mediaUri = "content://refreshed",
            metadata = cached.metadata.copy(playbackMimeType = "audio/flac"),
        )
        every { mediaController.currentMediaItem } returns null
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 0L

        controller.setQueue(listOf(cached))
        controller.connectIfNeeded()
        controller.setQueue(listOf(refreshed))
        connector.requests.single().onConnected(mediaController)

        verify(exactly = 1) {
            mediaController.setMediaItems(capture(submittedItems), 0, 0L)
        }
        val submitted = SongMediaItemCodec.decode(submittedItems.captured.single())
        assertEquals("content://refreshed", submitted?.mediaUri)
        assertEquals("audio/flac", submitted?.metadata?.playbackMimeType)
        controller.release()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidEquivalentPlaylistChangesAreDebouncedAndRebuiltOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connector = FakeConnector()
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = List(4) { index -> SongFixtures.song("local-$index") }
        val playerQueue = List(4) { index -> SongFixtures.song("player-$index") }
        var resolverCalls = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(playerQueue[firstArg()].id).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(playerQueue[1].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 1
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 12_345L
        every { mediaController.duration } returns 60_000L
        val resolver = PlaybackSongResolver { id ->
            resolverCalls += 1
            playerQueue.firstOrNull { it.id == id }
        }
        val controller = controller(
            connector = connector,
            dispatcher = dispatcher,
            songResolver = resolver,
        )
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)

        assertEquals(0, resolverCalls)
        advanceTimeBy(99)
        runCurrent()
        assertEquals(0, resolverCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(playerQueue.size, resolverCalls)
        assertEquals(playerQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })

        listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(playerQueue.size, resolverCalls)
        controller.release()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pausedOldQueueMirrorCannotOverwriteNewLocalQueue() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        val workerScheduler = TestCoroutineScheduler()
        val workerDispatcher = StandardTestDispatcher(workerScheduler)
        val connector = FakeConnector()
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val localQueue = List(2) { index -> SongFixtures.song("local-$index") }
        val mirroredQueue = List(2) { index -> SongFixtures.song("mirrored-$index") }
        val newQueue = List(2) { index -> SongFixtures.song("new-$index") }
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.getMediaItemAt(any()) } answers {
            SongMediaItemCodec.encode(mirroredQueue[firstArg()])
        }
        every { mediaController.currentMediaItem } returns SongMediaItemCodec.encode(mirroredQueue[0])
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns mirroredQueue.size
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 60_000L
        val controller = controller(
            connector = connector,
            dispatcher = mainDispatcher,
            queueMirrorDispatcher = workerDispatcher,
            songResolver = PlaybackSongResolver { id -> mirroredQueue.firstOrNull { it.id == id } },
        )
        controller.setQueue(localQueue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onTimelineChanged(
            Timeline.EMPTY,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
        )
        advanceTimeBy(PlayerController.QUEUE_MIRROR_DEBOUNCE_MS)
        runCurrent()
        controller.setQueue(newQueue)

        workerScheduler.runCurrent()
        runCurrent()

        assertEquals(newQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })
        controller.release()
    }

    @Test
    fun alignedLargeQueueReorderUsesSingleIncrementalMove() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = List(4_500) { index -> SongFixtures.song("song-$index") }
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(queue[firstArg()].id).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[2_000].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2_000
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 12_345L
        every { mediaController.duration } returns 60_000L
        every { mediaController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.moveInQueue(fromIndex = 4_000, toIndex = 10)

        verify(exactly = 1) { mediaController.moveMediaItem(4_000, 10) }
        verify(exactly = 0) { mediaController.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        assertEquals("song-2000", controller.playbackSurfaceState.currentSong?.id)
        controller.release()
    }

    @Test
    fun misalignedQueueReorderFallsBackToSingleFullSync() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = List(4) { index -> SongFixtures.song("song-$index") }
        every { mediaController.getMediaItemAt(any()) } answers {
            val index = firstArg<Int>()
            val mediaId = if (index == 3) "stale-song" else queue[index].id
            MediaItem.Builder().setMediaId(mediaId).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[1].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 1
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 12_345L
        every { mediaController.duration } returns 60_000L
        every { mediaController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.moveInQueue(fromIndex = 3, toIndex = 0)

        verify(exactly = 0) { mediaController.moveMediaItem(any(), any()) }
        verify(exactly = 1) { mediaController.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        assertEquals("song-1", controller.playbackSurfaceState.currentSong?.id)
        controller.release()
    }

    @Test
    fun unsupportedSelectionSurfacesUserMessageBeforeExoPlayback() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val dff = SongFixtures.song(
            "dff",
            container = "DSD",
            mime = "audio/x-dsdiff",
            fileExtension = "dff",
        ).copy(fileName = "track.dff")
        val item = SongMediaItemCodec.encode(dff)
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.getMediaItemAt(0) } returns item
        controller.setQueue(listOf(dff))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(0)
        shadowOf(Looper.getMainLooper()).idle()

        val expected = "不支持 DFF/DSDIFF 格式，请使用 DSF"
        assertEquals(expected, controller.playbackSurfaceState.playbackError)
        assertEquals(expected, controller.userMessage?.text)
        listener.captured.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(expected, controller.playbackSurfaceState.playbackError)
        controller.release()
    }

    @Test
    fun dsdPlaybackUsesDefaultEffectiveTuningWithoutDroppingRequestedTuning() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val dsd = SongFixtures.song(
            "dsd",
            container = "FLAC",
            mime = "audio/flac",
            fileExtension = "dsf",
        )
        val flacItem = SongMediaItemCodec.encode(flac)
        val dsdItem = SongMediaItemCodec.encode(dsd)
        var currentItem = flacItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.getMediaItemAt(0) } returns flacItem
        every { mediaController.getMediaItemAt(1) } returns dsdItem
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 60_000L
        every { mediaController.playbackParameters } returns PlaybackParameters.DEFAULT
        controller.setQueue(listOf(flac, dsd))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.setPlaybackSpeed(2.0f)
        currentItem = dsdItem
        currentIndex = 1
        listener.captured.onMediaItemTransition(dsdItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.captured.onPlaybackParametersChanged(PlaybackParameters.DEFAULT)

        assertEquals(2.0f, controller.playbackSurfaceState.playbackTuning.speed, 0.0001f)
        verify {
            mediaController.setPlaybackParameters(match { it.speed == 1.0f && it.pitch == 1.0f })
        }
        controller.release()
    }

    @Test
    fun requestedTuningRestoresWhenLeavingDsdForSupportedPcm() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val dsd = SongFixtures.song("dsd", container = "DSD", mime = "audio/x-dsf")
        val flacItem = SongMediaItemCodec.encode(flac)
        val dsdItem = SongMediaItemCodec.encode(dsd)
        var currentItem = flacItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.getMediaItemAt(0) } returns flacItem
        every { mediaController.getMediaItemAt(1) } returns dsdItem
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 60_000L
        every { mediaController.playbackParameters } returns PlaybackParameters.DEFAULT
        controller.setQueue(listOf(flac, dsd))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.setPlaybackSpeed(1.5f)
        currentItem = dsdItem
        currentIndex = 1
        listener.captured.onMediaItemTransition(dsdItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.captured.onPlaybackParametersChanged(PlaybackParameters.DEFAULT)
        currentItem = flacItem
        currentIndex = 0
        listener.captured.onMediaItemTransition(flacItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(1.5f, controller.playbackSurfaceState.playbackTuning.speed, 0.0001f)
        verify {
            mediaController.setPlaybackParameters(match { it.speed == 1.5f })
        }
        controller.release()
    }

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
    fun callbacksFromSupersededConnectionCannotReplaceActiveConnection() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val queue = listOf(SongFixtures.song("song-a"), SongFixtures.song("song-b"))
        val oldMediaController = mockk<MediaController>(relaxed = true)
        val activeMediaController = mockk<MediaController>(relaxed = true)
        val activeItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        every { activeMediaController.currentMediaItem } returns activeItem
        every { activeMediaController.currentMediaItemIndex } returns 1
        every { activeMediaController.mediaItemCount } returns queue.size
        every { activeMediaController.getMediaItemAt(0) } returns SongMediaItemCodec.encode(queue[0])
        every { activeMediaController.getMediaItemAt(1) } returns activeItem
        every { activeMediaController.duration } returns 60_000L
        controller.setQueue(queue)

        controller.connectIfNeeded()
        val superseded = connector.requests.single()
        controller.retryConnect()
        val active = connector.requests.last()
        active.onConnected(activeMediaController)

        superseded.onConnected(oldMediaController)
        superseded.onFailure(IllegalStateException("stale failure"))
        superseded.onDisconnected()

        assertTrue(controller.isConnected)
        assertEquals("song-b", controller.playbackSurfaceState.currentSong?.id)
        assertNull(controller.userMessage)
        verify(exactly = 0) { oldMediaController.addListener(any()) }
        controller.release()
    }

    @Test
    fun listenerFromSupersededControllerCannotMutateActivePlaybackState() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val queue = listOf(SongFixtures.song("song-a"), SongFixtures.song("song-b"))
        val oldMediaController = mockk<MediaController>(relaxed = true)
        val activeMediaController = mockk<MediaController>(relaxed = true)
        val oldListener = slot<Player.Listener>()
        val oldItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        val activeItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        every { oldMediaController.addListener(capture(oldListener)) } returns Unit
        every { oldMediaController.currentMediaItem } returns oldItem
        every { oldMediaController.currentMediaItemIndex } returns 0
        every { oldMediaController.mediaItemCount } returns queue.size
        every { oldMediaController.getMediaItemAt(0) } returns oldItem
        every { oldMediaController.getMediaItemAt(1) } returns activeItem
        every { oldMediaController.duration } returns 60_000L
        every { activeMediaController.currentMediaItem } returns activeItem
        every { activeMediaController.currentMediaItemIndex } returns 1
        every { activeMediaController.mediaItemCount } returns queue.size
        every { activeMediaController.getMediaItemAt(0) } returns oldItem
        every { activeMediaController.getMediaItemAt(1) } returns activeItem
        every { activeMediaController.duration } returns 60_000L
        controller.setQueue(queue)

        controller.connectIfNeeded()
        connector.requests.single().onConnected(oldMediaController)
        controller.retryConnect()
        connector.requests.last().onConnected(activeMediaController)

        oldListener.captured.onIsPlayingChanged(true)
        oldListener.captured.onPlayerError(
            PlaybackException(
                "stale controller failure",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
        )

        assertTrue(controller.isConnected)
        assertEquals("song-b", controller.playbackSurfaceState.currentSong?.id)
        assertFalse(controller.playbackSurfaceState.isPlaying)
        assertNull(controller.playbackSurfaceState.playbackError)
        controller.release()
    }

    @Test
    fun restoringMissingSongClearsPersistedSession() {
        val storage = FakeSessionStorage()
        val controller = controller(storage = storage)
        controller.setQueue(SongFixtures.queue(2))

        controller.restoreSession(PlaybackSession("missing", 5_000))

        assertEquals(1, storage.clearCount)
        assertEquals(0, controller.playbackQueueState.currentIndex)
        controller.release()
    }

    @Test
    fun restoredSessionSelectsSongAndPositionWithoutAutoPlay() {
        val storage = FakeSessionStorage()
        val controller = controller(storage = storage)
        controller.setQueue(SongFixtures.queue(3))

        controller.restoreSession(PlaybackSession("song-2", 12_345))
        controller.reconcileRestoredSessionIndex()

        assertEquals("song-2", controller.playbackSurfaceState.currentSong?.id)
        assertEquals(12_345, controller.uiPositionMs())
        assertFalse(controller.playbackSurfaceState.isPlaying)

        controller.persistPlaybackSessionNow()
        assertEquals(PlaybackSession("song-2", 12_345), storage.saved)
        assertTrue(storage.savedSynchronously)
        controller.release()
    }

    @Test
    fun coldStartPendingQueuePublishesSavedPositionOnFirstControllerConnection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val queue = SongFixtures.queue(2)
        val connector = FakeConnector()
        val controller = controller(
            connector = connector,
            songResolver = PlaybackSongResolver { id -> queue.firstOrNull { it.id == id } },
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        val submittedItems = slot<List<MediaItem>>()
        store.clear(sync = true)
        store.save(
            ServicePlaybackSnapshot(
                queueSongIds = queue.map { it.id },
                currentIndex = 1,
                positionMs = 42_000L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = false,
                qualityMode = AudioQualityMode.HIFI,
            ),
            sync = true,
        )
        val restoredItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentMediaItem } returns restoredItem
        every { mediaController.currentMediaItemIndex } returns 1
        every { mediaController.currentPosition } returns 42_000L
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } returns false

        try {
            assertTrue(controller.bootstrapQueue { id -> queue.firstOrNull { it.id == id } })
            controller.connectIfNeeded()
            connector.requests.single().onConnected(mediaController)

            verify(exactly = 1) {
                mediaController.setMediaItems(capture(submittedItems), 1, 42_000L)
            }
            assertEquals(queue.map { it.id }, submittedItems.captured.map { it.mediaId })
            assertEquals(42_000, controller.uiPositionMs())
        } finally {
            controller.release()
            store.clear(sync = true)
        }
    }

    @Test
    fun selectingAnotherSongAfterColdStartRestoreStartsAtBeginning() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val queue = SongFixtures.queue(2)
        val connector = FakeConnector()
        val controller = controller(
            connector = connector,
            songResolver = PlaybackSongResolver { id -> queue.firstOrNull { it.id == id } },
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        store.clear(sync = true)
        store.save(
            ServicePlaybackSnapshot(
                queueSongIds = queue.map { it.id },
                currentIndex = 0,
                positionMs = 42_000L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = false,
                qualityMode = AudioQualityMode.HIFI,
            ),
            sync = true,
        )
        every { mediaController.mediaItemCount } returns 0

        try {
            assertTrue(controller.bootstrapQueue { id -> queue.firstOrNull { it.id == id } })
            controller.connectIfNeeded()
            connector.requests.single().onConnected(mediaController)
            clearMocks(mediaController, answers = false, recordedCalls = true)

            controller.playSong(1)

            verify(exactly = 1) { mediaController.seekTo(1, 0L) }
        } finally {
            controller.release()
            store.clear(sync = true)
        }
    }

    @Test
    fun coldStartRestoreReleasesUiPositionWhenPlaybackResumes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val queue = SongFixtures.queue(2)
        val connector = FakeConnector()
        val controller = controller(
            connector = connector,
            songResolver = PlaybackSongResolver { id -> queue.firstOrNull { it.id == id } },
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        val restoredItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        var playerPositionMs = 622L
        store.clear(sync = true)
        store.save(
            ServicePlaybackSnapshot(
                queueSongIds = queue.map { it.id },
                currentIndex = 0,
                positionMs = 622L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = false,
                qualityMode = AudioQualityMode.HIFI,
            ),
            sync = true,
        )
        every { mediaController.currentMediaItem } returns restoredItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } answers { playerPositionMs }
        every { mediaController.duration } returns 60_000L

        try {
            assertTrue(controller.bootstrapQueue { id -> queue.firstOrNull { it.id == id } })
            controller.connectIfNeeded()
            connector.requests.single().onConnected(mediaController)
            controller.togglePlay()

            playerPositionMs = 128_406L
            controller.syncPosition()

            assertEquals(128_406, controller.uiPositionMs())
        } finally {
            controller.release()
            store.clear(sync = true)
        }
    }

    @Test
    fun ordinaryControllerSamplesDoNotPublishBackwardJitter() {
        val connector = FakeConnector()
        var nowMs = 1_000L
        val controller = controller(connector = connector, monotonicNowMs = { nowMs })
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("clock-jitter")
        val item = MediaItem.Builder().setMediaId(song.id).build()
        var playerPositionMs = 12_207L
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.currentPosition } answers { playerPositionMs }
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } returns true
        every { mediaController.playbackParameters } returns PlaybackParameters.DEFAULT

        try {
            controller.setQueue(listOf(song))
            controller.connectIfNeeded()
            connector.requests.single().onConnected(mediaController)
            controller.syncPosition()
            assertEquals(12_207, controller.playbackProgressState.positionMs)

            nowMs = 1_050L
            playerPositionMs = 12_206L
            controller.syncPosition()
            assertEquals(12_257, controller.playbackProgressState.positionMs)

            nowMs = 1_100L
            playerPositionMs = 12_310L
            controller.syncPosition()
            assertEquals(12_307, controller.playbackProgressState.positionMs)
        } finally {
            controller.release()
        }
    }

    @Test
    fun coldStartBootstrapHydratesPersistedExternalSongWhenLibraryResolverMisses() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val external = SongFixtures.song("external_test").copy(source = SongSource.TRANSIENT_EXTERNAL)
        val controller = controller()
        store.clear(sync = true)
        store.save(
            ServicePlaybackSnapshot(
                queueSongIds = listOf(external.id),
                currentIndex = 0,
                positionMs = 2_000L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = false,
                qualityMode = AudioQualityMode.HIFI,
                externalSongs = listOf(ServiceExternalSongSnapshot.from(external)),
            ),
            sync = true,
        )

        try {
            assertTrue(controller.bootstrapQueue { null })
            assertEquals(external.id, controller.playbackSurfaceState.currentSong?.id)
            assertEquals(SongSource.TRANSIENT_EXTERNAL, controller.playbackSurfaceState.currentSong?.source)
            assertEquals(2_000, controller.uiPositionMs())
        } finally {
            controller.release()
            store.clear(sync = true)
        }
    }

    private fun positionInfo(
        item: MediaItem,
        mediaItemIndex: Int,
        positionMs: Long,
    ) = Player.PositionInfo(
        null,
        mediaItemIndex,
        item,
        null,
        mediaItemIndex,
        positionMs,
        positionMs,
        -1,
        -1,
    )

    private fun controller(
        connector: FakeConnector = FakeConnector(),
        storage: FakeSessionStorage = FakeSessionStorage(),
        songResolver: PlaybackSongResolver = PlaybackSongResolver { null },
        outputStatusFlow: StateFlow<PlaybackOutputStatus> = MutableStateFlow(PlaybackOutputStatus()),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
        queueMirrorDispatcher: CoroutineDispatcher = dispatcher,
        monotonicNowMs: () -> Long = { 0L },
    ): PlayerController = PlayerController(
        context = ApplicationProvider.getApplicationContext(),
        mediaControllerConnector = connector,
        sessionStorage = storage,
        songResolver = songResolver,
        outputStatusFlow = outputStatusFlow,
        dispatcher = dispatcher,
        queueMirrorDispatcher = queueMirrorDispatcher,
        monotonicNowMs = monotonicNowMs,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun outputStatusUpdatesSurfaceWithoutPretendingMediaExecutionChanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val output = MutableStateFlow(
            PlaybackOutputStatus(
                revision = 1L,
                availability = PlaybackOutputAvailability.STABLE,
            ),
        )
        val connector = FakeConnector()
        val controller = controller(
            connector = connector,
            outputStatusFlow = output,
            dispatcher = dispatcher,
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("usb-transition")
        every { mediaController.currentMediaItem } returns MediaItem.Builder().setMediaId(song.id).build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.playbackState } returns Player.STATE_READY
        every { mediaController.playWhenReady } returns false
        every { mediaController.isPlaying } returns false

        controller.setQueue(listOf(song))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        runCurrent()
        assertEquals(PlaybackExecutionState.PAUSED, controller.playbackSurfaceState.playbackStatus.execution)

        output.value = PlaybackOutputStatus(
            revision = 2L,
            availability = PlaybackOutputAvailability.WAITING_FOR_PERMISSION,
            pendingPlayIntent = true,
        )
        runCurrent()

        assertEquals(PlaybackExecutionState.PAUSED, controller.playbackSurfaceState.playbackStatus.execution)
        assertEquals(
            com.mica.music.playback.PlaybackIntent.PLAY,
            controller.playbackSurfaceState.playbackStatus.intent,
        )
        assertEquals(
            PlaybackOutputAvailability.WAITING_FOR_PERMISSION,
            controller.playbackSurfaceState.playbackStatus.outputAvailability,
        )
        controller.release()
    }

    @Test
    fun disconnectedControllerCannotLeaveActualPlaybackMarkedPlaying() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val song = SongFixtures.song("disconnect-playing")
        every { mediaController.currentMediaItem } returns MediaItem.Builder().setMediaId(song.id).build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.playbackState } returns Player.STATE_READY
        every { mediaController.playWhenReady } returns true
        every { mediaController.isPlaying } returns true

        controller.setQueue(listOf(song))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        assertTrue(controller.playbackSurfaceState.isPlaying)

        connector.requests.single().onDisconnected()

        assertFalse(controller.playbackSurfaceState.isPlaying)
        assertEquals(
            PlaybackExecutionState.UNAVAILABLE,
            controller.playbackSurfaceState.playbackStatus.execution,
        )
        controller.release()
    }

    @Test
    fun playCountIsPublishedOnceOnlyAfterActualPlaybackStarts() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val firstItem = MediaItem.Builder().setMediaId("song-1").build()
        val secondItem = MediaItem.Builder().setMediaId("song-2").build()
        var currentItem = firstItem
        var currentIndex = 0
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } answers { playing }
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1"), SongFixtures.song("song-2")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onPositionDiscontinuity(
            positionInfo(firstItem, 0, 59_900L),
            positionInfo(secondItem, 1, 0L),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onMediaItemTransition(
            secondItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-2", 59_900L, 0L),
        )
        assertEquals(0, count)
        playing = true
        listener.captured.onIsPlayingChanged(true)
        listener.captured.onIsPlayingChanged(true)

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun playCountIsPublishedWhenIndexSyncPrecedesAutoTransition() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val firstItem = MediaItem.Builder().setMediaId("song-1").build()
        val secondItem = MediaItem.Builder().setMediaId("song-2").build()
        var currentItem = firstItem
        var currentIndex = 0
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.duration } returns 60_000L
        every { mediaController.currentPosition } returns 0L
        every { mediaController.isPlaying } answers { playing }
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1"), SongFixtures.song("song-2")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        currentItem = secondItem
        currentIndex = 1
        controller.syncPlaybackState()
        listener.captured.onPositionDiscontinuity(
            positionInfo(firstItem, 0, 59_900L),
            positionInfo(secondItem, 1, 0L),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onMediaItemTransition(
            secondItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-2", 59_900L, 0L),
        )
        playing = true
        listener.captured.onIsPlayingChanged(true)

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun playCountIsPublishedForManualExistingItemSeekTransition() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val firstItem = MediaItem.Builder().setMediaId("song-1").build()
        val secondItem = MediaItem.Builder().setMediaId("song-2").build()
        val queue = listOf(SongFixtures.song("song-1"), SongFixtures.song("song-2"))
        var currentItem = firstItem
        var currentIndex = 0
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.getMediaItemAt(0) } returns firstItem
        every { mediaController.getMediaItemAt(1) } returns secondItem
        every { mediaController.duration } returns 60_000L
        every { mediaController.currentPosition } returns 0L
        every { mediaController.isPlaying } answers { playing }
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(1)
        currentItem = secondItem
        currentIndex = 1
        listener.captured.onMediaItemTransition(
            secondItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        playing = true
        listener.captured.onIsPlayingChanged(true)

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun explicitlyReplayingCurrentSongPublishesOneNewPlaySession() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.getMediaItemAt(0) } returns item
        every { mediaController.duration } returns 60_000L
        every { mediaController.currentPosition } returns 20_000L
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.playSong(0)
        assertEquals(0, count)
        listener.captured.onPositionDiscontinuity(
            positionInfo(item, 0, 20_000L),
            positionInfo(item, 0, 0L),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun playCountIsNotPublishedOnSeekOrResumeFromPause() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val firstItem = MediaItem.Builder().setMediaId("song-1").build()
        val secondItem = MediaItem.Builder().setMediaId("song-2").build()
        var currentItem = firstItem
        var currentIndex = 0
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } answers { playing }
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1"), SongFixtures.song("song-2")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onPositionDiscontinuity(
            positionInfo(firstItem, 0, 59_900L),
            positionInfo(secondItem, 1, 0L),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onMediaItemTransition(secondItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-2", 59_900L, 0L),
        )
        playing = true
        listener.captured.onIsPlayingChanged(true)
        assertEquals(1, count)

        playing = false
        listener.captured.onIsPlayingChanged(false)
        listener.captured.onMediaItemTransition(secondItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        playing = true
        listener.captured.onIsPlayingChanged(true)

        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun playCountIsNotPublishedOnPlaylistRefreshForSameSong() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } answers { playing }
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onMediaItemTransition(
            item,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        playing = true
        listener.captured.onIsPlayingChanged(true)

        assertEquals(0, count)
        controller.release()
    }

    @Test
    fun playCountIsNotPublishedOnAutoTransitionForSameSong() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        val metadataItem = item.buildUpon()
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("current lyric")
                    .build(),
            )
            .build()
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns metadataItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onMediaItemTransition(
            metadataItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))

        assertEquals(0, count)
        controller.release()
    }

    @Test
    fun playCountIsNotPublishedWhenMetadataReplacementIsReportedAsRepeat() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        val metadataItem = item.buildUpon()
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("current lyric")
                    .build(),
            )
            .build()
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns metadataItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onMediaItemTransition(
            metadataItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))

        assertEquals(0, count)
        controller.release()
    }

    @Test
    fun playCountIsPublishedOncePerConfirmedRepeatBoundary() {
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
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        val oldPosition = positionInfo(item, 0, 59_900L)
        val newPosition = positionInfo(item, 0, 0L)
        repeat(2) {
            listener.captured.onMediaItemTransition(
                item,
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            )
            listener.captured.onPositionDiscontinuity(
                oldPosition,
                newPosition,
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            )
        }
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        assertEquals(0, count)

        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-1", 59_900L, 0L),
        )
        assertEquals(1, count)

        listener.captured.onMediaItemTransition(
            item,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        assertEquals(1, count)

        listener.captured.onPositionDiscontinuity(
            oldPosition,
            newPosition,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onMediaItemTransition(
            item,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        assertEquals(1, count)

        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-1", 59_900L, 0L),
        )

        assertEquals(2, count)
        controller.release()
    }

    @Test
    fun repeatEvidenceDoesNotLeakAcrossControllerEventBatches() {
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
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        val oldPosition = positionInfo(item, 0, 59_900L)
        val newPosition = positionInfo(item, 0, 0L)

        listener.captured.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        listener.captured.onPositionDiscontinuity(
            oldPosition,
            newPosition,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        assertEquals(0, count)

        listener.captured.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        listener.captured.onPositionDiscontinuity(
            oldPosition,
            newPosition,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))

        assertEquals(0, count)
        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-1", 59_900L, 0L),
        )
        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun automaticNextCountsOnceWhenTransitionPrecedesBoundaryAndCallbacksRepeat() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val firstItem = MediaItem.Builder().setMediaId("song-1").build()
        val secondItem = MediaItem.Builder().setMediaId("song-2").build()
        var currentItem = firstItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.duration } returns 60_000L
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1"), SongFixtures.song("song-2")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        currentItem = secondItem
        currentIndex = 1

        repeat(2) {
            listener.captured.onMediaItemTransition(
                secondItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            )
            listener.captured.onPositionDiscontinuity(
                positionInfo(firstItem, 0, 59_900L),
                positionInfo(secondItem, 1, 0L),
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            )
        }
        listener.captured.onEvents(mediaController, mockk(relaxed = true))

        assertEquals(0, count)
        connector.requests.single().onPlaybackBoundary(
            ConfirmedPlaybackBoundary("song-1", "song-2", 59_900L, 0L),
        )
        assertEquals(1, count)
        controller.release()
    }

    @Test
    fun seekFromEndToStartDiscontinuityDoesNotPublishPlayCount() {
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
        every { mediaController.isPlaying } returns true
        var count = 0
        controller.onSongPlayStarted = { count++ }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onPositionDiscontinuity(
            positionInfo(item, 0, 59_900L),
            positionInfo(item, 0, 0L),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        listener.captured.onEvents(mediaController, mockk(relaxed = true))
        listener.captured.onIsPlayingChanged(true)

        assertEquals(0, count)
        controller.release()
    }

    @Test
    fun listenSecondsArePublishedWhenPlaybackPauses() {
        val connector = FakeConnector()
        var nowMs = 1_000L
        val controller = controller(connector = connector, monotonicNowMs = { nowMs })
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val item = MediaItem.Builder().setMediaId("song-1").build()
        var playing = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns item
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.duration } returns 60_000L
        every { mediaController.currentPosition } returns 0L
        every { mediaController.isPlaying } answers { playing }
        val listened = mutableListOf<Pair<String, Long>>()
        controller.onSongListenSecondsAdded = { songId, seconds -> listened += songId to seconds }
        controller.setQueue(listOf(SongFixtures.song("song-1")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        listener.captured.onMediaItemTransition(
            item,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )
        playing = true
        listener.captured.onIsPlayingChanged(true)
        nowMs += 65_400L
        playing = false
        listener.captured.onIsPlayingChanged(false)

        assertEquals(listOf("song-1" to 65L), listened)
        controller.release()
    }

    @Test
    fun listenSecondsAreClosedAgainstPreviousSongOnTransition() {
        val connector = FakeConnector()
        var nowMs = 0L
        val controller = controller(connector = connector, monotonicNowMs = { nowMs })
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val first = MediaItem.Builder().setMediaId("song-a").build()
        val second = MediaItem.Builder().setMediaId("song-b").build()
        var currentItem = first
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.duration } returns 60_000L
        every { mediaController.currentPosition } returns 0L
        every { mediaController.isPlaying } returns true
        every { mediaController.getMediaItemAt(0) } returns first
        every { mediaController.getMediaItemAt(1) } returns second
        val listened = mutableListOf<Pair<String, Long>>()
        controller.onSongListenSecondsAdded = { songId, seconds -> listened += songId to seconds }
        controller.setQueue(listOf(SongFixtures.song("song-a"), SongFixtures.song("song-b")))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        listener.captured.onMediaItemTransition(first, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        listener.captured.onIsPlayingChanged(true)
        nowMs += 90_000L
        currentItem = second
        currentIndex = 1
        listener.captured.onMediaItemTransition(second, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        nowMs += 10_000L
        listener.captured.onIsPlayingChanged(false)

        assertEquals(listOf("song-a" to 90L, "song-b" to 10L), listened)
        controller.release()
    }

    @Test
    fun cyclePlaybackQueueModeSendsCommandAndWaitsForControllerCallbackMirror() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        var repeatMode = Player.REPEAT_MODE_OFF
        var shuffleEnabled = false
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.repeatMode } answers { repeatMode }
        every { mediaController.shuffleModeEnabled } answers { shuffleEnabled }
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.isPlaying } returns false
        every { mediaController.duration } returns 0L
        every { mediaController.currentPosition } returns 0L
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.cyclePlaybackQueueMode()

        verify { mediaController.shuffleModeEnabled = false }
        verify { mediaController.repeatMode = Player.REPEAT_MODE_ALL }
        assertEquals(PlaybackQueueMode.OFF, controller.playbackSurfaceState.playbackQueueMode)

        repeatMode = Player.REPEAT_MODE_ALL
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ALL)

        assertEquals(PlaybackQueueMode.REPEAT_ALL, controller.playbackSurfaceState.playbackQueueMode)
        controller.release()
    }

    @Test
    fun shuffleModeBuildsAppPlaybackOrderWithoutEnablingMedia3Shuffle() {
        val connector = FakeConnector()
        val storage = FakeSessionStorage()
        val controller = controller(connector = connector, storage = storage)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(6)
        var repeatMode = Player.REPEAT_MODE_OFF
        var playbackState = Player.STATE_READY
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.repeatMode } answers { repeatMode }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[2].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 10_000L
        every { mediaController.playWhenReady } returns true
        every { mediaController.playbackState } answers { playbackState }
        every { mediaController.duration } returns 60_000L
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(queue[firstArg()].id).build()
        }
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.playSong(2)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.cyclePlaybackQueueMode()
        repeatMode = Player.REPEAT_MODE_ALL
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ALL)
        controller.cyclePlaybackQueueMode()
        repeatMode = Player.REPEAT_MODE_ONE
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ONE)
        controller.cyclePlaybackQueueMode()

        verify { mediaController.shuffleModeEnabled = false }
        verify { mediaController.repeatMode = Player.REPEAT_MODE_OFF }
        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(true, storage.saved?.shuffleEnabled)
        assertEquals(true, storage.savedSynchronously)
        assertEquals(queue.map { it.id }, storage.saved?.shuffleSourceIds)
        assertEquals(queue[2].id, controller.playbackSurfaceState.currentSong?.id)
        assertEquals(queue.map { it.id }.toSet(), controller.playbackQueueState.queue.map { it.id }.toSet())
        assertEquals(queue.size, controller.playbackQueueState.queue.distinctBy { it.id }.size)

        clearMocks(mediaController, answers = false, recordedCalls = true)
        playbackState = Player.STATE_ENDED
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(PlaybackExecutionState.ENDED, controller.playbackSurfaceState.playbackStatus.execution)
        assertFalse(controller.playbackSurfaceState.playbackStatus.showsPauseAction)
        verify(exactly = 0) { mediaController.play() }
        verify(exactly = 0) { mediaController.prepare() }
        verify(exactly = 0) { mediaController.seekTo(any<Long>()) }
        verify(exactly = 0) { mediaController.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { mediaController.seekToNext() }

        clearMocks(mediaController, answers = false, recordedCalls = true)
        controller.togglePlay()

        verify(exactly = 1) { mediaController.seekTo(2, 0L) }
        verify(exactly = 1) { mediaController.play() }
        controller.release()
    }

    @Test
    fun shuffleNextKeepsAdjacentTargetIndexInsteadOfReanchoringToZero() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(6)
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.repeatMode } returns Player.REPEAT_MODE_OFF
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[2].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 10_000L
        every { mediaController.playWhenReady } returns true
        every { mediaController.duration } returns 60_000L
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(controller.playbackQueueState.queue[firstArg()].id).build()
        }
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.playSong(2)
        controller.cyclePlaybackQueueMode()
        val target = controller.manualNextTarget()
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.next()
        shadowOf(Looper.getMainLooper()).idle()

        verify { mediaController.seekTo(target ?: -1, 0L) }
        assertEquals(target, controller.playbackQueueState.currentIndex)
        controller.release()
    }

    @Test
    fun shuffleModeCanBeTurnedOffWithoutWaitingForPlayerCallback() {
        val connector = FakeConnector()
        val storage = FakeSessionStorage()
        val controller = controller(connector = connector, storage = storage)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(4)
        var repeatMode = Player.REPEAT_MODE_OFF
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[0].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 0L
        every { mediaController.playWhenReady } returns false
        every { mediaController.duration } returns 60_000L
        every { mediaController.repeatMode } answers { repeatMode }
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(controller.playbackQueueState.queue[firstArg()].id).build()
        }
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        controller.cyclePlaybackQueueMode()
        repeatMode = Player.REPEAT_MODE_ALL
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ALL)
        controller.cyclePlaybackQueueMode()
        repeatMode = Player.REPEAT_MODE_ONE
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ONE)
        controller.cyclePlaybackQueueMode()
        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(true, storage.saved?.shuffleEnabled)
        assertEquals(true, storage.savedSynchronously)
        assertEquals(queue.map { it.id }, storage.saved?.shuffleSourceIds)

        controller.cyclePlaybackQueueMode()

        assertEquals(PlaybackQueueMode.OFF, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(false, storage.saved?.shuffleEnabled)
        assertTrue(storage.saved?.shuffleSourceIds.isNullOrEmpty())
        assertEquals(queue.map { it.id }, controller.playbackQueueState.queue.map { it.id })
        verify(atLeast = 1) { mediaController.shuffleModeEnabled = false }
        controller.release()
    }

    @Test
    fun connectedServiceQueueRestoresShuffleModeEvenWithoutBootstrap() {
        val sourceQueue = SongFixtures.queue(5)
        val storage = FakeSessionStorage().apply {
            saved = PlaybackSession(
                songId = sourceQueue[2].id,
                positionMs = 12_000,
                shuffleEnabled = true,
                shuffleSourceIds = sourceQueue.map { it.id },
            )
        }
        val connector = FakeConnector()
        val controller = controller(connector = connector, storage = storage)
        val mediaController = mockk<MediaController>(relaxed = true)
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(sourceQueue[2].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2
        every { mediaController.mediaItemCount } returns sourceQueue.size
        every { mediaController.repeatMode } returns Player.REPEAT_MODE_OFF
        every { mediaController.shuffleModeEnabled } returns false
        every { mediaController.duration } returns 60_000L
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(sourceQueue[firstArg()].id).build()
        }

        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
        controller.release()
    }

    @Test
    fun serviceWinsColdStartRestoresShuffleModeWithoutChangingPersistedPlaybackOrder() {
        val sourceQueue = SongFixtures.queue(5)
        val playbackQueue = listOf(
            sourceQueue[2],
            sourceQueue[4],
            sourceQueue[0],
            sourceQueue[3],
            sourceQueue[1],
        )
        val storage = FakeSessionStorage().apply {
            saved = PlaybackSession(
                songId = playbackQueue[2].id,
                positionMs = 12_000,
                shuffleEnabled = true,
                shuffleSourceIds = sourceQueue.map { it.id },
            )
        }
        val connector = FakeConnector()
        val controller = controller(
            connector = connector,
            storage = storage,
            songResolver = PlaybackSongResolver { id -> sourceQueue.firstOrNull { it.id == id } },
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(playbackQueue[2].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2
        every { mediaController.mediaItemCount } returns playbackQueue.size
        every { mediaController.currentPosition } returns 12_000L
        every { mediaController.duration } returns 60_000L
        every { mediaController.repeatMode } returns Player.REPEAT_MODE_OFF
        every { mediaController.shuffleModeEnabled } returns false
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(playbackQueue[firstArg()].id).build()
        }

        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)

        assertTrue(controller.bootstrapQueue { id -> sourceQueue.firstOrNull { it.id == id } })
        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(playbackQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })

        controller.cyclePlaybackQueueMode()

        assertEquals(PlaybackQueueMode.OFF, controller.playbackSurfaceState.playbackQueueMode)
        assertEquals(sourceQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })
        assertEquals(false, storage.saved?.shuffleEnabled)
        controller.release()
    }

    @Test
    fun snapshotColdStartRestoresShufflePlaybackOrderWithoutReshuffling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val sourceQueue = SongFixtures.queue(5)
        val playbackQueue = listOf(
            sourceQueue[3],
            sourceQueue[1],
            sourceQueue[4],
            sourceQueue[0],
            sourceQueue[2],
        )
        val storage = FakeSessionStorage().apply {
            saved = PlaybackSession(
                songId = playbackQueue[1].id,
                positionMs = 7_000,
                shuffleEnabled = true,
                shuffleSourceIds = sourceQueue.map { it.id },
            )
        }
        val controller = controller(
            storage = storage,
            songResolver = PlaybackSongResolver { id -> sourceQueue.firstOrNull { it.id == id } },
        )
        store.clear(sync = true)
        store.save(
            ServicePlaybackSnapshot(
                queueSongIds = playbackQueue.map { it.id },
                currentIndex = 1,
                positionMs = 7_000L,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playWhenReady = false,
                qualityMode = AudioQualityMode.HIFI,
            ),
            sync = true,
        )

        try {
            assertTrue(controller.bootstrapQueue { id -> sourceQueue.firstOrNull { it.id == id } })
            assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackSurfaceState.playbackQueueMode)
            assertEquals(playbackQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })
            assertEquals(playbackQueue[1].id, controller.playbackSurfaceState.currentSong?.id)
        } finally {
            controller.release()
            store.clear(sync = true)
        }
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
        assertEquals("song-b", controller.playbackSurfaceState.currentSong?.id)
        controller.release()
    }

    @Test
    fun playSongSwitchSkipsQueuePayloadWhenServiceTargetIsAlreadyAligned() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
        )
        // Binder 侧常见：timeline 不是 SessionMediaTimeline 子类
        every { mediaController.currentTimeline } returns Timeline.EMPTY
        every { mediaController.getCurrentTimeline() } returns Timeline.EMPTY
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(queue[firstArg()].id).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[0].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 4_000L
        every { mediaController.playWhenReady } returns true
        every { mediaController.isPlaying } returns true
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.playSong(0)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.playSong(2)

        val navigation = PendingPlaybackNavigation.consumeNavigationOverride()
        assertNull(navigation)
        verify(exactly = 0) { mediaController.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(exactly = 1) {
            mediaController.seekTo(2, 0L)
        }
        controller.release()
    }

    @Test
    fun laterSetQueueSupersedesPendingAtomicQueuePlaybackBeforeConnection() {
        PendingPlaybackNavigation.clear()
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val requestedQueue = listOf(
            SongFixtures.song("remote-a"),
            SongFixtures.song("remote-b"),
        )
        val replacementQueue = listOf(
            SongFixtures.song("local-a"),
            SongFixtures.song("local-b"),
        )
        val mediaController = mockk<MediaController>(relaxed = true)
        val submittedItems = slot<List<MediaItem>>()
        every { mediaController.mediaItemCount } returns 0
        every { mediaController.currentMediaItem } returns null
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 0L

        controller.playQueueSong(requestedQueue, "remote-b")
        controller.setQueue(replacementQueue)
        connector.requests.single().onConnected(mediaController)

        verify(exactly = 1) { mediaController.setMediaItems(capture(submittedItems), 1, 0L) }
        verify(exactly = 0) { mediaController.play() }
        assertEquals(replacementQueue.map { it.id }, submittedItems.captured.map(MediaItem::mediaId))
        assertEquals(replacementQueue.map { it.id }, controller.playbackQueueState.queue.map { it.id })
        assertNull(PendingPlaybackNavigation.consumeNavigationOverride())
        controller.release()
    }

    @Test
    fun playQueueSongSetsTargetStartIndexAndCarriesQueuePayloadAcrossBinderLag() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val oldItem = MediaItem.Builder().setMediaId("old-song").build()
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
        )
        every { mediaController.currentMediaItem } returns oldItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.getMediaItemAt(0) } returns oldItem
        every { mediaController.currentPosition } returns 9_000L
        every { mediaController.duration } returns 60_000L
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        clearMocks(mediaController, answers = false, recordedCalls = true)
        PendingPlaybackNavigation.clear()

        controller.playQueueSong(queue, "song-b")

        val submittedItems = slot<List<MediaItem>>()
        verify(exactly = 1) {
            mediaController.setMediaItems(capture(submittedItems), 1, 0L)
        }
        verify(exactly = 1) { mediaController.seekTo(1, 0L) }
        verify(exactly = 1) { mediaController.play() }
        assertEquals(queue.map { it.id }, submittedItems.captured.map(MediaItem::mediaId))
        assertEquals("song-b", controller.playbackSurfaceState.currentSong?.id)
        val navigation = PendingPlaybackNavigation.consumeNavigationOverride()
        assertEquals("song-b", navigation?.targetSongId)
        assertEquals(queue.map { it.id }, navigation?.queue?.items?.map(MediaItem::mediaId))
        controller.release()
    }

    @Test
    fun playSongSwitchCarriesQueuePayloadWhenServiceQueueIsNotAligned() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = listOf(
            SongFixtures.song("song-a"),
            SongFixtures.song("song-b"),
            SongFixtures.song("song-c"),
        )
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(queue[firstArg()].id).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[0].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 2
        every { mediaController.currentPosition } returns 4_000L
        every { mediaController.playWhenReady } returns true
        every { mediaController.isPlaying } returns true
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.playSong(2)

        val navigation = PendingPlaybackNavigation.consumeNavigationOverride()
        assertEquals("song-c", navigation?.targetSongId)
        assertEquals(queue.map { it.id }, navigation?.queue?.items?.map { it.mediaId })
        verify(exactly = 1) { mediaController.seekTo(2, 0L) }
        controller.release()
    }

    @Test
    fun playSongSwitchResetsPlayerDurationSoUiDurationDoesNotInheritPreviousTrack() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val queue = listOf(
            SongFixtures.song("song-long", durationSec = 312),
            SongFixtures.song("song-short", durationSec = 218),
        )
        every { mediaController.getMediaItemAt(any()) } answers {
            MediaItem.Builder().setMediaId(queue[firstArg()].id).build()
        }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[0].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 0L
        every { mediaController.duration } returns 312_000L
        every { mediaController.isPlaying } returns true
        every { mediaController.playbackState } returns Player.STATE_READY
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.syncPlaybackState()
        assertEquals(312_000, controller.playbackProgressState.durationMs)

        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[1].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 1
        // Exo 尚未上报新曲时长时，也不应继续沿用上一首 player duration。
        every { mediaController.duration } returns 312_000L

        controller.playSong(1)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(218_000, controller.playbackProgressState.durationMs)
        assertEquals("song-short", controller.playbackSurfaceState.currentSong?.id)
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
    fun playlistChangeKeepsProgressWhenCurrentSongDoesNotChange() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(2)
        val currentItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns currentItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 40_000L
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.syncPosition()

        listener.captured.onMediaItemTransition(
            currentItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )

        assertEquals(queue[0].id, controller.playbackSurfaceState.currentSong?.id)
        assertEquals(40_000, controller.uiPositionMs())
        controller.release()
    }

    @Test
    fun sameSongRepeatTransitionUsesAuthoritativePlayerPosition() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val song = SongFixtures.song("same-song")
        val currentItem = MediaItem.Builder().setMediaId(song.id).build()
        var playerPositionMs = 40_000L
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } returns currentItem
        every { mediaController.currentMediaItemIndex } returns 0
        every { mediaController.mediaItemCount } returns 1
        every { mediaController.currentPosition } answers { playerPositionMs }
        every { mediaController.duration } returns 60_000L
        controller.setQueue(listOf(song))
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.syncPosition()

        // Metadata/playlist refresh mislabeled by Media3 as REPEAT must not reset lyrics to zero.
        listener.captured.onMediaItemTransition(
            currentItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )
        assertEquals(40_000, controller.uiPositionMs())

        // A real repeat-one wrap is committed only by its authoritative discontinuity.
        listener.captured.onPositionDiscontinuity(
            positionInfo(currentItem, 0, 59_900L),
            positionInfo(currentItem, 0, 0L),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        assertEquals(0, controller.uiPositionMs())
        controller.release()
    }

    @Test
    fun playlistChangeResetsProgressWhenCurrentSongChanges() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(2)
        val firstItem = MediaItem.Builder().setMediaId(queue[0].id).build()
        val secondItem = MediaItem.Builder().setMediaId(queue[1].id).build()
        var currentItem = firstItem
        var currentIndex = 0
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.currentMediaItem } answers { currentItem }
        every { mediaController.currentMediaItemIndex } answers { currentIndex }
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 40_000L
        every { mediaController.duration } returns 60_000L
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.syncPosition()

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onMediaItemTransition(
            secondItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )

        assertEquals(queue[1].id, controller.playbackSurfaceState.currentSong?.id)
        assertEquals(0, controller.uiPositionMs())
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
        shadowOf(Looper.getMainLooper()).idle()
        listener.captured.onMediaItemTransition(
            firstItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
        assertEquals("song-b", controller.playbackSurfaceState.currentSong?.id)

        currentItem = secondItem
        currentIndex = 1
        listener.captured.onPlayerError(
            PlaybackException(
                "decoder failed",
                null,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
        )
        assertEquals("无法读取音频文件", controller.playbackSurfaceState.playbackError)
        assertNull(controller.userMessage)

        currentItem = firstItem
        currentIndex = 0
        listener.captured.onMediaItemTransition(
            firstItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )

        assertEquals("song-a", controller.playbackSurfaceState.currentSong?.id)
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
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("song-c", controller.playbackSurfaceState.currentSong?.id)

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
        assertEquals("song-c", controller.playbackSurfaceState.currentSong?.id)
        assertNull(controller.playbackSurfaceState.playbackError)

        currentItem = thirdItem
        currentIndex = 2
        listener.captured.onMediaItemTransition(
            thirdItem,
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )

        assertEquals("song-c", controller.playbackSurfaceState.currentSong?.id)
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

        assertEquals("song-d", controller.playbackSurfaceState.currentSong?.id)
        assertEquals(3, controller.playbackQueueState.currentIndex)
        controller.release()
    }

    private class FakeConnector : MediaControllerConnector {
        val requests = mutableListOf<Request>()

        override fun connect(
            onConnected: (MediaController) -> Unit,
            onDisconnected: () -> Unit,
            onFailure: (Throwable) -> Unit,
            onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
            onPlaybackStackRebuilt: () -> Unit,
        ): MediaControllerConnection {
            val request = Request(
                onConnected,
                onDisconnected,
                onFailure,
                onPlaybackBoundary,
                onPlaybackStackRebuilt,
                FakeConnection(),
            )
            requests += request
            return request.connection
        }

        data class Request(
            val onConnected: (MediaController) -> Unit,
            val onDisconnected: () -> Unit,
            val onFailure: (Throwable) -> Unit,
            val onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
            val onPlaybackStackRebuilt: () -> Unit,
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
