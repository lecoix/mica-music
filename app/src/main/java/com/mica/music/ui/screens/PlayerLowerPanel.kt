package com.mica.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricLine
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.LowerPanelFrame
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLowerPanelSection(
    surfaceState: PlaybackSurfaceState,
    progressState: PlaybackProgressState,
    activeSong: Song,
    lyrics: List<LyricLine>,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    lowerBackground: PlayerLowerBackgroundMode,
    lower: LowerPanelFrame,
    seekState: PlaybackSeekState,
    immersiveLower: Boolean,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSeekToMs: (Int) -> Unit,
    onToggleImmersive: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    spectrumEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = lower.spacing
    val lyricsFocus = lower.lyricsLayoutFocus

    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (immersiveLower) {
                        Modifier.combinedClickable(
                            onClick = onTogglePlay,
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
                    .graphicsLayer { alpha = lower.metaAlpha },
            ) {
                Spacer(Modifier.height(spacing.afterCover))
                Box(
                    Modifier.graphicsLayer {
                        alpha = lower.metaAlpha * (1f - lower.immersiveProgress)
                        translationY = -lower.immersiveProgress * 12f
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
                Spacer(Modifier.height(spacing.afterInfo))
                SongTitleSection(
                    title = activeSong.title,
                    artist = activeSong.artist,
                    album = activeSong.album,
                    isBuffering = surfaceState.isBuffering,
                    playbackError = surfaceState.playbackError,
                    colors = colors,
                    immersiveProgress = lower.immersiveProgress,
                    modifier = Modifier.graphicsLayer {
                        translationY = lower.titleSlideDown.toPx()
                    },
                    onLongPress = if (!immersiveLower) onToggleImmersive else null,
                )
                Spacer(Modifier.height(spacing.afterSubtitle))
                if (!immersiveLower) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsSection(
                            lyrics = lyrics,
                            positionMs = progressState.positionMs,
                            colors = colors,
                            lineSlots = lower.lyricLineSlots,
                            onClick = onOpenLyrics,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(spacing.beforePlaybackChrome))
            }
            if (lyricsFocus > 0.01f) {
                ExpandedLyricsPanel(
                    lyrics = lyrics,
                    positionMs = progressState.positionMs,
                    colors = colors,
                    onLineClick = { timeMs ->
                        if (timeMs >= 0) onSeekToMs(timeMs)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lower.lyricsChromeFade },
                )
            }
            if (spectrumEnabled && lower.showStandardProgress && lower.spectrumOverlayAlpha > 0.01f) {
                LivePlayerSpectrumStrip(
                    enabled = true,
                    isPlaying = surfaceState.isPlaying,
                    colors = colors,
                    height = 56.dp,
                    alpha = lower.spectrumOverlayAlpha,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = HifiSpacing.lg)
                        .graphicsLayer { translationY = 15.dp.toPx() },
                )
            }
        }

        PlayerLowerPanelChrome(
            surfaceState = surfaceState,
            colors = colors,
            seekState = seekState,
            lower = lower,
            spectrumEnabled = spectrumEnabled,
            onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
            onPrevious = onPrevious,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onOpenEqualizer = onOpenEqualizer,
            onOpenQueue = onOpenQueue,
        )
    }
}
