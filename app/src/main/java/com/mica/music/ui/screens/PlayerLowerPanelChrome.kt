package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.screens.player.LowerPanelFrame
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

private val CoverEdgeChromeProgressSlide = HifiSpacing.lg

@Composable
internal fun PlayerLowerPanelChrome(
    surfaceState: PlaybackSurfaceState,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    lower: LowerPanelFrame,
    spectrumEnabled: Boolean,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val lyricsFocus = lower.lyricsLayoutFocus
    val density = LocalDensity.current
    val spectrumAlpha = (1f - lyricsFocus).coerceIn(0f, 1f)
    val transitionProgressSlidePx = with(density) {
        CoverEdgeChromeProgressSlide.toPx() * (1f - lyricsFocus.coerceIn(0f, 1f))
    }

    val controlsModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = HifiSpacing.lg)
        .padding(bottom = lower.controlsBottomPadding)

    Box(
        Modifier
            .fillMaxWidth()
            .height(lower.chromeHeight)
            .then(
                if (lower.coverEdgeOnPlaySurface) Modifier.clipToBounds() else Modifier,
            )
            .graphicsLayer { alpha = 1f - lower.immersiveProgress },
    ) {
        if (lower.coverEdgeOnPlaySurface) {
            if (lower.showChromeProgressInTransition) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            alpha = lower.chromeProgressAlpha
                            translationY = transitionProgressSlidePx
                        },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HifiSpacing.lg),
                    ) {
                        PlayerProgressBarSection(
                            seekState = seekState,
                            colors = colors,
                            spectrumEnabled = spectrumEnabled,
                            spectrumPlaying = surfaceState.isPlaying,
                            spectrumAlpha = spectrumAlpha,
                            spectrumHeight = 56.dp,
                        )
                        Spacer(Modifier.height(lower.spacing.afterProgress))
                    }
                }
            }
            PlayerPlaybackControlsSection(
                surfaceState = surfaceState,
                colors = colors,
                onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
                onPrevious = onPrevious,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpenEqualizer = onOpenEqualizer,
                onOpenQueue = onOpenQueue,
                modifier = controlsModifier.align(Alignment.BottomCenter),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                if (lower.showStandardProgress) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HifiSpacing.lg),
                    ) {
                        PlayerProgressBarSection(
                            seekState = seekState,
                            colors = colors,
                            spectrumEnabled = spectrumEnabled,
                            spectrumPlaying = surfaceState.isPlaying,
                            spectrumAlpha = spectrumAlpha,
                            spectrumHeight = 56.dp,
                        )
                        Spacer(Modifier.height(lower.spacing.afterProgress))
                    }
                }
                Spacer(Modifier.weight(1f))
                PlayerPlaybackControlsSection(
                    surfaceState = surfaceState,
                    colors = colors,
                    onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
                    onPrevious = onPrevious,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenQueue = onOpenQueue,
                    modifier = controlsModifier,
                )
            }
        }
    }
}
