package com.mica.music.data.preferences

import com.mica.music.data.BrowseListInfoVisibility
import com.mica.music.data.CompactLyricsLineMode
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongTrailingInfo
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
    @Test
    fun videoAlbumCoverDefaultsOffAndPersists() {
        assertEquals(false, PlaybackUiPreferences.videoAlbumCoverEnabled(context))

        PlaybackUiPreferences.setVideoAlbumCoverEnabled(context, true)

        assertTrue(PlaybackUiPreferences.videoAlbumCoverEnabled(context))
    }


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
    fun compactLyricsLineModeDefaultsAutoAndRoundTrips() {
        assertEquals(CompactLyricsLineMode.AUTO, PlaybackUiPreferences.compactLyricsLineMode(context))

        PlaybackUiPreferences.setCompactLyricsLineMode(context, CompactLyricsLineMode.ONE)
        assertEquals(CompactLyricsLineMode.ONE, PlaybackUiPreferences.compactLyricsLineMode(context))

        PlaybackUiPreferences.setCompactLyricsLineMode(context, CompactLyricsLineMode.THREE)
        assertEquals(CompactLyricsLineMode.THREE, PlaybackUiPreferences.compactLyricsLineMode(context))
    }

    @Test
    fun customStandardCoverTapPlayPauseDefaultsOffAndRoundTrips() {
        assertEquals(false, PlaybackUiPreferences.customStandardCoverTapPlayPause(context))

        PlaybackUiPreferences.setCustomStandardCoverTapPlayPause(context, true)

        assertTrue(PlaybackUiPreferences.customStandardCoverTapPlayPause(context))
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
    fun customPlayerLowerLayoutRoundTrips() {
        val config = PlayerLowerLayoutConfig.Default
            .move(PlayerLowerComponent.CONTROLS, -2)
            .withVisibility(PlayerLowerComponent.INFO, false)
            .withScalePercent(PlayerLowerComponent.TITLE, 175)
            .copy(
                spacingDp = 16,
                topPaddingDp = 32,
                bottomPaddingDp = 48,
                lyricsLineCount = PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT,
            )

        PlaybackUiPreferences.setCustomPlayerLowerLayout(context, config)

        assertEquals(config, PlaybackUiPreferences.customPlayerLowerLayout(context))
    }

    @Test
    fun legacyCustomPlayerSizesMigrateToPercentages() {
        MicaSettingsStore.prefs(context).edit()
            .putString("custom_player_lower_sizes", "info:small,title:large")
            .apply()

        val config = PlaybackUiPreferences.customPlayerLowerLayout(context)

        assertEquals(85, config.scalePercentOf(PlayerLowerComponent.INFO))
        assertEquals(115, config.scalePercentOf(PlayerLowerComponent.TITLE))
    }

    @Test
    fun legacyCustomPlayerOrderAddsCoverAtTop() {
        MicaSettingsStore.prefs(context).edit()
            .putString("custom_player_lower_order", "title,lyrics,progress,controls,info")
            .apply()

        val config = PlaybackUiPreferences.customPlayerLowerLayout(context)

        assertEquals(PlayerLowerComponent.COVER, config.order.first())
        assertEquals(PlayerLowerComponent.entries.toSet(), config.order.toSet())
        assertTrue(config.isVisible(PlayerLowerComponent.COVER))
        assertEquals(PlayerLowerLayoutConfig.DEFAULT_SCALE_PERCENT, config.scalePercentOf(PlayerLowerComponent.COVER))
    }

    @Test
    fun songListInfoVisibilityRoundTrips() {
        val visibility = SongListInfoVisibility(
            showSongArtist = false,
            showSongAlbum = false,
            showSongPlayCount = false,
            showSongDuration = true,
            trailingInfo = SongTrailingInfo.PLAY_COUNT,
        )

        PlaybackUiPreferences.setSongListInfoVisibility(context, visibility)

        assertEquals(visibility, PlaybackUiPreferences.songListInfoVisibility(context))
    }

    @Test
    fun browseListInfoVisibilityRoundTrips() {
        val visibility = BrowseListInfoVisibility(
            showArtistCount = false,
            showArtistGridColumns = false,
            showArtistCustomText = true,
            artistCustomText = "现场录音",
            showAlbumCount = false,
            showAlbumLastScanTime = false,
            showAlbumCustomText = true,
            albumCustomText = "珍藏版",
        )

        PlaybackUiPreferences.setBrowseListInfoVisibility(context, visibility)

        assertEquals(visibility, PlaybackUiPreferences.browseListInfoVisibility(context))
    }

    @Test
    fun photoStackModeFromStorage() {
        assertEquals(PlayerCoverFlowMode.PHOTO_STACK, PlayerCoverFlowMode.fromStorage("photo_stack"))
        assertEquals(PlayerCoverFlowMode.CUSTOM_STANDARD, PlayerCoverFlowMode.fromStorage("custom_standard"))
        assertEquals(PlayerCoverFlowMode.STANDARD, PlayerCoverFlowMode.fromStorage("missing"))
    }
}
