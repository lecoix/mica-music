package com.mica.music.data

import androidx.media3.session.MediaController
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.core.app.ApplicationProvider
import com.mica.music.media.AudioQualityMode
import com.mica.music.media.PendingPlaybackNavigation
import com.mica.music.media.ServicePlaybackSnapshot
import com.mica.music.media.ServicePlaybackStateStore
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.testutil.SongFixtures
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerControllerBoundaryTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rapidEquivalentPlaylistChangesAreDebouncedAndRebuiltOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connector = FakeConnector()
        val controller = controller(connector = connector, dispatcher = dispatcher)
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
        controller.songResolver = { id ->
            resolverCalls += 1
            playerQueue.firstOrNull { it.id == id }
        }
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
        assertEquals(playerQueue.map { it.id }, controller.songQueue.map { it.id })

        listener.captured.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(playerQueue.size, resolverCalls)
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
        assertEquals("song-2000", controller.currentSong?.id)
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
        assertEquals("song-1", controller.currentSong?.id)
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

        val expected = "不支持 DFF/DSDIFF 格式，请使用 DSF"
        assertEquals(expected, controller.playbackError)
        assertEquals(expected, controller.userMessage?.text)
        listener.captured.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(expected, controller.playbackError)
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

    @Test
    fun selectingAnotherSongAfterColdStartRestoreStartsAtBeginning() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val queue = SongFixtures.queue(2)
        val connector = FakeConnector()
        val controller = controller(connector = connector)
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
        val controller = controller(connector = connector)
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

    private fun controller(
        connector: FakeConnector = FakeConnector(),
        storage: FakeSessionStorage = FakeSessionStorage(),
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
        queueMirrorDispatcher: CoroutineDispatcher = dispatcher,
        monotonicNowMs: () -> Long = { 0L },
    ): PlayerController = PlayerController(
        context = ApplicationProvider.getApplicationContext(),
        mediaControllerConnector = connector,
        sessionStorage = storage,
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
        assertEquals(PlaybackQueueMode.OFF, controller.playbackQueueMode)

        repeatMode = Player.REPEAT_MODE_ALL
        listener.captured.onRepeatModeChanged(Player.REPEAT_MODE_ALL)

        assertEquals(PlaybackQueueMode.REPEAT_ALL, controller.playbackQueueMode)
        controller.release()
    }

    @Test
    fun shuffleModeBuildsAppPlaybackOrderWithoutEnablingMedia3Shuffle() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
        val mediaController = mockk<MediaController>(relaxed = true)
        val listener = slot<Player.Listener>()
        val queue = SongFixtures.queue(6)
        var repeatMode = Player.REPEAT_MODE_OFF
        every { mediaController.addListener(capture(listener)) } returns Unit
        every { mediaController.repeatMode } answers { repeatMode }
        every { mediaController.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(queue[2].id)
            .build()
        every { mediaController.currentMediaItemIndex } returns 2
        every { mediaController.mediaItemCount } returns queue.size
        every { mediaController.currentPosition } returns 10_000L
        every { mediaController.playWhenReady } returns true
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
        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackQueueMode)
        assertEquals(queue[2].id, controller.currentSong?.id)
        assertEquals(queue.map { it.id }.toSet(), controller.songQueue.map { it.id }.toSet())
        assertEquals(queue.size, controller.songQueue.distinctBy { it.id }.size)
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
            MediaItem.Builder().setMediaId(controller.songQueue[firstArg()].id).build()
        }
        controller.setQueue(queue)
        controller.connectIfNeeded()
        connector.requests.single().onConnected(mediaController)
        controller.playSong(2)
        controller.cyclePlaybackQueueMode()
        val target = controller.manualNextTarget()
        clearMocks(mediaController, answers = false, recordedCalls = true)

        controller.next()

        verify { mediaController.seekTo(target ?: -1, 0L) }
        assertEquals(target, controller.currentIndex)
        controller.release()
    }

    @Test
    fun shuffleModeCanBeTurnedOffWithoutWaitingForPlayerCallback() {
        val connector = FakeConnector()
        val controller = controller(connector = connector)
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
            MediaItem.Builder().setMediaId(controller.songQueue[firstArg()].id).build()
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
        assertEquals(PlaybackQueueMode.SHUFFLE, controller.playbackQueueMode)

        controller.cyclePlaybackQueueMode()

        assertEquals(PlaybackQueueMode.OFF, controller.playbackQueueMode)
        verify(atLeast = 1) { mediaController.shuffleModeEnabled = false }
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

        assertEquals(218_000, controller.playbackProgressState.durationMs)
        assertEquals("song-short", controller.currentSong?.id)
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

        assertEquals(queue[0].id, controller.currentSong?.id)
        assertEquals(40_000, controller.uiPositionMs())
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

        assertEquals(queue[1].id, controller.currentSong?.id)
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
        assertEquals("无法读取音频文件", controller.playbackError)
        assertNull(controller.userMessage)

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
