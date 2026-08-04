package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.LowerPanelFrame
import com.mica.music.ui.theme.PlayerContentColors

/**
 * PHOTO_STACK's lyrics target: the particle-style list and playback chrome, without the
 * lyrics-focus header. The whole page is animated by [PhotoStackLyricsTransitionState].
 */
@Composable
internal fun PhotoStackLyricsPage(
    renderState: LyricsRenderState,
    surfaceState: PlaybackSurfaceState,
    colors: PlayerContentColors,
    lower: LowerPanelFrame,
    seekState: PlaybackSeekState,
    lyricsPageImmersive: Boolean,
    lyricsAlignment: LyricsPageAlignment,
    lyricsFontSizeSp: Int,
    lyricsTranslationFontSizeSp: Int,
    lyricsLineSpacingDp: Int,
    lyricsWordAnimationPreset: LyricsWordAnimationPreset,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode,
    onLineClick: (Int) -> Unit,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onToggleLyricsPageImmersive: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            ExpandedLyricsPanel(
                renderState = renderState,
                isPlaying = surfaceState.isPlaying,
                colors = colors,
                onLineClick = onLineClick,
                lyricsAlignment = lyricsAlignment,
                lyricsFontSizeSp = lyricsFontSizeSp,
                lyricsTranslationFontSizeSp = lyricsTranslationFontSizeSp,
                lyricsLineSpacingDp = lyricsLineSpacingDp,
                lyricsWordAnimationPreset = lyricsWordAnimationPreset,
                bilingualDisplayMode = lyricsBilingualDisplayMode,
                modifier = Modifier.fillMaxSize(),
            )
        }
        PlayerLowerPanelChrome(
            surfaceState = surfaceState,
            colors = colors,
            seekState = seekState,
            lower = lower,
            spectrumEnabled = false,
            hidden = lyricsPageImmersive,
            onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
            onPrevious = onPrevious,
            onTogglePlay = onTogglePlay,
            onPlayLongPress = onToggleLyricsPageImmersive,
            onNext = onNext,
            onOpenEqualizer = onOpenEqualizer,
            onOpenQueue = onOpenQueue,
        )
    }
}
