package com.mica.music.data.preferences

import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.SongListInfoVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class PlaybackUiPreferencesRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun particleCoverTuningRoundTrips() {
        val tuning = ParticleCoverTuning(
            erosionScale = 1.35f,
            featherScale = 1.7f,
            edgeParticleDensity = 0.88f,
            edgeParticleAlpha = 1.2f,
            edgeTravelScale = 0.4f,
            transitionParticleDensity = 1f,
        )

        PlaybackUiPreferences.setParticleCoverTuning(context, tuning)

        assertEquals(tuning, PlaybackUiPreferences.particleCoverTuning(context))
    }

    @Test
    fun keepScreenOnWhenPlayingRoundTrips() {
        assertEquals(false, PlaybackUiPreferences.keepScreenOnWhenPlaying(context))

        PlaybackUiPreferences.setKeepScreenOnWhenPlaying(context, true)

        assertTrue(PlaybackUiPreferences.keepScreenOnWhenPlaying(context))
    }

    @Test
    fun audioFocusEnabledDefaultsOnAndRoundTrips() {
        assertTrue(PlaybackUiPreferences.audioFocusEnabled(context))

        PlaybackUiPreferences.setAudioFocusEnabled(context, false)

        assertEquals(false, PlaybackUiPreferences.audioFocusEnabled(context))
    }

    @Test
    fun miniPlayerLyricsEnabledRoundTrips() {
        assertTrue(PlaybackUiPreferences.miniPlayerLyricsEnabled(context))

        PlaybackUiPreferences.setMiniPlayerLyricsEnabled(context, false)

        assertEquals(false, PlaybackUiPreferences.miniPlayerLyricsEnabled(context))
    }

    @Test
    fun miniPlayerSwipeSettingsRoundTrip() {
        assertEquals(false, PlaybackUiPreferences.miniPlayerSwipeEnabled(context))
        assertEquals(MiniPlayerSwipeAction.NEXT, PlaybackUiPreferences.miniPlayerLeftSwipeAction(context))
        assertEquals(MiniPlayerSwipeAction.PREVIOUS, PlaybackUiPreferences.miniPlayerRightSwipeAction(context))

        PlaybackUiPreferences.setMiniPlayerSwipeEnabled(context, true)
        PlaybackUiPreferences.setMiniPlayerLeftSwipeAction(context, MiniPlayerSwipeAction.PREVIOUS)
        PlaybackUiPreferences.setMiniPlayerRightSwipeAction(context, MiniPlayerSwipeAction.NEXT)

        assertTrue(PlaybackUiPreferences.miniPlayerSwipeEnabled(context))
        assertEquals(MiniPlayerSwipeAction.PREVIOUS, PlaybackUiPreferences.miniPlayerLeftSwipeAction(context))
        assertEquals(MiniPlayerSwipeAction.NEXT, PlaybackUiPreferences.miniPlayerRightSwipeAction(context))
    }

    @Test
    fun playerInfoVisibilityRoundTrips() {
        val visibility = PlayerInfoVisibility(
            showFormat = false,
            showSampleRate = true,
            showBitrate = false,
            showPlaybackSpeed = true,
            showPlaybackPitch = true,
            showCurrentTime = true,
            showCustomText = true,
            customText = "Hi-Res",
        )

        PlaybackUiPreferences.setPlayerInfoVisibility(context, visibility)

        assertEquals(visibility, PlaybackUiPreferences.playerInfoVisibility(context))
    }

    @Test
    fun songListInfoVisibilityRoundTrips() {
        val visibility = SongListInfoVisibility(
            showSongArtist = false,
            showSongAlbum = false,
            showSongPlayCount = false,
            showSongDuration = true,
        )

        PlaybackUiPreferences.setSongListInfoVisibility(context, visibility)

        assertEquals(visibility, PlaybackUiPreferences.songListInfoVisibility(context))
    }

    @Test
    fun photoStackModeFromStorage() {
        assertEquals(PlayerCoverFlowMode.PHOTO_STACK, PlayerCoverFlowMode.fromStorage("photo_stack"))
        assertEquals(PlayerCoverFlowMode.STANDARD, PlayerCoverFlowMode.fromStorage("missing"))
    }
}
