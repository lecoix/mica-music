package com.mica.music.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mica.music.data.PlaybackQueueMode
import com.mica.music.data.PlaybackSessionStore
import com.mica.music.data.PlaybackSongResolver
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.playback.ServicePlaybackStateStore
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.connectMediaService
import com.mica.music.testutil.ContractTestSupport.createSilentWav
import com.mica.music.testutil.ContractTestSupport.onMain
import com.mica.music.testutil.ContractTestSupport.stopMediaServiceAndAwaitDestruction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression tests: real app commands, Binder callbacks and natural ExoPlayer transitions. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ShuffleQueueReproductionTest {
    @Test
    fun ordinaryShuffleNaturalNextMatchesDisplayedQueue() = withShuffledQueue { app, service, _ ->
        assertNaturalNextMatchesDisplay(app, service, "ordinary")
    }

    @Test
    fun insertPlayNextInShuffleIsActuallyPlayedNext() = withShuffledQueue { app, service, songs ->
        val inserted = songs.last()
        onMain { app.insertPlayNext(inserted) }
        await("inserted service item", 10_000) { onMain { service.mediaItemCount == songs.size } }
        await("inserted display item", 10_000) {
            onMain {
                val state = app.playbackQueueState
                state.queue.getOrNull(state.currentIndex + 1)?.id == inserted.id
            }
        }
        assertNaturalNextMatchesDisplay(app, service, "insert")
    }

    @Test
    fun movingSongToNextInShuffleChangesActualSuccessor() = withShuffledQueue { app, service, _ ->
        val movedId = onMain {
            val state = app.playbackQueueState
            val from = state.queue.lastIndex
            val id = state.queue[from].id
            app.moveInQueue(from, state.currentIndex + 1)
            id
        }
        await("moved service successor", 10_000) {
            onMain {
                service.nextMediaItemIndex.takeIf { it >= 0 }
                    ?.let { service.getMediaItemAt(it).mediaId } == movedId
            }
        }
        assertEquals(movedId, onMain {
            val state = app.playbackQueueState
            state.queue.getOrNull(state.currentIndex + 1)?.id
        })
        assertNaturalNextMatchesDisplay(app, service, "move")
    }

    @Test
    fun appendAndPausedCurrentDeletionKeepEffectiveOrder() = withShuffledQueue { app, service, songs ->
        val before = onMain { app.playbackQueueState.queue.map { it.id } }
        onMain { app.appendSongs(listOf(songs.last())) }
        await("appended membership", 10_000) { onMain { service.mediaItemCount == songs.size } }
        assertEquals(before + songs.last().id, onMain { app.playbackQueueState.queue.map { it.id } })
        val nextId = before[1]
        onMain { app.removeFromQueue(app.playbackQueueState.currentIndex) }
        await("paused effective neighbor", 10_000) { onMain { service.currentMediaItem?.mediaId == nextId } }
        assertTrue(onMain { !service.playWhenReady && !service.isPlaying })
        assertNaturalNextMatchesDisplay(app, service, "append-delete")
    }

    @Test
    fun leavingShuffleRestoresSourceTraversalWithoutReplacingCurrent() = withShuffledQueue { app, service, songs ->
        val current = onMain { service.currentMediaItem?.mediaId }
        onMain { app.cyclePlaybackQueueMode() }
        await("source successor", 10_000) {
            onMain { service.nextMediaItemIndex.takeIf { it >= 0 }?.let { service.getMediaItemAt(it).mediaId } == songs[3].id }
        }
        assertEquals(current, onMain { service.currentMediaItem?.mediaId })
        assertEquals(PlaybackQueueMode.OFF, onMain { app.playbackSurfaceState.playbackQueueMode })
        assertNaturalNextMatchesDisplay(app, service, "source")
    }

    @Test
    fun offlineLastDeletionClearsExistingServiceQueueOnConnect() = withShuffledQueue { _, service, songs ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disconnected = onMain { PlayerController(context) }
        try {
            onMain {
                disconnected.setQueue(listOf(songs[0]))
                disconnected.removeFromQueue(0)
                disconnected.connectIfNeeded()
            }
            await("offline clear reaches service", 10_000) { onMain { service.mediaItemCount == 0 } }
            assertTrue(onMain { disconnected.playbackQueueState.queue.isEmpty() })
        } finally { onMain { disconnected.release() } }
    }

    @Test
    fun consecutiveEditsNearNaturalBoundaryUseLatestSuccessor() = withShuffledQueue { app, service, songs ->
        repeat(3) { step ->
            val (current, expected) = onMain {
                val current = service.currentMediaItem!!.mediaId
                service.seekTo(service.duration - 600L)
                service.play()
                app.insertPlayNext(songs.last())
                val state = app.playbackQueueState
                val expected = state.queue.last().id
                app.moveInQueue(state.queue.lastIndex, state.currentIndex + 1)
                current to expected
            }
            // Deliberately do not await command acknowledgement before the natural boundary.
            await("near-tail transition $step", 10_000) { onMain { service.currentMediaItem?.mediaId != current } }
            assertEquals(expected, onMain { service.currentMediaItem?.mediaId })
            await("near-tail UI callback $step", 10_000) { onMain { app.playbackSurfaceState.currentSong?.id == expected } }
            onMain { service.pause() }
            logState(app, service, "near-tail-$step")
        }
    }

    @Test
    fun editedOrderAndMiddleCursorSurviveServiceRecreation() = withShuffledQueue { app, service, songs ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        onMain {
            app.appendSongs(listOf(songs.last()))
            app.moveInQueue(app.playbackQueueState.queue.lastIndex, 1)
            app.playSong(2)
        }
        val expected = onMain { app.playbackQueueState.queue.map { it.id } }
        val currentId = expected[2]
        await("middle cursor", 10_000) { onMain { service.currentMediaItem?.mediaId == currentId && service.isPlaying } }
        onMain { service.pause(); service.seekTo(12_000L) }
        await("accepted order persisted", 10_000) {
            ServicePlaybackStateStore(context).load()?.let {
                it.playbackOrderIds == expected && it.currentSongId == currentId && it.positionMs >= 12_000L
            } == true
        }
        onMain { app.release(); service.release() }
        stopMediaServiceAndAwaitDestruction(context)
        val restored = onMain { PlayerController(context, PlaybackSongResolver { id -> songs.firstOrNull { it.id == id } }) }
        var observer: MediaController? = null
        try {
            runBlocking { withContext(Dispatchers.Main) { restored.bootstrapQueue { id -> songs.firstOrNull { it.id == id } } } }
            assertEquals(expected, onMain { restored.playbackQueueState.queue.map { it.id } })
            onMain { restored.connectIfNeeded() }
            await("restored connection", 10_000) { onMain { restored.isConnected } }
            val coldService = connectMediaService(context)
            observer = coldService
            await("restored middle cursor", 10_000) {
                onMain { coldService.currentMediaItem?.mediaId == currentId && coldService.currentPosition >= 12_000 && !coldService.playWhenReady }
            }
            assertOneNaturalNextMatchesDisplay(restored, coldService, "cold")
            val index = onMain { restored.playbackQueueState.currentIndex }
            onMain { coldService.seekToNextMediaItem() }
            await("system next shares order", 10_000) { onMain { coldService.currentMediaItem?.mediaId == expected[index + 1] } }
            assertEquals(expected, onMain { restored.playbackQueueState.queue.map { it.id } })
        } finally {
            observer?.let { onMain { it.pause(); it.clearMediaItems(); it.release() } }
            onMain { restored.release() }
            stopMediaServiceAndAwaitDestruction(context)
        }
    }

    private fun assertNaturalNextMatchesDisplay(app: PlayerController, service: MediaController, label: String) {
        val remaining = onMain { app.playbackQueueState.let { it.queue.lastIndex - it.currentIndex } }
        repeat(remaining) { step -> assertOneNaturalNextMatchesDisplay(app, service, "$label-step-$step") }
        val finalId = onMain { service.currentMediaItem?.mediaId }
        onMain { service.seekTo((service.duration - 200L).coerceAtLeast(0)); service.play() }
        await("terminal shuffle stops", 10_000) { onMain { service.playbackState == Player.STATE_ENDED } }
        assertEquals(finalId, onMain { service.currentMediaItem?.mediaId })
    }

    private fun assertOneNaturalNextMatchesDisplay(app: PlayerController, service: MediaController, label: String) {
        val (currentId, expectedNext) = onMain {
            val state = app.playbackQueueState
            state.queue[state.currentIndex].id to checkNotNull(state.queue.getOrNull(state.currentIndex + 1)).id
        }
        logState(app, service, "$label-before")
        await("accepted successor ($label)", 10_000) {
            onMain { service.nextMediaItemIndex.takeIf { it >= 0 }?.let { service.getMediaItemAt(it).mediaId } == expectedNext }
        }
        onMain {
            service.seekTo((service.duration - 300L).coerceAtLeast(0L))
            service.play()
        }
        await("real natural transition ($label)", 10_000) {
            onMain { service.currentMediaItem?.mediaId != currentId }
        }
        val actual = onMain { service.currentMediaItem?.mediaId }
        await("app receives real transition ($label)", 10_000) {
            onMain { app.playbackSurfaceState.currentSong?.id == actual }
        }
        onMain { service.pause() }
        logState(app, service, "$label-after")
        assertEquals("$label: displayed next must equal natural next", expectedNext, actual)
    }

    private fun withShuffledQueue(block: (PlayerController, MediaController, List<Song>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Only side-by-side QA is allowed", context.packageName.endsWith(".qa"))
        val files = (0..8).map { createSilentWav(context.cacheDir, "shuffle-repro-$it", 30) }
        val songs = files.mapIndexed { index, file -> testSong("shuffle-repro-$index", file) }
        var app: PlayerController? = null
        var service: MediaController? = null
        try {
            stopMediaServiceAndAwaitDestruction(context)
            ServicePlaybackStateStore(context).clear(sync = true)
            PlaybackSessionStore.clear(context)
            val player = onMain {
                PlayerController(context, PlaybackSongResolver { id -> songs.firstOrNull { it.id == id } }).also {
                    it.setQueue(songs.dropLast(1))
                    it.connectIfNeeded()
                }
            }
            app = player
            await("app connected", 10_000) { onMain { player.isConnected } }
            val observer = connectMediaService(context)
            service = observer
            onMain { player.playSongById(songs[2].id) }
            await("real fixture playback", 10_000) {
                onMain { observer.currentMediaItem?.mediaId == songs[2].id && observer.isPlaying && observer.duration > 0 }
            }
            onMain { observer.pause() }
            repeat(3) {
                val oldMode = onMain { player.playbackSurfaceState.playbackQueueMode }
                onMain { player.cyclePlaybackQueueMode() }
                await("mode changes from $oldMode", 10_000) {
                    onMain { player.playbackSurfaceState.playbackQueueMode != oldMode }
                }
            }
            await("service native shuffle", 10_000) { onMain { observer.shuffleModeEnabled } }
            assertEquals(PlaybackQueueMode.SHUFFLE, onMain { player.playbackSurfaceState.playbackQueueMode })
            assertEquals(Player.REPEAT_MODE_OFF, onMain { observer.repeatMode })
            logState(player, observer, "enabled")
            block(player, observer, songs)
        } finally {
            service?.let { onMain { it.pause(); it.clearMediaItems(); it.release() } }
            app?.let { onMain { it.release() } }
            stopMediaServiceAndAwaitDestruction(context)
            ServicePlaybackStateStore(context).clear(sync = true)
            PlaybackSessionStore.clear(context)
            files.forEach(File::delete)
        }
    }

    private fun logState(app: PlayerController, service: MediaController, label: String) = onMain {
        val shown = app.playbackQueueState
        val physical = List(service.mediaItemCount) { service.getMediaItemAt(it).mediaId }
        val seed = PlaybackSessionStore.load(ApplicationProvider.getApplicationContext<Context>())?.shuffleSeed
        val next = service.nextMediaItemIndex.takeIf { it >= 0 }?.let { service.getMediaItemAt(it).mediaId }
        Log.i("ShuffleRepro", "$label seed=$seed shown=${shown.queue.map { it.id }} shownIndex=${shown.currentIndex} physical=$physical current=${service.currentMediaItem?.mediaId} nativeNext=$next nativeShuffle=${service.shuffleModeEnabled}")
    }

    private fun testSong(id: String, file: File) = Song(
        id = id, title = id, artist = "Mica", album = "Shuffle reproduction", durationSec = 30,
        metadata = TrackMetadata(containerName = "WAV", sampleRateHz = 8_000, bitsPerSample = 16,
            bitrateKbps = 128, channelCount = 1, playbackMimeType = "audio/wav"),
        albumArtUri = null, coverColorArgb = 0, mediaUri = Uri.fromFile(file).toString(), fileName = file.name,
    )
}
