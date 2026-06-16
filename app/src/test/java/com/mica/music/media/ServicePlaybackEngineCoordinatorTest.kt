package com.mica.music.media

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.mica.music.testutil.SongFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServicePlaybackEngineCoordinatorTest {
    @Test
    fun deniedSoftwareAudioFocusDoesNotStartEngine() {
        val song = SongFixtures.song("dsd", container = "DSD", mime = "audio/x-dsf")
        val item = SongMediaItemCodec.encode(song)
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns 1
        every { exo.getMediaItemAt(0) } returns item
        every { exo.currentMediaItem } returns item
        every { exo.currentMediaItemIndex } returns 0
        every { exo.currentPosition } returns 0L
        every { exo.playbackState } returns Player.STATE_READY
        val player = MicaCompositePlayer(exo)
        val engine = mockk<AlacAudioTrackEngine>(relaxed = true)
        val focus = object : SoftwareAudioFocusGate {
            override fun request(generation: Long): Boolean = false
            override fun abandon(generation: Long) = Unit
        }
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
            engine = engine,
            audioFocusGate = focus,
        )
        coordinator.start()

        player.play()

        verify(exactly = 0) {
            engine.play(any(), any(), any())
        }
        assertFalse(player.playWhenReady)
        coordinator.release()
    }

    @Test
    fun softwareSeekIsForwardedToEngineAtRequestedPosition() {
        val song = SongFixtures.song("dsd", container = "DSD", mime = "audio/x-dsf")
        val item = SongMediaItemCodec.encode(song)
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.mediaItemCount } returns 1
        every { exo.getMediaItemAt(0) } returns item
        every { exo.currentMediaItem } returns item
        every { exo.currentMediaItemIndex } returns 0
        every { exo.currentPosition } returns 0L
        every { exo.playbackState } returns Player.STATE_READY
        val player = MicaCompositePlayer(exo)
        val engine = mockk<AlacAudioTrackEngine>(relaxed = true)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
            engine = engine,
        )
        coordinator.start()

        player.play()
        player.seekTo(25_000L)

        verify(exactly = 1) {
            engine.seekToMs(25_000, startPlayback = true)
        }
        coordinator.release()
    }

    @Test
    fun playerVolumeIsForwardedToSoftwareEngine() {
        val exo = mockk<ExoPlayer>(relaxed = true)
        val player = MicaCompositePlayer(exo)
        val engine = mockk<AlacAudioTrackEngine>(relaxed = true)
        val coordinator = ServicePlaybackEngineCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            player = player,
            engine = engine,
        )
        coordinator.start()

        player.volume = 0.35f

        verify(exactly = 1) {
            engine.setVolume(0.35f)
        }
        coordinator.release()
    }
}
