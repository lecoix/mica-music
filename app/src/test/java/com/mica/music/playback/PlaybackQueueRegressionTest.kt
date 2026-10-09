package com.mica.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.mica.music.audio.AudioQualityMode
import com.mica.music.data.PlaybackSession
import com.mica.music.data.PlaybackSongResolver
import com.mica.music.data.playback.ServicePlaybackSnapshot
import com.mica.music.data.playback.ServicePlaybackStateStore
import com.mica.music.media.ConfirmedPlaybackBoundary
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackQueueRegressionTest {
    @Test
    fun appendMustKeepExistingPlaybackOrderAndAddAtTail() {
        val songs = SongFixtures.queue(8)
        val app = PlayerController(ApplicationProvider.getApplicationContext())
        try {
            app.setQueue(songs)
            app.restoreSession(PlaybackSession(songs[2].id, 0, true, songs.map { it.id }, 42L))
            val before = app.playbackQueueState.queue.map { it.id }
            val extra = SongFixtures.song("audit-extra")
            app.appendSongs(listOf(extra))
            assertEquals(before + extra.id, app.playbackQueueState.queue.map { it.id })
        } finally { app.release() }
    }

    @Test
    fun metadataRefreshMustReachMatchingIdsInDifferentlyOrderedPhysicalQueue() {
        val physical = SongFixtures.queue(4)
        val shown = listOf(physical[2], physical[0], physical[3], physical[1]).map { it.copy(title = "updated ${it.title}") }
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns physical.size
        every { player.getMediaItemAt(any()) } answers { SongMediaItemCodec.encode(physical[firstArg()]) }
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true
        val plan = MediaControllerQueueSync.planMetadataRefresh(player, shown, shown.indices.toList())
        assertTrue("metadata refresh incorrectly skipped: $plan", plan is PlaybackQueueSyncPlan.ReplaceMediaItems)
        MediaControllerQueueSync.executeSyncPlan(player, plan)
        verify(exactly = physical.size) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun mirrorMustKeepEffectiveShuffleOrderWhenPhysicalIdsHaveNotChanged() {
        val source = SongFixtures.queue(8)
        val order = PlaybackOrderState.fromSource(source.map { it.id }, source[2].id, true, 42L)
        val model = PlaybackQueueModel().applyOrder(order, source)
        val mirrored = model.mirrorFromPlayer(source, 2)
        assertEquals(order.playbackIds, mirrored.queue.map { it.id })
    }

    @Test
    fun clearingQueueBeforeConnectionMustNotSubmitEarlierPendingItems() {
        val connector = ProbeConnector()
        val app = probeController(connector)
        val player = mockk<MediaController>(relaxed = true)
        every { player.mediaItemCount } returns 0
        try {
            app.setQueue(SongFixtures.queue(1))
            app.removeFromQueue(0)
            assertTrue(app.playbackQueueState.queue.isEmpty())
            app.connectIfNeeded()
            connector.connected(player)
            verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
            verify(exactly = 1) { player.clearMediaItems() }
        } finally { app.release() }
    }

    @Test
    fun earlierColdRestoreMustNotOverwriteNewUserQueue() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val oldSong = SongFixtures.song("audit-old")
        val chosenSong = SongFixtures.song("audit-user-choice")
        store.save(ServicePlaybackSnapshot(listOf(oldSong.id), 0, 12000, Player.REPEAT_MODE_OFF,
            false, false, AudioQualityMode.HIFI), sync = true)
        val connector = ProbeConnector()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restoreWork = java.util.ArrayDeque<Runnable>()
        val delayedIo = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                restoreWork.add(block)
            }
        }
        val app = PlayerController(context, connector, ProbeStorage(), PlaybackSongResolver { null },
            dispatcher = dispatcher, queueMirrorDispatcher = dispatcher, restoreDispatcher = delayedIo)
        try {
            var handled = false
            val restore = launch(start = CoroutineStart.UNDISPATCHED) { handled = app.bootstrapQueue { id -> oldSong.takeIf { it.id == id } } }
            runCurrent()
            assertEquals("old restore reached the real persistence await", 1, restoreWork.size)
            assertTrue("restore must be suspended at IO boundary", !restore.isCompleted)
            app.setQueue(listOf(chosenSong))
            // End with equal content (A -> B -> A). A content-equality guard would accept old IO.
            app.setQueue(listOf(oldSong))
            // Deliver the non-cancelled IO result after the new queue is committed.
            restoreWork.removeFirst().run()
            runCurrent()
            restore.join()
            assertTrue("expired bootstrap must suppress caller fallback", handled)
            assertEquals(listOf(oldSong.id), app.playbackQueueState.queue.map { it.id })
            assertEquals(0, app.playbackProgressState.positionMs)
        } finally { app.release(); store.clear(sync = true) }
    }

    @Test
    fun acceptedColdOrderSurvivesCursorMoveAndLegacySeed() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ServicePlaybackStateStore(context)
        val source = SongFixtures.queue(8)
        val original = PlaybackOrderState.fromSource(source.map { it.id }, source[2].id, true, 42L)
        val currentId = original.playbackIds[2]
        store.save(ServicePlaybackSnapshot(source.map { it.id }, source.indexOfFirst { it.id == currentId },
            12000, Player.REPEAT_MODE_OFF, true, false, AudioQualityMode.HIFI,
            playbackOrderIds = original.playbackIds, sourceOrderIds = original.sourceIds), sync = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = object : PlaybackSessionStorage {
            override fun load() = PlaybackSession(currentId, 12000, true, source.map { it.id }, 42L)
            override fun save(session: PlaybackSession?, sync: Boolean) = Unit
            override fun clear() = Unit
        }
        val app = PlayerController(context, ProbeConnector(), storage, PlaybackSongResolver { null },
            dispatcher = dispatcher, queueMirrorDispatcher = dispatcher, restoreDispatcher = dispatcher)
        try {
            app.bootstrapQueue { id -> source.firstOrNull { it.id == id } }
            assertEquals(original.playbackIds, app.playbackQueueState.queue.map { it.id })
        } finally { app.release(); store.clear(sync = true) }
    }

    @Test
    fun tenThousandOrderUsesOneBoundedPermutationMessage() {
        val songs = SongFixtures.queue(10_000).map { it.copy(lyricsLoaded = false) }
        val items = songs.map(SongMediaItemCodec::encode)
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns songs.size
        every { player.currentMediaItem } returns items[0]
        every { player.currentPosition } returns 12000L
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } returns true
        val ids = songs.map { it.id }
        val started = System.nanoTime()
        val args = checkNotNull(com.mica.music.media.PlaybackShuffleSessionCommand.encodeOrder(
            ids, ids.reversed(), ids, true, 1L))
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(args)
            println("QUEUE_ORDER_CAPACITY n=10000 planMs=${(System.nanoTime()-started)/1_000_000} binderBytes=${parcel.dataSize()}")
            assertTrue(parcel.dataSize() < 100_000)
        } finally { parcel.recycle() }
    }
    @Test fun tenThousandLoadedLyricEntriesAreNotTraversedOrEncodedDuringMove() {
        // Access trap stands in for arbitrarily large, already loaded word-timed lyric documents.
        // Order-only work must share the document, regardless of its text/token size.
        val document = com.mica.music.data.LyricsDocument(lines = object : AbstractList<com.mica.music.data.LyricLineNode>() {
            override val size = 100
            override fun get(index: Int): com.mica.music.data.LyricLineNode = error("order edit traversed lyrics")
        })
        val songs = SongFixtures.queue(10_000).map { it.copy(lyricsDocument = document, lyricsLoaded = true) }
        val items = songs.map { MediaItem.Builder().setMediaId(it.id).setUri(it.mediaUri).build() }
        val player = mockk<MediaController>(relaxed = true)
        every { player.mediaItemCount } returns items.size
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.currentMediaItem } returns items[0]
        every { player.currentMediaItemIndex } returns 0
        val connector = ProbeConnector()
        val app = probeController(connector)
        try {
            app.setQueue(songs)
            app.connectIfNeeded()
            connector.connected(player)
            io.mockk.clearMocks(player, answers = false, recordedCalls = true)
            app.moveInQueue(9_999, 1)
            assertEquals(songs.last().id, app.playbackQueueState.queue[1].id)
            assertTrue(app.playbackQueueState.queue.all { it.lyricsDocument === document })
            verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
            verify(exactly = 0) { player.moveMediaItem(any(), any()) }
            verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
            verify(exactly = 1) { player.sendCustomCommand(com.mica.music.media.PlaybackShuffleSessionCommand.command, any()) }
        } finally { app.release() }
    }

    private fun probeController(connector: ProbeConnector) = PlayerController(
        ApplicationProvider.getApplicationContext(), connector, ProbeStorage(), PlaybackSongResolver { null },
        dispatcher = StandardTestDispatcher(), restoreDispatcher = Dispatchers.Unconfined)

    private class ProbeStorage : PlaybackSessionStorage {
        override fun save(session: PlaybackSession?, sync: Boolean) = Unit
        override fun load(): PlaybackSession? = null
        override fun clear() = Unit
    }

    private class ProbeConnector : MediaControllerConnector {
        lateinit var connected: (MediaController) -> Unit
        override fun connect(onConnected: (MediaController) -> Unit, onDisconnected: () -> Unit,
            onFailure: (Throwable) -> Unit, onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
            onPlaybackStackRebuilt: () -> Unit): MediaControllerConnection {
            connected = onConnected
            return object : MediaControllerConnection { override fun cancel() = Unit }
        }
    }
}
