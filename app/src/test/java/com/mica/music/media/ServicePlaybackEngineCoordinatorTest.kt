package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackEngineCoordinatorTest {

    @Test
    fun dffIsRejectedBeforeExoPlaybackStarts() {
        val dff = SongFixtures.song(
            "dff",
            container = "DSD",
            mime = "audio/x-dsdiff",
            fileExtension = "dff",
        ).copy(fileName = "track.dff")
        val item = SongMediaItemCodec.encode(dff)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0)
        val player = MicaCompositePlayer(exo)
        var failure: PlaybackFailure? = null
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.onPlaybackFailure = { failure = it }
        coordinator.start()

        coordinator.onSelectMediaItem(0, 0L)

        verify(exactly = 0) {
            exo.setMediaItems(any<List<MediaItem>>(), any(), any())
        }
        assertEquals("不支持 DFF/DSDIFF 格式，请使用 DSF", failure?.message)
        coordinator.release()
    }

    @Test
    fun supportedSongStartsExoPlayback() {
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val item = SongMediaItemCodec.encode(flac)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.start()

        coordinator.onSelectMediaItem(0, 1_500L)

        verify(exactly = 1) {
            exo.setMediaItems(any<List<MediaItem>>(), 0, 1_500L)
        }
        verify(exactly = 1) { exo.prepare() }
        coordinator.release()
    }

    @Test
    fun skipToPreviousSeeksToStartWhenPastThreshold() {
        val song = SongFixtures.song()
        val item = SongMediaItemCodec.encode(song)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0, positionMs = 5_000L)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.start()

        coordinator.onSkipToPrevious()

        verify(exactly = 1) { exo.seekTo(0L) }
        coordinator.release()
    }

    @Test
    fun decodeFailureInvokesCallbackAndAutoSkipsWhenPossible() {
        val songs = listOf(
            SongFixtures.song("first", container = "FLAC", mime = "audio/flac"),
            SongFixtures.song("second", container = "FLAC", mime = "audio/flac"),
        )
        val items = songs.map(SongMediaItemCodec::encode)
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("Decoder failed"),
            "AudioRenderer",
            0,
            Format.Builder().setSampleMimeType("audio/flac").build(),
            C.FORMAT_HANDLED,
            false,
            ExoPlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        val exo = mockExoWithQueue(items, currentIndex = 0)
        every { exo.playerError } returns error
        val player = MicaCompositePlayer(exo)
        var failure: PlaybackFailure? = null
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.onPlaybackFailure = { failure = it }
        coordinator.start()
        coordinator.onSelectMediaItem(0, 0L)

        coordinator.onPlayerError(error)

        assertNotNull(failure)
        assertEquals(PlaybackFailureKind.DECODE_FAILED, failure?.kind)
        verify(atLeast = 1) {
            exo.setMediaItems(any<List<MediaItem>>(), any(), any())
        }
        coordinator.release()
    }

    @Test
    fun duplicateStartAtIsSkippedOnlyWhenPlaybackIsHealthy() {
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val item = SongMediaItemCodec.encode(flac)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0, positionMs = 0L)
        every { exo.isPlaying } returns false
        every { exo.playbackState } returns Player.STATE_READY
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.start()
        coordinator.onSelectMediaItem(0, 0L)
        coordinator.onSelectMediaItem(0, 0L)

        verify(atLeast = 2) {
            exo.setMediaItems(any<List<MediaItem>>(), any(), any())
        }
        coordinator.release()
    }

    @Test
    fun duplicateStartAtWhilePlayingDoesNotRestartExo() {
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val item = SongMediaItemCodec.encode(flac)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0, positionMs = 100L)
        every { exo.isPlaying } returns true
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
        )
        coordinator.start()
        coordinator.onSelectMediaItem(0, 100L)
        coordinator.onSelectMediaItem(0, 100L)

        verify(exactly = 1) {
            exo.setMediaItems(any<List<MediaItem>>(), any(), any())
        }
        coordinator.release()
    }

    private fun mockExoWithQueue(
        items: List<MediaItem>,
        currentIndex: Int,
        positionMs: Long = 0L,
    ): ExoPlayer {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns items.size
        every { exo.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { exo.currentMediaItem } returns items.getOrNull(currentIndex)
        every { exo.currentMediaItemIndex } returns currentIndex
        every { exo.currentPosition } returns positionMs
        every { exo.playbackState } returns Player.STATE_READY
        every { exo.playWhenReady } returns true
        return exo
    }
}
