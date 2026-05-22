package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.data.ArtistNames
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.measurePlayerCoverFitOriginal
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops

private val LyricsFocusMiniCoverSize = 56.dp * 0.95f
private val LyricsFocusCoverStartPadding = HifiSpacing.lg + HifiSpacing.sm

@Composable
internal fun NowPlayingCoverSection(
    activeSong: Song,
    coverColor: Color,
    contentColors: PlayerContentColors,
    lowerBackground: PlayerLowerBackgroundMode,
    artworkJunction: Color,
    statusBarTop: Dp,
    screenHeight: Dp,
    lyricsExpanded: Boolean,
    lyricsLayoutFocus: Float,
    lyricsChromeFade: Float,
    useCoverEdgeProgress: Boolean,
    seekState: PlaybackSeekState,
    letterboxAlpha: Float,
    onCoverZoneStopChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val screenWidth = maxWidth
        val miniHeaderHeight = statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm
        val coverSize = lerpDp(screenWidth, LyricsFocusMiniCoverSize, lyricsLayoutFocus)
        val coverStartPadding = lerpDp(0.dp, LyricsFocusCoverStartPadding, lyricsLayoutFocus)
        val coverTopPadding = lerpDp(0.dp, statusBarTop, lyricsLayoutFocus)
        val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
            lyricsLayoutFocus < 0.5f

        var coverAspectRatio by remember(activeSong.albumArtUri) { mutableFloatStateOf(1f) }
        val fitOriginal = LocalCoverDisplayMode.current == CoverDisplayMode.FIT_ORIGINAL
        val (coverWidth, coverHeight) = if (fitOriginal) {
            val (intrinsicW, intrinsicH) = measurePlayerCoverFitOriginal(
                coverAspectRatio,
                screenWidth,
                screenHeight,
            )
            lerpDp(intrinsicW, LyricsFocusMiniCoverSize, lyricsLayoutFocus) to
                lerpDp(intrinsicH, LyricsFocusMiniCoverSize, lyricsLayoutFocus)
        } else {
            coverSize to coverSize
        }
        val coverBlockHeight = lerpDp(
            coverHeight + coverTopPadding,
            miniHeaderHeight,
            lyricsLayoutFocus,
        )
        SideEffect {
            onCoverZoneStopChanged(
                (coverBlockHeight.value / screenHeight.value)
                    .coerceIn(0.12f, PlayerCoverMaxScreenFraction),
            )
        }

        Box(
            Modifier
                .height(coverBlockHeight)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = coverStartPadding, top = coverTopPadding)
                    .size(coverWidth, coverHeight)
                    .then(
                        if (lyricsExpanded) {
                            Modifier.clickable(onClick = onCloseLyrics)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                SongCover(
                    albumArtUri = activeSong.albumArtUri,
                    fallbackColor = coverColor,
                    contentDescription = activeSong.album,
                    modifier = Modifier.matchParentSize(),
                    letterboxAlpha = letterboxAlpha,
                    crossfadeMillis = 0,
                    onAspectRatioChanged = { coverAspectRatio = it },
                )
                if (coverEdgeFade) {
                    artworkEdgeFadeStops(artworkJunction)?.let { stops ->
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Brush.verticalGradient(colorStops = stops)),
                        )
                    }
                }
                if (useCoverEdgeProgress) {
                    val coverEdgeProgressAlpha = 1f - lyricsChromeFade
                    if (coverEdgeProgressAlpha > 0.01f) {
                        CoverEdgeProgressBar(
                            value = seekState.sliderValue,
                            onValueChange = seekState.onValueChange,
                            onValueChangeFinished = seekState.onValueChangeFinished,
                            valueRange = seekState.valueRange,
                            progressColor = contentColors.primary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .graphicsLayer { alpha = coverEdgeProgressAlpha },
                        )
                    }
                }
            }
            if (lyricsLayoutFocus > 0.01f) {
                LyricsFocusHeaderOverlay(
                    title = activeSong.title,
                    artist = activeSong.artist,
                    coverWidth = coverWidth,
                    coverHeight = coverHeight,
                    coverStartPadding = coverStartPadding,
                    coverTopPadding = coverTopPadding,
                    colors = contentColors,
                    focusAlpha = lyricsLayoutFocus,
                    onCloseLyrics = onCloseLyrics,
                )
            }
        }
    }
}

@Composable
private fun LyricsFocusHeaderOverlay(
    title: String,
    artist: String,
    coverWidth: Dp,
    coverHeight: Dp,
    coverStartPadding: Dp,
    coverTopPadding: Dp,
    colors: PlayerContentColors,
    focusAlpha: Float,
    onCloseLyrics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = coverTopPadding,
                start = coverStartPadding,
                end = HifiSpacing.lg,
            )
            .graphicsLayer { alpha = focusAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(coverWidth, coverHeight))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = HifiSpacing.md)
                .height(maxOf(coverHeight, HifiSize.touchTarget)),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MicaTheme.typography.titleSm,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ArtistNames.normalizeDisplay(artist),
                style = MicaTheme.typography.bodySm,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onCloseLyrics,
            modifier = Modifier.size(HifiSize.touchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Collapse lyrics",
                tint = colors.primary,
            )
        }
    }
}
