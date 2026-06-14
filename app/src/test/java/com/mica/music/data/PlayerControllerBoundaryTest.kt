package com.mica.music.data

import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.mica.music.media.AlacAudioTrackEngine
import com.mica.music.media.MicaCompositePlayer
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        playbackBackend = EmptyPlaybackBackend,
        mediaControllerConnector = connector,
        sessionStorage = storage,
        dispatcher = StandardTestDispatcher(),
    )

    private object EmptyPlaybackBackend : PlaybackBackend {
        override val compositePlayer: MicaCompositePlayer? = null
        override val alacEngine: AlacAudioTrackEngine? = null
    }

    private class FakeConnector : MediaControllerConnector {
        val requests = mutableListOf<Request>()

        override fun connect(
            onConnected: (MediaController) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): MediaControllerConnection {
            val request = Request(onConnected, onFailure, FakeConnection())
            requests += request
            return request.connection
        }

        data class Request(
            val onConnected: (MediaController) -> Unit,
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
