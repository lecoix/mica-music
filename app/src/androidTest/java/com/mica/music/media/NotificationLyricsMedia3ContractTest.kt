package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.LyricLine
import com.mica.music.data.MediaControllerConnection
import com.mica.music.data.MediaControllerConnector
import com.mica.music.data.PlaybackSession
import com.mica.music.data.PlaybackSessionStorage
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.toLyricsDocumentCompat
import com.google.common.util.concurrent.Futures
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NotificationLyricsMedia3ContractTest {
    @Test
    fun metadataReplacementDoesNotCountAndThreeRealRepeatsCountOnceEach() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFile = createSilentWav(context.cacheDir, "repeat", durationSeconds = 2)
        val song = testSong("repeat", Uri.fromFile(audioFile).toString(), durationSeconds = 2)
        val previousLyricsSetting = LyricsPreferences.notificationLyricsEnabled(context)
        var lyricsCoordinator: NotificationLyricsCoordinator? = null

        try {
            LyricsPreferences.setNotificationLyricsEnabled(context, true)
            withPlayback(context, listOf(song), Player.REPEAT_MODE_ONE) { contract ->
                lyricsCoordinator = onMain {
                    NotificationLyricsCoordinator(
                        context = context,
                        player = contract.player,
                        handler = Handler(Looper.getMainLooper()),
                        songLoader = { song },
                    ).also { it.start() }
                }
                await("notification lyric metadata replacement") {
                    onMain {
                        contract.player.currentMediaItem?.mediaMetadata?.title?.toString() == "line-two"
                    }
                }
                assertEquals(listOf("repeat"), contract.playSessions.toList())

                await("three confirmed repeat-one boundaries", timeoutMs = 8_000L) {
                    contract.rawPlayerEvents.autoPositionWraps.get() >= 3 &&
                        contract.playSessions.size >= 4
                }
                assertEquals(
                    "rawWraps=${contract.rawPlayerEvents.autoPositionWraps.get()} " +
                        "repeatTransitions=${contract.rawPlayerEvents.repeatTransitions.get()} " +
                        "sessions=${contract.playSessions}",
                    listOf("repeat", "repeat", "repeat", "repeat"),
                    contract.playSessions.toList(),
                )
            }
        } finally {
            lyricsCoordinator?.let { onMain { it.release() } }
            LyricsPreferences.setNotificationLyricsEnabled(context, previousLyricsSetting)
            audioFile.delete()
        }
    }

    @Test
    fun pauseResumeAndManualSeekToStartDoNotCount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFile = createSilentWav(context.cacheDir, "seek", durationSeconds = 10)
        val song = testSong("seek", Uri.fromFile(audioFile).toString(), durationSeconds = 10)

        try {
            withPlayback(context, listOf(song), Player.REPEAT_MODE_OFF) { contract ->
                onMain { contract.playerController.pauseIfPlaying() }
                await("pause") {
                    onMain {
                        !contract.player.isPlaying &&
                            !contract.playerController.playbackSurfaceState.isPlaying
                    }
                }
                onMain { contract.playerController.togglePlay() }
                await("resume") { onMain { contract.player.isPlaying } }

                onMain { contract.playerController.seekToMs(5_000) }
                await("seek near middle") {
                    onMain {
                        contract.player.currentPosition >= 4_500L &&
                            contract.player.playbackState == Player.STATE_READY &&
                            contract.playerController.playbackProgressState.pendingSeekMs < 0
                    }
                }
                onMain { contract.playerController.seekToMs(0) }
                await("manual seek to start") { onMain { contract.player.currentPosition < 750L } }
                SystemClock.sleep(500L)

                assertEquals(listOf("seek"), contract.playSessions.toList())
            }
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun naturalNextAndExplicitReplayOfCurrentSongCountExactlyOnceEach() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstFile = createSilentWav(context.cacheDir, "first", durationSeconds = 2)
        val secondFile = createSilentWav(context.cacheDir, "second", durationSeconds = 3)
        val first = testSong("first", Uri.fromFile(firstFile).toString(), durationSeconds = 2)
        val second = testSong("second", Uri.fromFile(secondFile).toString(), durationSeconds = 3)

        try {
            withPlayback(context, listOf(first, second), Player.REPEAT_MODE_OFF) { contract ->
                await("natural transition to second song", timeoutMs = 5_000L) {
                    contract.playSessions.size >= 2
                }
                assertEquals(listOf("first", "second"), contract.playSessions.toList())

                onMain { contract.playerController.playSong(1) }
                await("explicit replay of current song") { contract.playSessions.size >= 3 }
                SystemClock.sleep(300L)

                assertEquals(listOf("first", "second", "second"), contract.playSessions.toList())
            }
        } finally {
            firstFile.delete()
            secondFile.delete()
        }
    }

    @Test
    fun attachingSecondControllerDuringPlaybackDoesNotCount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFile = createSilentWav(context.cacheDir, "reconnect", durationSeconds = 5)
        val song = testSong("reconnect", Uri.fromFile(audioFile).toString(), durationSeconds = 5)

        try {
            withPlayback(context, listOf(song), Player.REPEAT_MODE_OFF) { contract ->
                val reconnectCount = AtomicInteger()
                val secondController = onMain {
                    PlayerController(
                        context = context,
                        mediaControllerConnector = ImmediateConnector(
                            contract.mediaController,
                            contract.boundaryDispatcher,
                        ),
                        sessionStorage = NoOpSessionStorage,
                        dispatcher = Dispatchers.Main.immediate,
                    ).apply {
                        onSongPlayStarted = { reconnectCount.incrementAndGet() }
                        setQueue(listOf(song))
                        connectIfNeeded()
                    }
                }
                try {
                    await("second controller attachment") { onMain { secondController.isConnected } }
                    SystemClock.sleep(750L)
                    assertEquals(0, reconnectCount.get())
                    assertEquals(listOf("reconnect"), contract.playSessions.toList())
                } finally {
                    onMain { secondController.release() }
                }
            }
        } finally {
            audioFile.delete()
        }
    }

    private fun withPlayback(
        context: Context,
        songs: List<Song>,
        repeatMode: Int,
        block: (PlaybackContract) -> Unit,
    ) {
        var playerToRelease: ExoPlayer? = null
        var sessionToRelease: MediaSession? = null
        var mediaControllerToRelease: MediaController? = null
        var playerControllerToRelease: PlayerController? = null
        val playSessions = CopyOnWriteArrayList<String>()
        val boundaryDispatcher = BoundaryDispatcher()
        val rawPlayerEvents = RawPlayerEvents { boundary ->
            sessionToRelease?.broadcastCustomCommand(
                PlaybackBoundarySessionEvent.command,
                PlaybackBoundarySessionEvent.encode(boundary),
            )
        }

        try {
            val player = onMain {
                ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    this.repeatMode = repeatMode
                    addListener(rawPlayerEvents)
                }
            }.also { playerToRelease = it }
            val session = onMain {
                MediaSession.Builder(context, player).build()
            }.also { sessionToRelease = it }
            val mediaController = MediaController.Builder(context, session.token)
                .setListener(object : MediaController.Listener {
                    override fun onCustomCommand(
                        controller: MediaController,
                        command: SessionCommand,
                        args: android.os.Bundle,
                    ) = Futures.immediateFuture(
                        if (PlaybackBoundarySessionEvent.decode(command, args)
                                ?.also(boundaryDispatcher::dispatch) != null
                        ) {
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } else {
                            SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                        },
                    )
                })
                .buildAsync()
                .get(5, TimeUnit.SECONDS)
                .also { mediaControllerToRelease = it }
            val playerController = onMain {
                PlayerController(
                    context = context,
                    mediaControllerConnector = ImmediateConnector(mediaController, boundaryDispatcher),
                    sessionStorage = NoOpSessionStorage,
                    dispatcher = Dispatchers.Main.immediate,
                ).apply {
                    onSongPlayStarted = { playSessions += it }
                    connectIfNeeded()
                    setQueue(songs)
                }
            }.also { playerControllerToRelease = it }

            await("MediaController queue") { onMain { mediaController.mediaItemCount == songs.size } }
            onMain {
                player.prepare()
                playerController.playSong(0)
            }
            await("initial explicit playback") { playSessions.size == 1 }

            block(
                PlaybackContract(
                    player = player,
                    mediaController = mediaController,
                    playerController = playerController,
                    playSessions = playSessions,
                    rawPlayerEvents = rawPlayerEvents,
                    boundaryDispatcher = boundaryDispatcher,
                ),
            )
        } finally {
            playerControllerToRelease?.let { onMain { it.release() } }
            mediaControllerToRelease?.let { onMain { it.release() } }
            sessionToRelease?.let { onMain { it.release() } }
            playerToRelease?.let { onMain { it.release() } }
        }
    }

    private fun testSong(id: String, uri: String, durationSeconds: Int) = Song(
        id = id,
        title = "Contract $id",
        artist = "Mica",
        album = "Tests",
        durationSec = durationSeconds,
        metadata = TrackMetadata(
            containerName = "WAV",
            sampleRateHz = SAMPLE_RATE,
            bitsPerSample = 16,
            bitrateKbps = 128,
            channelCount = 1,
            playbackMimeType = "audio/wav",
        ),
        albumArtUri = null,
        coverColorArgb = 0,
        mediaUri = uri,
        fileName = "$id.wav",
        lyricsDocument = listOf(
            LyricLine(timeMs = 0, text = "line-one"),
            LyricLine(timeMs = 500, text = "line-two"),
        ).toLyricsDocumentCompat(),
    )

    private fun createSilentWav(directory: File, id: String, durationSeconds: Int): File {
        val dataSize = SAMPLE_RATE * durationSeconds * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
        return File(directory, "media3-contract-$id.wav").also { file ->
            FileOutputStream(file).use { output ->
                output.write(header)
                output.write(ByteArray(dataSize))
            }
        }
    }

    private fun await(label: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue("Timed out waiting for $label", condition())
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private data class PlaybackContract(
        val player: ExoPlayer,
        val mediaController: MediaController,
        val playerController: PlayerController,
        val playSessions: CopyOnWriteArrayList<String>,
        val rawPlayerEvents: RawPlayerEvents,
        val boundaryDispatcher: BoundaryDispatcher,
    )

    private class RawPlayerEvents(
        private val onAutomaticBoundary: (ConfirmedPlaybackBoundary) -> Unit,
    ) : Player.Listener {
        val autoPositionWraps = AtomicInteger()
        val repeatTransitions = AtomicInteger()

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                repeatTransitions.incrementAndGet()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                oldPosition.positionMs > newPosition.positionMs
            ) {
                autoPositionWraps.incrementAndGet()
            }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                onAutomaticBoundary(
                    ConfirmedPlaybackBoundary(
                        oldSongId = oldPosition.mediaItem?.mediaId,
                        newSongId = newPosition.mediaItem?.mediaId,
                        oldPositionMs = oldPosition.positionMs,
                        newPositionMs = newPosition.positionMs,
                    ),
                )
            }
        }
    }

    private class BoundaryDispatcher {
        private val callbacks = CopyOnWriteArrayList<(ConfirmedPlaybackBoundary) -> Unit>()

        fun add(callback: (ConfirmedPlaybackBoundary) -> Unit) {
            callbacks += callback
        }

        fun remove(callback: (ConfirmedPlaybackBoundary) -> Unit) {
            callbacks -= callback
        }

        fun dispatch(boundary: ConfirmedPlaybackBoundary) {
            callbacks.forEach { it(boundary) }
        }
    }

    private class ImmediateConnector(
        private val controller: MediaController,
        private val boundaryDispatcher: BoundaryDispatcher,
    ) : MediaControllerConnector {
        override fun connect(
            onConnected: (MediaController) -> Unit,
            onDisconnected: () -> Unit,
            onFailure: (Throwable) -> Unit,
            onPlaybackBoundary: (ConfirmedPlaybackBoundary) -> Unit,
        ): MediaControllerConnection {
            boundaryDispatcher.add(onPlaybackBoundary)
            onConnected(controller)
            return object : MediaControllerConnection {
                override fun cancel() {
                    boundaryDispatcher.remove(onPlaybackBoundary)
                }
            }
        }
    }

    private object NoOpSessionStorage : PlaybackSessionStorage {
        override fun save(session: PlaybackSession?, sync: Boolean) = Unit
        override fun load(): PlaybackSession? = null
        override fun clear() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
    }
}
