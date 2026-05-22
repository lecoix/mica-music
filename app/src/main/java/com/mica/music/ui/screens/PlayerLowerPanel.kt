package com.mica.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import com.mica.music.data.LyricLine
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLowerPanelSection(
    playerController: PlayerController,
    activeSong: Song,
    lyrics: List<LyricLine>,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    lowerBackground: PlayerLowerBackgroundMode,
    spacing: PlayerLowerPanelSpacing,
    seekState: PlaybackSeekState,
    edgeAnchor: PlayerLowerPanelEdgeAnchorState,
    useCoverEdgeProgress: Boolean,
    coverEdgeOnPlaySurface: Boolean,
    lyricsExpanded: Boolean,
    lyricsChromeFade: Float,
    lyricsLayoutFocus: Float,
    immersiveLower: Boolean,
    immersiveProgress: Float,
    titleSlideDown: Dp,
    lyricLineSlots: Int,
    onToggleImmersive: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lowerPanelCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val displaySpacing = if (edgeAnchor.active) edgeAnchor.playSpacing else spacing
    val metaFade = 1f - lyricsLayoutFocus
    val showChromeProgressInTransition =
        useCoverEdgeProgress &&
            !coverEdgeOnPlaySurface &&
            (lyricsLayoutFocus > PlayerLowerPanelProgressEpsilon ||
                lyricsChromeFade > PlayerLowerPanelProgressEpsilon)
    val showStandardProgress =
        !coverEdgeOnPlaySurface && (!useCoverEdgeProgress || !edgeAnchor.active)
    val chromeProgressAlpha = when {
        !useCoverEdgeProgress -> 1f
        coverEdgeOnPlaySurface -> 0f
        else -> lyricsChromeFade.coerceIn(0f, 1f)
    }

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { lowerPanelCoords = it },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (immersiveLower) {
                        Modifier.combinedClickable(
                            onClick = { playerController.togglePlay() },
                            onLongClick = onToggleImmersive,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = metaFade },
            ) {
                Spacer(Modifier.height(displaySpacing.afterCover))
                Box(
                    Modifier.graphicsLayer {
                        alpha = metaFade * (1f - immersiveProgress)
                        translationY = -immersiveProgress * 12f
                    },
                ) {
                    HiFiBadgeSection(
                        song = activeSong,
                        colors = if (lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW) {
                            hifiBadgeColors
                        } else {
                            colors
                        },
                    )
                }
                Spacer(Modifier.height(displaySpacing.afterInfo))
                SongTitleSection(
                    title = activeSong.title,
                    artist = activeSong.artist,
                    album = activeSong.album,
                    isBuffering = playerController.isBuffering,
                    playbackError = playerController.playbackError,
                    colors = colors,
                    immersiveProgress = immersiveProgress,
                    modifier = Modifier.graphicsLayer {
                        translationY = titleSlideDown.toPx()
                    },
                    onLongPress = if (!immersiveLower) onToggleImmersive else null,
                )
                Spacer(Modifier.height(displaySpacing.afterSubtitle))
                if (!immersiveLower) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsSection(
                            lyrics = lyrics,
                            positionMs = playerController.positionMs,
                            colors = colors,
                            lineSlots = if (edgeAnchor.active) {
                                edgeAnchor.playLyricLineSlots
                            } else {
                                lyricLineSlots
                            },
                            onClick = onOpenLyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(
                    Modifier.height(displaySpacing.beforePlaybackChrome),
                )
            }
            if (lyricsLayoutFocus > 0.01f) {
                ExpandedLyricsPanel(
                    lyrics = lyrics,
                    positionMs = playerController.positionMs,
                    colors = colors,
                    onLineClick = { timeMs ->
                        if (timeMs >= 0) {
                            playerController.seekToMs(timeMs)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lyricsChromeFade },
                )
            }
        }

        PlayerLowerPanelChrome(
            playerController = playerController,
            colors = colors,
            seekState = seekState,
            chromeHeight = edgeAnchor.chromeHeight,
            controlsBottomPadding = edgeAnchor.controlsBottomPadding,
            afterProgress = displaySpacing.afterProgress,
            anchorToPanelBottom = edgeAnchor.active,
            showStandardProgress = showStandardProgress,
            showCoverEdgeTransitionProgress = showChromeProgressInTransition,
            chromeProgressAlpha = chromeProgressAlpha,
            lowerPanelCoords = lowerPanelCoords,
            onControlsBottomMeasured = edgeAnchor.onControlsBottomMeasured,
            lyricsLayoutFocus = lyricsLayoutFocus,
            onOpenQueue = onOpenQueue,
            clipChrome = !edgeAnchor.active,
            immersiveProgress = immersiveProgress,
        )
    }
}

