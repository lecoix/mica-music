@file:Suppress("DEPRECATION")

package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mica.music.data.LyricLine
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.data.toLyricsDocumentCompat
import com.mica.music.data.toMediaItem
import com.mica.music.media.dsf.DsfFormat
import com.mica.music.testutil.ContractTestSupport.await
import com.mica.music.testutil.ContractTestSupport.onMain
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealAudioDecodeContractTest {
    @Test
    fun singlePlayFromIdleSurvivesNotificationLyricMetadataReplacementWithoutReprepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = copyAsset(
            sourceContext = InstrumentationRegistry.getInstrumentation().context,
            outputDirectory = context.cacheDir,
            assetPath = "media/contract-silence-flac-96k-24bit.flac",
        )
        val song = Song(
            id = "notification-first-play-hires-flac",
            title = "Contract notification first play",
            artist = "Mica",
            album = "Tests",
            durationSec = 2,
            metadata = TrackMetadata(
                containerName = "FLAC",
                sampleRateHz = 96_000,
                bitsPerSample = 24,
                bitrateKbps = 4_608,
                channelCount = 2,
                playbackMimeType = MimeTypes.AUDIO_FLAC,
            ),
            albumArtUri = null,
            coverColorArgb = 0,
            mediaUri = Uri.fromFile(file).toString(),
            fileName = file.name,
            lyricsDocument = listOf(
                LyricLine(timeMs = 0, text = "notification-line-one"),
                LyricLine(timeMs = 500, text = "notification-line-two"),
            ).toLyricsDocumentCompat(),
        )
        val previousLyricsSetting = LyricsPreferences.notificationLyricsEnabled(context)
        val observation = DecodeObservation()
        val stack = onMain { ExoPlaybackStackFactory.build(context) }
        val engineCoordinator = onMain {
            ServicePlaybackEngineCoordinator(stack.compositePlayer, context).also { it.start() }
        }
        var lyricsCoordinator: NotificationLyricsCoordinator? = null

        try {
            LyricsPreferences.setNotificationLyricsEnabled(context, true)
            onMain {
                stack.exoPlayer.volume = 0f
                stack.exoPlayer.addAnalyticsListener(observation)
                stack.exoPlayer.setMediaItem(song.toMediaItem())
            }
            assertEquals(Player.STATE_IDLE, onMain { stack.exoPlayer.playbackState })
            assertEquals(0, observation.decoderInitializations.get())

            repeat(FIRST_PLAY_STRESS_ITERATIONS) { iteration ->
                val decoderInitializationsBeforePlay = observation.decoderInitializations.get()
                val positionAdvancingBeforePlay = observation.positionAdvancing.get()
                lyricsCoordinator = onMain {
                    NotificationLyricsCoordinator(
                        context = context,
                        player = stack.compositePlayer,
                        handler = Handler(Looper.getMainLooper()),
                        songLoader = { song },
                    ).also { it.start() }
                }
                onMain { stack.compositePlayer.play() }

                await("single play command iteration $iteration", timeoutMs = 10_000L) {
                    onMain {
                        stack.exoPlayer.playerError != null ||
                            observation.positionAdvancing.get() > positionAdvancingBeforePlay &&
                            stack.exoPlayer.currentMediaItem?.mediaMetadata?.title?.toString()
                                ?.startsWith("notification-line-") == true
                    }
                }
                SystemClock.sleep(250L)

                val playbackError = onMain { stack.exoPlayer.playerError }
                assertNull("Playback error on iteration $iteration: $playbackError", playbackError)
                assertTrue(onMain { stack.exoPlayer.isPlaying || stack.exoPlayer.currentPosition > 0L })
                assertEquals(
                    "Notification metadata must not reinitialize the decoder on iteration $iteration",
                    decoderInitializationsBeforePlay + 1,
                    observation.decoderInitializations.get(),
                )

                lyricsCoordinator?.let { onMain { it.release() } }
                lyricsCoordinator = null
                if (iteration < FIRST_PLAY_STRESS_ITERATIONS - 1) {
                    onMain {
                        stack.exoPlayer.stop()
                        stack.exoPlayer.setMediaItem(song.toMediaItem())
                    }
                    assertEquals(Player.STATE_IDLE, onMain { stack.exoPlayer.playbackState })
                }
            }
        } finally {
            lyricsCoordinator?.let { onMain { it.release() } }
            LyricsPreferences.setNotificationLyricsEnabled(context, previousLyricsSetting)
            onMain {
                engineCoordinator.release()
                stack.exoPlayer.release()
            }
            file.delete()
        }
    }

    @Test
    fun generatedAlacUsesFfmpegAndReachesAudioSink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = copyAsset(
            sourceContext = InstrumentationRegistry.getInstrumentation().context,
            outputDirectory = context.cacheDir,
            assetPath = "media/contract-silence-alac.m4a",
        )
        val observation = DecodeObservation()
        val stack = onMain { ExoPlaybackStackFactory.build(context) }

        try {
            onMain {
                stack.exoPlayer.volume = 0f
                stack.exoPlayer.addAnalyticsListener(observation)
                stack.exoPlayer.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("contract-alac")
                        .setUri(Uri.fromFile(file))
                        .setMimeType(MimeTypes.AUDIO_MP4)
                        .build(),
                )
                stack.exoPlayer.prepare()
                stack.exoPlayer.play()
            }

            await("ALAC decoder and AudioTrack delivery", timeoutMs = 10_000L) {
                observation.error.get() != null ||
                    observation.decoderName.get() != null && observation.positionAdvancing.get() > 0
            }
            assertNull("ALAC playback error: ${observation.error.get()}", observation.error.get())
            assertTrue(
                "Expected FFmpeg decoder, got ${observation.decoderName.get()}",
                observation.decoderName.get().orEmpty().contains("ffmpeg", ignoreCase = true),
            )
            assertEquals(MimeTypes.AUDIO_ALAC, observation.inputMime.get())
        } finally {
            onMain { stack.exoPlayer.release() }
            file.delete()
        }
    }

    @Test
    fun generatedDsfUsesFfmpegReachesAudioSinkAndSeeks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = createDsf(context.cacheDir)
        val observation = DecodeObservation()
        val stack = onMain { ExoPlaybackStackFactory.build(context) }

        try {
            onMain {
                stack.exoPlayer.volume = 0f
                stack.exoPlayer.addAnalyticsListener(observation)
                stack.exoPlayer.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("contract-dsf")
                        .setUri(Uri.fromFile(file))
                        .setMimeType(DsfFormat.MIME_CONTAINER_DSF)
                        .build(),
                )
                stack.exoPlayer.prepare()
                stack.exoPlayer.play()
            }

            await("DSF decoder and AudioTrack delivery", timeoutMs = 10_000L) {
                observation.error.get() != null ||
                    observation.decoderName.get() != null && observation.positionAdvancing.get() > 0
            }
            assertNull("DSF playback error: ${observation.error.get()}", observation.error.get())
            assertTrue(
                "Expected FFmpeg decoder, got ${observation.decoderName.get()}",
                observation.decoderName.get().orEmpty().contains("ffmpeg", ignoreCase = true),
            )
            assertEquals(DsfFormat.MIME_DSF, observation.inputMime.get())

            onMain {
                stack.exoPlayer.pause()
                stack.exoPlayer.seekTo(500L)
            }
            await("DSF seek position") { onMain { stack.exoPlayer.currentPosition >= 450L } }
        } finally {
            onMain { stack.exoPlayer.release() }
            file.delete()
        }
    }

    private fun createDsf(directory: File): File {
        val blockSize = 4_096
        val channelCount = 2
        val blockCount = 96
        val sampleRate = 2_822_400
        val sampleCount = blockSize.toLong() * 8L * blockCount
        val payloadSize = blockSize * channelCount * blockCount
        val totalSize = DsfFormat.DSD_CHUNK_SIZE + DsfFormat.FMT_CHUNK_SIZE +
            DsfFormat.DATA_HEADER_SIZE + payloadSize
        val header = ByteBuffer.allocate(
            (DsfFormat.DSD_CHUNK_SIZE + DsfFormat.FMT_CHUNK_SIZE + DsfFormat.DATA_HEADER_SIZE).toInt(),
        ).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(DsfFormat.CHUNK_DSD.toByteArray())
            putLong(DsfFormat.DSD_CHUNK_SIZE)
            putLong(totalSize)
            putLong(0L)
            put(DsfFormat.CHUNK_FMT.toByteArray())
            putLong(DsfFormat.FMT_CHUNK_SIZE)
            putInt(1)
            putInt(DsfFormat.FORMAT_ID_DSD_RAW)
            putInt(2)
            putInt(channelCount)
            putInt(sampleRate)
            putInt(1)
            putLong(sampleCount)
            putInt(blockSize)
            putInt(0)
            put(DsfFormat.CHUNK_DATA.toByteArray())
            putLong(DsfFormat.DATA_HEADER_SIZE + payloadSize)
        }.array()
        return File(directory, "mica-contract-silence.dsf").also { outputFile ->
            FileOutputStream(outputFile).use { output ->
                output.write(header)
                output.write(ByteArray(payloadSize) { DSD_SILENCE })
            }
        }
    }

    private fun copyAsset(sourceContext: Context, outputDirectory: File, assetPath: String): File =
        File(outputDirectory, assetPath.substringAfterLast('/')).also { outputFile ->
            sourceContext.assets.open(assetPath).use { input ->
                outputFile.outputStream().use(input::copyTo)
            }
        }

    @Suppress("DEPRECATION")
    private class DecodeObservation : AnalyticsListener {
        val decoderInitializations = AtomicInteger()
        val decoderName = AtomicReference<String>()
        val inputMime = AtomicReference<String>()
        val positionAdvancing = AtomicInteger()
        val error = AtomicReference<PlaybackException>()

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long,
        ) {
            decoderInitializations.incrementAndGet()
            this.decoderName.set(decoderName)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            inputMime.set(format.sampleMimeType)
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            positionAdvancing.incrementAndGet()
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            this.error.set(error)
        }
    }

    private companion object {
        const val DSD_SILENCE: Byte = 0x69
        const val FIRST_PLAY_STRESS_ITERATIONS = 10
    }
}
