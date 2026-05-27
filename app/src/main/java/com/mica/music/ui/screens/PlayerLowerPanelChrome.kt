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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerController
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

private val CoverEdgeChromeProgressSlide = HifiSpacing.lg

@Composable
internal fun PlayerLowerPanelChrome(
    playerController: PlayerController,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    chromeHeight: Dp,
    controlsBottomPadding: Dp,
    afterProgress: Dp,
    anchorToPanelBottom: Boolean,
    showStandardProgress: Boolean,
    showCoverEdgeTransitionProgress: Boolean,
    chromeProgressAlpha: Float,
    spectrumEnabled: Boolean,
    lowerPanelCoords: LayoutCoordinates?,
    onControlsBottomMeasured: (bottomFromPanel: Dp, settledOnLyrics: Boolean) -> Unit,
    lyricsLayoutFocus: Float,
    onOpenQueue: () -> Unit,
    clipChrome: Boolean,
    immersiveProgress: Float,
) {
    val settledOnLyrics = lyricsLayoutFocus >= 1f - PlayerLowerPanelProgressEpsilon
    val settledOnPlay = lyricsLayoutFocus <= PlayerLowerPanelProgressEpsilon
    val density = LocalDensity.current
    val spectrumAlpha = (1f - lyricsLayoutFocus).coerceIn(0f, 1f)
    val transitionProgressSlidePx = with(density) {
        CoverEdgeChromeProgressSlide.toPx() * (1f - lyricsLayoutFocus.coerceIn(0f, 1f))
    }

    val controlsModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = HifiSpacing.lg)
        .padding(bottom = controlsBottomPadding)
        .onGloballyPositioned { coords ->
            val panel = lowerPanelCoords ?: return@onGloballyPositioned
            val panelBottomY = panel.positionInRoot().y + panel.size.height
            val controlsBottomY = coords.positionInRoot().y + coords.size.height
            val bottomDp = with(density) { (panelBottomY - controlsBottomY).toDp() }
            when {
                settledOnLyrics -> onControlsBottomMeasured(bottomDp, true)
                settledOnPlay -> onControlsBottomMeasured(bottomDp, false)
            }
        }

    Box(
        Modifier
            .fillMaxWidth()
            .height(chromeHeight)
            .then(if (clipChrome) Modifier.clipToBounds() else Modifier)
            .graphicsLayer { alpha = 1f - immersiveProgress },
    ) {
        if (anchorToPanelBottom) {
            if (showCoverEdgeTransitionProgress) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            alpha = chromeProgressAlpha
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
                            spectrumPlaying = playerController.isPlaying,
                            spectrumAlpha = spectrumAlpha,
                            spectrumHeight = 56.dp,
                        )
                        Spacer(Modifier.height(afterProgress))
                    }
                }
            }
            PlayerPlaybackControlsSection(
                playerController = playerController,
                colors = colors,
                onOpenQueue = onOpenQueue,
                modifier = controlsModifier.align(Alignment.BottomCenter),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                if (showStandardProgress) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = HifiSpacing.lg),
                    ) {
                        PlayerProgressBarSection(
                            seekState = seekState,
                            colors = colors,
                        )
                        Spacer(Modifier.height(afterProgress))
                    }
                }
                Spacer(Modifier.weight(1f))
                PlayerPlaybackControlsSection(
                    playerController = playerController,
                    colors = colors,
                    onOpenQueue = onOpenQueue,
                    modifier = controlsModifier,
                )
            }
        }
    }
}
