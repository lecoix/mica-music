package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackEngineCoordinatorTest {

    @Test
    fun dffSelectionStopsOldPlaybackAndCannotRestartIt() {
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
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.onPlaybackFailure = { failure = it }
        coordinator.start()

        coordinator.onSelectMediaItem(0, 0L)

        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(exactly = 1) { exo.seekTo(0, 0L) }
        verify(exactly = 1) { exo.pause() }
        verify(exactly = 0) { exo.prepare() }
        assertEquals("不支持 DFF/DSDIFF 格式，请使用 DSF", failure?.message)

        coordinator.playCurrent()

        verify(exactly = 0) { exo.play() }
        coordinator.release()
    }

    @Test
    fun indexedSelectionPreservesPausedIntentDuringRestore() {
        val items = listOf(
            SongMediaItemCodec.encode(SongFixtures.song("first")),
            SongMediaItemCodec.encode(SongFixtures.song("second")),
        )
        val exo = mockExoWithQueue(items, currentIndex = 0, playWhenReady = false)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()

        player.playWhenReady = false
        player.seekTo(1, 12_345L)

        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(exactly = 1) { exo.seekTo(1, 12_345L) }
        verify(atLeast = 1) { exo.playWhenReady = false }
        verify(exactly = 0) { exo.play() }
        coordinator.release()
    }

    @Test
    fun supportedSongStartsExoPlayback() {
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val item = SongMediaItemCodec.encode(flac)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()

        coordinator.onSelectMediaItem(0, 1_500L)

        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(exactly = 1) { exo.seekTo(0, 1_500L) }
        verify(exactly = 0) { exo.prepare() }
        coordinator.release()
    }

    @Test
    fun changedQueueOverrideStillRebuildsExoPlaylistOnce() {
        val first = SongMediaItemCodec.encode(SongFixtures.song("first"))
        val second = SongMediaItemCodec.encode(SongFixtures.song("second"))
        val exo = mockExoWithQueue(listOf(first), currentIndex = 0)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()
        PendingPlaybackNavigation.prepare("second", listOf(first, second))

        player.seekTo(1, 0L)

        verify(exactly = 1) {
            exo.setMediaItems(match { it.map(MediaItem::mediaId) == listOf("first", "second") }, 1, 0L)
        }
        coordinator.release()
    }

    @Test
    fun skipToPreviousSeeksToStartWhenPastThreshold() {
        val song = SongFixtures.song()
        val item = SongMediaItemCodec.encode(song)
        val exo = mockExoWithQueue(listOf(item), currentIndex = 0, positionMs = 5_000L)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()

        coordinator.onSkipToPrevious()

        verify(exactly = 1) { exo.seekTo(0L) }
        coordinator.release()
    }

    @Test
    fun skipToNextUsesAdjacentPlaylistItem() {
        val items = listOf("first", "second", "third", "fourth")
            .map { SongMediaItemCodec.encode(SongFixtures.song(it)) }
        val exo = mockExoWithQueue(items, currentIndex = 1)
        val player = MicaCompositePlayer(exo)
        val coordinator = ServicePlaybackEngineCoordinator(
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()

        coordinator.onSkipToNext()

        verify(exactly = 1) { exo.seekTo(2, 0L) }
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
            player = player,
            context = RuntimeEnvironment.getApplication(),
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
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()
        coordinator.onSelectMediaItem(0, 0L)
        coordinator.onSelectMediaItem(0, 0L)

        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(atLeast = 2) { exo.seekTo(0, 0L) }
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
            player = player,
            context = RuntimeEnvironment.getApplication(),
        )
        coordinator.start()
        coordinator.onSelectMediaItem(0, 100L)
        coordinator.onSelectMediaItem(0, 100L)

        verify(exactly = 0) { exo.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        verify(exactly = 1) { exo.seekTo(0, 100L) }
        coordinator.release()
    }

    private fun mockExoWithQueue(
        items: List<MediaItem>,
        currentIndex: Int,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): ExoPlayer {
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns items.size
        every { exo.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { exo.currentMediaItem } returns items.getOrNull(currentIndex)
        every { exo.currentMediaItemIndex } returns currentIndex
        every { exo.currentPosition } returns positionMs
        every { exo.playbackState } returns Player.STATE_READY
        every { exo.playWhenReady } returns playWhenReady
        return exo
    }
}
