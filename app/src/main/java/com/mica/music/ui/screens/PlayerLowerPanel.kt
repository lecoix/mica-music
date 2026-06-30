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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.PlaybackProgressState
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.data.SongTitleDisplay
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.LowerPanelFrame
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
    lyricsPageOpen: Boolean,
    lyricsPageImmersive: Boolean,
    lyricsAlignment: LyricsPageAlignment,
    lyricsFontSizeSp: Int,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode,
    stripSongTitleParentheses: Boolean,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSeekToMs: (Int) -> Unit,
    onToggleImmersive: () -> Unit,
    onToggleLyricsPageImmersive: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    spectrumEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = lower.spacing
    val lyricsFocus = lower.lyricsLayoutFocus
    val hideInfoAndLyrics = lower.hideInfoAndLyrics
    val displayTitle = SongTitleDisplay.displayTitle(activeSong.title, stripSongTitleParentheses)
    val hideLyricsPageChrome = lyricsPageOpen && lyricsPageImmersive
    val playLongPress = if (lyricsPageOpen) onToggleLyricsPageImmersive else null
    var compactLyricsCenterYPx by remember { mutableFloatStateOf(Float.NaN) }

    if (hideInfoAndLyrics) {
        Column(modifier.fillMaxSize()) {
            SongTitleSection(
                title = displayTitle,
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
            Spacer(Modifier.height(lower.photoStackTitleToControlsGap))
            PlayerLowerPanelChrome(
                surfaceState = surfaceState,
                colors = colors,
                seekState = seekState,
                lower = lower,
                spectrumEnabled = spectrumEnabled,
                hidden = hideLyricsPageChrome,
                onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
                onPrevious = onPrevious,
                onTogglePlay = onTogglePlay,
                onPlayLongPress = playLongPress,
                onNext = onNext,
                onOpenEqualizer = onOpenEqualizer,
                onOpenQueue = onOpenQueue,
            )
            Spacer(Modifier.weight(1f))
        }
        return
    }

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
                    .graphicsLayer { alpha = lower.compactContentAlpha },
            ) {
                Spacer(Modifier.height(spacing.afterCover))
                if (lower.showMetadata) {
                    if (!hideInfoAndLyrics) {
                        Box(
                            Modifier.graphicsLayer {
                                alpha = lower.metaAlpha * (1f - lower.immersiveProgress)
                                translationY = -lower.immersiveProgress * 12f
                            },
                        ) {
                            HiFiBadgeSection(
                                song = activeSong,
                                colors = if (lowerBackground.usesBlurredArtwork) {
                                    hifiBadgeColors
                                } else {
                                    colors
                                },
                            )
                        }
                        Spacer(Modifier.height(spacing.afterInfo))
                    }
                    SongTitleSection(
                        title = displayTitle,
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
                }
                if (!immersiveLower && !hideInfoAndLyrics) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                if (!lyricsPageOpen && lyricsFocus <= 0.01f) {
                                    compactLyricsCenterYPx =
                                        coordinates.positionInParent().y + coordinates.size.height / 2f
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsSection(
                            lyrics = lyrics,
                            positionMs = progressState.positionMs,
                            isPlaying = surfaceState.isPlaying,
                            colors = colors,
                            lineSlots = lower.lyricLineSlots,
                            onClick = onOpenLyrics,
                            bilingualDisplayMode = lyricsBilingualDisplayMode,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else if (!hideInfoAndLyrics) {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(
                    Modifier.height(
                        if (hideInfoAndLyrics) {
                            12.dp
                        } else {
                            spacing.beforePlaybackChrome
                        },
                    ),
                )
            }
            if (lyricsFocus > 0.01f && !hideInfoAndLyrics) {
                ExpandedLyricsPanel(
                    lyrics = lyrics,
                    positionMs = progressState.positionMs,
                    isPlaying = surfaceState.isPlaying,
                    colors = colors,
                    onLineClick = { timeMs ->
                        if (timeMs >= 0) onSeekToMs(timeMs)
                    },
                    lyricsAlignment = lyricsAlignment,
                    lyricsFontSizeSp = lyricsFontSizeSp,
                    bilingualDisplayMode = lyricsBilingualDisplayMode,
                    currentLineAnchorYPx = compactLyricsCenterYPx.takeIf { it.isFinite() },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lower.lyricsChromeFade },
                )
            }
        }

        PlayerLowerPanelChrome(
            surfaceState = surfaceState,
            colors = colors,
            seekState = seekState,
            lower = lower,
            spectrumEnabled = spectrumEnabled,
            hidden = hideLyricsPageChrome,
            onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
            onPrevious = onPrevious,
            onTogglePlay = onTogglePlay,
            onPlayLongPress = playLongPress,
            onNext = onNext,
            onOpenEqualizer = onOpenEqualizer,
            onOpenQueue = onOpenQueue,
        )
    }
}
