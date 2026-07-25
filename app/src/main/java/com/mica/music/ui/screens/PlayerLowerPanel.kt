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
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.LyricsPageAlignment
import com.mica.music.data.LyricsWordAnimationPreset
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.data.SongTitleDisplay
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.components.DirectionalTrackWipe
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.screens.player.LowerPanelFrame
import com.mica.music.ui.theme.rememberLyricsContentColors
import com.mica.music.ui.theme.PlayerContentColors

private data class LowerTrackVisual(
    val song: Song,
    val surfaceState: PlaybackSurfaceState,
    val lyricsRenderState: LyricsRenderState,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLowerPanelSection(
    surfaceState: PlaybackSurfaceState,
    activeSong: Song,
    lyricsRenderState: LyricsRenderState,
    autoContentColors: PlayerContentColors,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    playerPageTextColorMode: PlaybackContentColorMode,
    lowerBackground: PlayerLowerBackgroundMode,
    lower: LowerPanelFrame,
    seekState: PlaybackSeekState,
    immersiveLower: Boolean,
    lyricsPageOpen: Boolean,
    lyricsPageImmersive: Boolean,
    lyricsTextColorMode: PlaybackContentColorMode,
    lyricsAlignment: LyricsPageAlignment,
    lyricsFontSizeSp: Int,
    lyricsTranslationFontSizeSp: Int,
    lyricsLineSpacingDp: Int,
    lyricsWordAnimationPreset: LyricsWordAnimationPreset,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode,
    stripSongTitleParentheses: Boolean,
    playerInfoVisibility: PlayerInfoVisibility,
    playbackTuning: PlaybackTuning,
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
    showCompactLyrics: Boolean = true,
    trackSkipDirection: TrackSkipDirection? = null,
    trackWipeMotionEnabled: Boolean = true,
    titleModifier: Modifier = Modifier,
    chromeModifier: Modifier = Modifier,
    metaModifier: Modifier = Modifier,
    compactLyricsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val spacing = lower.spacing
    val lyricsFocus = lower.lyricsLayoutFocus
    val hideInfoAndLyrics = lower.hideInfoAndLyrics
    val hideLyricsPageChrome = lyricsPageOpen && lyricsPageImmersive
    val playLongPress = if (lyricsPageOpen) onToggleLyricsPageImmersive else null
    val lyricsColors = rememberLyricsContentColors(autoContentColors, lyricsTextColorMode)
    val infoBarColors = when {
        playerPageTextColorMode != PlaybackContentColorMode.AUTO -> colors
        lowerBackground.usesBlurredArtwork -> hifiBadgeColors
        else -> colors
    }
    val showPlayerInfoRow = playerInfoVisibility.hasAnyEnabledSegment()

    if (hideInfoAndLyrics) {
        Column(modifier.fillMaxSize()) {
            DirectionalTrackWipe(
                targetState = activeSong,
                contentKey = Song::id,
                direction = trackSkipDirection,
                motionEnabled = trackWipeMotionEnabled,
            ) { visualSong ->
                SongTitleSection(
                    title = SongTitleDisplay.displayTitle(visualSong.title, stripSongTitleParentheses),
                    artist = visualSong.artist,
                    album = visualSong.album,
                    isBuffering = surfaceState.isBuffering,
                    playbackError = surfaceState.playbackError,
                    colors = colors,
                    immersiveProgress = lower.immersiveProgress,
                    modifier = titleModifier.graphicsLayer {
                        translationY = lower.titleSlideDown.toPx()
                    },
                    onLongPress = if (!immersiveLower) onToggleImmersive else null,
                )
            }
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
                modifier = chromeModifier,
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
            DirectionalTrackWipe(
                targetState = LowerTrackVisual(activeSong, surfaceState, lyricsRenderState),
                contentKey = { it.song.id },
                direction = trackSkipDirection,
                motionEnabled = trackWipeMotionEnabled,
                modifier = Modifier.fillMaxSize(),
            ) { visual ->
                Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lower.compactContentAlpha },
                ) {
                Spacer(Modifier.height(spacing.afterCover))
                if (lower.showMetadata) {
                    if (!hideInfoAndLyrics && showPlayerInfoRow) {
                        Box(
                            Modifier
                                .graphicsLayer {
                                    alpha = lower.metaAlpha * (1f - lower.immersiveProgress)
                                    translationY = -lower.immersiveProgress * 12f
                                }
                                .then(metaModifier),
                        ) {
                            HiFiBadgeSection(
                                song = visual.song,
                                colors = infoBarColors,
                                playerInfoVisibility = playerInfoVisibility,
                                playbackTuning = playbackTuning,
                            )
                        }
                        Spacer(Modifier.height(spacing.afterInfo))
                    }
                    SongTitleSection(
                        title = SongTitleDisplay.displayTitle(visual.song.title, stripSongTitleParentheses),
                        artist = visual.song.artist,
                        album = visual.song.album,
                        isBuffering = visual.surfaceState.isBuffering,
                        playbackError = visual.surfaceState.playbackError,
                        colors = colors,
                        immersiveProgress = lower.immersiveProgress,
                        modifier = titleModifier.graphicsLayer {
                            translationY = lower.titleSlideDown.toPx()
                        },
                        onLongPress = if (!immersiveLower) onToggleImmersive else null,
                    )
                    Spacer(Modifier.height(spacing.afterSubtitle))
                }
                if (!immersiveLower && !hideInfoAndLyrics && showCompactLyrics) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(compactLyricsModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        LyricsSection(
                            renderState = visual.lyricsRenderState,
                            isPlaying = visual.surfaceState.isPlaying,
                            colors = lyricsColors,
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
                    renderState = visual.lyricsRenderState,
                    isPlaying = visual.surfaceState.isPlaying,
                    colors = lyricsColors,
                    onLineClick = { timeMs ->
                        if (timeMs >= 0) onSeekToMs(timeMs)
                    },
                    lyricsAlignment = lyricsAlignment,
                    lyricsFontSizeSp = lyricsFontSizeSp,
                    lyricsTranslationFontSizeSp = lyricsTranslationFontSizeSp,
                    lyricsLineSpacingDp = lyricsLineSpacingDp,
                    lyricsWordAnimationPreset = lyricsWordAnimationPreset,
                    bilingualDisplayMode = lyricsBilingualDisplayMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = lower.lyricsChromeFade },
                )
            }
                }
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
            modifier = chromeModifier,
        )
    }
}
