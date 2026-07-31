package com.mica.music.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.data.AppUiSettings
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.PlayerPageUiModel
import com.mica.music.ui.theme.PlayerContentColors

@Composable
internal fun HorizontalClassicLyricsPage(
    pageModel: PlayerPageUiModel,
    uiSettings: AppUiSettings,
    surfaceState: PlaybackSurfaceState,
    song: Song,
    lyricsRenderState: LyricsRenderState,
    autoContentColors: PlayerContentColors,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    lowerBackground: PlayerLowerBackgroundMode,
    seekState: PlaybackSeekState,
    actions: NowPlayingActions,
    contentPadding: PaddingValues,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val lyricsFrame = pageModel.lyricsFrameFor(maxHeight)
        PlayerLowerPanelSection(
            surfaceState = surfaceState,
            activeSong = song,
            lyricsRenderState = lyricsRenderState,
            autoContentColors = autoContentColors,
            colors = colors,
            hifiBadgeColors = hifiBadgeColors,
            playerPageTextColorMode = uiSettings.playerPageTextColorMode,
            lowerBackground = lowerBackground,
            lower = lyricsFrame.lower,
            seekState = seekState,
            immersiveLower = false,
            lyricsPageOpen = true,
            lyricsPageImmersive = false,
            lyricsTextColorMode = uiSettings.lyricsPageTextColorMode,
            lyricsAlignment = uiSettings.lyricsPageAlignment,
            lyricsFontSizeSp = uiSettings.lyricsPageFontSizeSp,
            lyricsTranslationFontSizeSp = uiSettings.lyricsPageTranslationFontSizeSp,
            lyricsLineSpacingDp = uiSettings.lyricsPageLineSpacingDp,
            lyricsWordAnimationPreset = uiSettings.lyricsWordAnimationPreset,
            lyricsBilingualDisplayMode = uiSettings.lyricsBilingualDisplayMode,
            stripSongTitleParentheses = uiSettings.stripSongTitleParentheses,
            playerInfoVisibility = uiSettings.playerInfoVisibility,
            hiResBadgeAppearance = uiSettings.hiResBadgeAppearance,
            playbackTuning = surfaceState.playbackTuning,
            spectrumEnabled = false,
            onCyclePlaybackQueueMode = actions.cyclePlaybackQueueMode,
            onPrevious = actions.previous,
            onTogglePlay = actions.togglePlay,
            onNext = actions.next,
            onSeekToMs = actions.seekToMs,
            onToggleImmersive = {},
            onToggleLyricsPageImmersive = {},
            onOpenEqualizer = onOpenEqualizer,
            onOpenLyrics = {},
            onOpenQueue = onOpenQueue,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
