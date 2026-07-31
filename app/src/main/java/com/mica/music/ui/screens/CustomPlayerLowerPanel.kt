package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.PlaybackContentColorMode
import com.mica.music.data.PlaybackSurfaceState
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.HiResBadgeAppearance
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.PlayerLowerComponent
import com.mica.music.data.PlayerLowerLayoutConfig
import com.mica.music.data.Song
import com.mica.music.data.SongTitleDisplay
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.components.DirectionalTrackWipe
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.theme.CustomPlayerInfoRowHeightDp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.rememberLyricsContentColors

private data class CustomLyricsVisual(
    val song: Song,
    val renderState: LyricsRenderState,
    val isPlaying: Boolean,
)

@Composable
internal fun CustomPlayerPagePanel(
    config: PlayerLowerLayoutConfig,
    coverBaseHeightDp: Float,
    coverContent: @Composable (visualScale: Float) -> Unit,
    surfaceState: PlaybackSurfaceState,
    activeSong: Song,
    lyricsRenderState: LyricsRenderState,
    autoContentColors: PlayerContentColors,
    colors: PlayerContentColors,
    hifiBadgeColors: PlayerContentColors,
    playerPageTextColorMode: PlaybackContentColorMode,
    lowerBackground: PlayerLowerBackgroundMode,
    seekState: PlaybackSeekState,
    lyricsTextColorMode: PlaybackContentColorMode,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    stripSongTitleParentheses: Boolean,
    playerInfoVisibility: PlayerInfoVisibility,
    hiResBadgeAppearance: HiResBadgeAppearance,
    playbackTuning: PlaybackTuning,
    spectrumEnabled: Boolean,
    trackSkipDirection: TrackSkipDirection?,
    trackWipeMotionEnabled: Boolean,
    onCyclePlaybackQueueMode: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalized = config.normalized()
    val visible = normalized.order.filter(normalized::isVisible)
    val hasLyrics = PlayerLowerComponent.LYRICS in visible
    val lyricsColors = rememberLyricsContentColors(autoContentColors, lyricsTextColorMode)
    val infoColors = when {
        playerPageTextColorMode != PlaybackContentColorMode.AUTO -> colors
        lowerBackground.usesBlurredArtwork -> hifiBadgeColors
        else -> colors
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val metrics = customPlayerLayoutMetrics(
            panelHeightDp = maxHeight.value,
            coverBaseHeightDp = coverBaseHeightDp,
            config = normalized,
            visible = visible,
        )
        val fitScale = metrics.fitScale
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(normalized.topPaddingDp.dp * fitScale))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(
                    space = normalized.spacingDp.dp * fitScale,
                    alignment = if (hasLyrics || PlayerLowerComponent.COVER in visible) {
                        Alignment.Top
                    } else {
                        Alignment.CenterVertically
                    },
                ),
            ) {
                visible.forEach { component ->
                    val scale = normalized.scalePercentOf(component) / 100f * fitScale
                    val itemHeightDp = customPlayerBaseHeightDp(
                        component = component,
                        lyricsLineCount = normalized.lyricsLineCount,
                        coverBaseHeightDp = coverBaseHeightDp,
                    ) * scale
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeightDp.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (component) {
                            PlayerLowerComponent.COVER -> coverContent(scale)

                            PlayerLowerComponent.INFO -> DirectionalTrackWipe(
                                targetState = activeSong,
                                contentKey = Song::id,
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            ) { visualSong ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (playerInfoVisibility.hasAnyEnabledSegment()) {
                                        HiFiBadgeSection(
                                            song = visualSong,
                                            colors = infoColors,
                                            playerInfoVisibility = playerInfoVisibility,
                                            playbackTuning = playbackTuning,
                                            hiResBadgeAppearance = hiResBadgeAppearance,
                                        )
                                    }
                                }
                            }

                            PlayerLowerComponent.TITLE -> DirectionalTrackWipe(
                                targetState = activeSong,
                                contentKey = Song::id,
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) { visualSong ->
                                SongTitleSection(
                                    title = SongTitleDisplay.displayTitle(
                                        visualSong.title,
                                        stripSongTitleParentheses,
                                    ),
                                    artist = visualSong.artist,
                                    album = visualSong.album,
                                    isBuffering = surfaceState.isBuffering,
                                    playbackError = surfaceState.playbackError,
                                    colors = colors,
                                    immersiveProgress = 0f,
                                    contentScale = scale,
                                )
                            }

                            PlayerLowerComponent.LYRICS -> DirectionalTrackWipe(
                                targetState = CustomLyricsVisual(
                                    activeSong,
                                    lyricsRenderState,
                                    surfaceState.isPlaying,
                                ),
                                contentKey = { it.song.id },
                                direction = trackSkipDirection,
                                motionEnabled = trackWipeMotionEnabled,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .fillMaxWidth(),
                            ) { visual ->
                                CustomLyricsBlock(
                                    renderState = visual.renderState,
                                    isPlaying = visual.isPlaying,
                                    colors = lyricsColors,
                                    bilingualDisplayMode = bilingualDisplayMode,
                                    contentScale = scale,
                                    lineSlots = normalized.lyricsLineCount,
                                    onOpenLyrics = onOpenLyrics,
                                )
                            }

                            PlayerLowerComponent.PROGRESS -> PlayerProgressBarSection(
                                seekState = seekState,
                                colors = colors,
                                spectrumEnabled = spectrumEnabled,
                                spectrumPlaying = surfaceState.isPlaying,
                                spectrumHeight = 56.dp * scale,
                                visualScale = scale,
                                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
                            )

                            PlayerLowerComponent.CONTROLS -> PlayerPlaybackControlsSection(
                                surfaceState = surfaceState,
                                colors = colors,
                                onCyclePlaybackQueueMode = onCyclePlaybackQueueMode,
                                onPrevious = onPrevious,
                                onTogglePlay = onTogglePlay,
                                onNext = onNext,
                                onOpenQueue = onOpenQueue,
                                visualScale = scale,
                                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(normalized.bottomPaddingDp.dp * fitScale))
        }
    }
}

internal data class CustomPlayerLayoutMetrics(
    val fitScale: Float,
    val coverVisualScale: Float,
    val coverTopDp: Float?,
)

internal fun customPlayerLayoutMetrics(
    panelHeightDp: Float,
    coverBaseHeightDp: Float,
    config: PlayerLowerLayoutConfig,
    visible: List<PlayerLowerComponent> = config.order.filter(config::isVisible),
): CustomPlayerLayoutMetrics {
    if (panelHeightDp <= 0f || visible.isEmpty()) {
        return CustomPlayerLayoutMetrics(fitScale = 1f, coverVisualScale = 1f, coverTopDp = null)
    }
    val normalized = config.normalized()
    val componentsHeight = visible.sumOf { component ->
        customPlayerBaseHeightDp(component, normalized.lyricsLineCount, coverBaseHeightDp).toDouble() *
            normalized.scalePercentOf(component) / 100.0
    }.toFloat()
    val gapsHeight = normalized.spacingDp * (visible.size - 1).coerceAtLeast(0)
    val desiredHeight = componentsHeight + gapsHeight + normalized.topPaddingDp + normalized.bottomPaddingDp
    val fitScale = if (desiredHeight <= panelHeightDp) {
        1f
    } else {
        (panelHeightDp / desiredHeight).coerceIn(0f, 1f)
    }
    var topDp = normalized.topPaddingDp * fitScale
    var coverTopDp: Float? = null
    visible.forEachIndexed { index, component ->
        if (component == PlayerLowerComponent.COVER) coverTopDp = topDp
        topDp += customPlayerBaseHeightDp(component, normalized.lyricsLineCount, coverBaseHeightDp) *
            normalized.scalePercentOf(component) / 100f * fitScale
        if (index < visible.lastIndex) topDp += normalized.spacingDp * fitScale
    }
    return CustomPlayerLayoutMetrics(
        fitScale = fitScale,
        coverVisualScale = normalized.scalePercentOf(PlayerLowerComponent.COVER) / 100f * fitScale,
        coverTopDp = coverTopDp,
    )
}

internal fun customPlayerBaseHeightDp(
    component: PlayerLowerComponent,
    lyricsLineCount: Int = PlayerLowerLayoutConfig.DEFAULT_LYRICS_LINE_COUNT,
    coverBaseHeightDp: Float = DefaultCustomCoverBaseHeightDp,
): Float = when (component) {
    PlayerLowerComponent.COVER -> coverBaseHeightDp.coerceAtLeast(0f)
    PlayerLowerComponent.INFO -> CustomPlayerInfoRowHeightDp
    PlayerLowerComponent.TITLE -> 72f
    PlayerLowerComponent.LYRICS -> if (
        lyricsLineCount == PlayerLowerLayoutConfig.SINGLE_LYRICS_LINE_COUNT
    ) 48f else 112f
    PlayerLowerComponent.PROGRESS -> 64f
    PlayerLowerComponent.CONTROLS -> 80f
}

private const val DefaultCustomCoverBaseHeightDp = 360f

@Composable
private fun CustomLyricsBlock(
    renderState: LyricsRenderState,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
    contentScale: Float,
    lineSlots: Int,
    onOpenLyrics: () -> Unit,
) {
    LyricsSection(
        renderState = renderState,
        isPlaying = isPlaying,
        colors = colors,
        lineSlots = lineSlots,
        onClick = onOpenLyrics,
        bilingualDisplayMode = bilingualDisplayMode,
        contentScale = contentScale,
        modifier = Modifier
            .fillMaxSize()
            .fillMaxWidth(),
    )
}
