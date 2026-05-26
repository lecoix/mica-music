package com.mica.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import com.mica.music.data.ArtistNames
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.measurePlayerCoverFitOriginal
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
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
    spectrumEnabled: Boolean,
    spectrumPlaying: Boolean,
    coverFlowModeEnabled: Boolean,
    coverFlowMode: PlayerCoverFlowMode,
    queue: List<Song>,
    currentIndex: Int,
    coverFlowProgress: Float,
    letterboxAlpha: Float,
    onCoverZoneStopChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    onToggleCoverFlow: (() -> Unit)?,
    onPlayQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val screenWidth = maxWidth
        val density = LocalDensity.current
        val miniHeaderHeight = statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm
        val coverSize = lerpDp(screenWidth, LyricsFocusMiniCoverSize, lyricsLayoutFocus)
        val coverStartPadding = lerpDp(0.dp, LyricsFocusCoverStartPadding, lyricsLayoutFocus)
        val coverTopPadding = lerpDp(0.dp, statusBarTop, lyricsLayoutFocus)
        val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
            lyricsLayoutFocus < 0.5f
        val motionEnabled = rememberMicaMotionEnabled()
        var previousSong by remember { mutableStateOf(activeSong) }
        var previousIndex by remember { mutableIntStateOf(currentIndex) }
        var swapFromSong by remember { mutableStateOf<Song?>(null) }
        var swapToSong by remember { mutableStateOf<Song?>(null) }
        var swapFromIndex by remember { mutableIntStateOf(currentIndex) }
        var swapToIndex by remember { mutableIntStateOf(currentIndex) }
        val swapProgress = remember { Animatable(1f) }
        val pendingSwap = coverFlowModeEnabled && previousSong.id != activeSong.id
        val swapActive = pendingSwap || (swapFromSong != null && swapToSong != null)
        val foldProgress = if (swapActive) 1f else coverFlowProgress.coerceIn(0f, 1f)
        val stageActive = foldProgress > 0.001f

        LaunchedEffect(coverFlowModeEnabled, activeSong.id) {
            if (coverFlowModeEnabled && previousSong.id != activeSong.id) {
                swapFromSong = previousSong
                swapToSong = activeSong
                swapFromIndex = previousIndex
                swapToIndex = currentIndex
                swapProgress.snapTo(0f)
                val swapDuration = if (coverFlowMode == PlayerCoverFlowMode.RETRO_3D) {
                    MicaMotion.DurationLongMs
                } else {
                    MicaMotion.DurationMediumMs
                }
                swapProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = MicaMotion.tweenFloat(motionEnabled, swapDuration),
                )
                swapFromSong = null
                swapToSong = null
                swapFromIndex = currentIndex
                swapToIndex = currentIndex
            }
            previousSong = activeSong
            previousIndex = currentIndex
        }

        var coverAspectRatio by remember { mutableFloatStateOf(1f) }
        val coverDisplayMode = LocalCoverDisplayMode.current
        val effectiveCoverDisplayMode = if (coverFlowModeEnabled) {
            CoverDisplayMode.CROP_FILL
        } else {
            coverDisplayMode
        }
        val fitOriginal = effectiveCoverDisplayMode == CoverDisplayMode.FIT_ORIGINAL
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

        CompositionLocalProvider(LocalCoverDisplayMode provides effectiveCoverDisplayMode) {
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
                        .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onToggleCoverFlow)),
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(1f),
                    ) {
                        if (coverFlowModeEnabled && stageActive) {
                            val transitionFrom = if (pendingSwap) previousIndex else swapFromIndex
                            val transitionTo = if (pendingSwap) currentIndex else swapToIndex
                            val progress = if (pendingSwap && swapToSong?.id != activeSong.id) {
                                0f
                            } else {
                                swapProgress.value
                            }
                            CoverFlowStage(
                                queue = queue,
                                virtualCenterIndex = transitionFrom + (transitionTo - transitionFrom) * progress,
                                coverColor = coverColor,
                                coverWidth = coverWidth,
                                coverHeight = coverHeight,
                                screenWidthPx = with(density) { screenWidth.toPx() },
                                foldProgress = foldProgress,
                                coverFlowMode = coverFlowMode,
                                activeSongId = activeSong.id,
                                letterboxAlpha = letterboxAlpha,
                                onAspectRatioChanged = { coverAspectRatio = it },
                                onPlayQueueIndex = onPlayQueueIndex,
                            )
                        } else {
                            SongCover(
                                albumArtUri = activeSong.albumArtUri,
                                fallbackColor = coverColor,
                                contentDescription = activeSong.album,
                                modifier = Modifier.matchParentSize(),
                                letterboxAlpha = letterboxAlpha,
                                crossfadeMillis = 0,
                                onAspectRatioChanged = { coverAspectRatio = it },
                            )
                        }
                        if (!stageActive && coverEdgeFade) {
                            artworkEdgeFadeStops(artworkJunction)?.let { stops ->
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(Brush.verticalGradient(colorStops = stops)),
                                )
                            }
                        }
                        if (!stageActive && useCoverEdgeProgress) {
                            val coverEdgeProgressAlpha = 1f - lyricsChromeFade
                            if (coverEdgeProgressAlpha > 0.01f) {
                                LivePlayerSpectrumStrip(
                                    enabled = spectrumEnabled,
                                    isPlaying = spectrumPlaying,
                                    colors = contentColors,
                                    height = 72.dp,
                                    alpha = coverEdgeProgressAlpha,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
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
}

private fun coverClickModifier(
    lyricsExpanded: Boolean,
    onCloseLyrics: () -> Unit,
    onToggleCoverFlow: (() -> Unit)?,
): Modifier = when {
    lyricsExpanded -> Modifier.clickable(onClick = onCloseLyrics)
    onToggleCoverFlow != null -> Modifier.clickable(onClick = onToggleCoverFlow)
    else -> Modifier
}

@Composable
private fun BoxScope.CoverFlowStage(
    queue: List<Song>,
    virtualCenterIndex: Float,
    coverColor: Color,
    coverWidth: Dp,
    coverHeight: Dp,
    screenWidthPx: Float,
    foldProgress: Float,
    coverFlowMode: PlayerCoverFlowMode,
    activeSongId: String,
    letterboxAlpha: Float,
    onAspectRatioChanged: (Float) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
) {
    val start = floor(virtualCenterIndex - 2f).toInt()
    val end = ceil(virtualCenterIndex + 2f).toInt()
    val maxDistance = if (coverFlowMode == PlayerCoverFlowMode.RETRO_3D) 2.35f else 2.15f
    (start..end).forEach { index ->
        val song = queue.getOrNull(index) ?: return@forEach
        val offset = index - virtualCenterIndex
        val distance = abs(offset)
        if (distance > maxDistance) return@forEach
        val centerScale = coverFlowCenterScale(coverFlowMode, foldProgress)
        val slotScale = coverFlowSlotScale(distance, centerScale, coverFlowMode)
        val slotAlpha = coverFlowSlotAlpha(distance, foldProgress, coverFlowMode)
        val slotTranslation = coverFlowSlotTranslation(offset, screenWidthPx, coverFlowMode)
        val slotRotationY = coverFlowSlotRotationY(offset, coverFlowMode)
        val transformOrigin = if (offset < -0.01f) {
            TransformOrigin(1f, 0.5f)
        } else if (offset > 0.01f) {
            TransformOrigin(0f, 0.5f)
        } else {
            TransformOrigin.Center
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(coverWidth, coverHeight)
                .zIndex(coverFlowSlotZIndex(distance, coverFlowMode))
                .graphicsLayer {
                    alpha = slotAlpha
                    translationX = slotTranslation
                    rotationY = slotRotationY
                    scaleX = slotScale
                    scaleY = slotScale
                    cameraDistance = 18f * density
                    this.transformOrigin = transformOrigin
                }
                .then(
                    if (distance > 0.08f) {
                        Modifier.clickable { onPlayQueueIndex(index) }
                    } else {
                        Modifier
                    },
                ),
        ) {
            ParallelCoverWithReflection(
                song = song,
                coverColor = coverColor,
                activeSongId = activeSongId,
                letterboxAlpha = letterboxAlpha,
                onAspectRatioChanged = onAspectRatioChanged,
            )
        }
    }
}

@Composable
private fun ParallelCoverWithReflection(
    song: Song,
    coverColor: Color,
    activeSongId: String,
    letterboxAlpha: Float,
    onAspectRatioChanged: (Float) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SongCover(
            albumArtUri = song.albumArtUri,
            fallbackColor = coverColor,
            contentDescription = song.album,
            modifier = Modifier.matchParentSize(),
            letterboxAlpha = letterboxAlpha,
            crossfadeMillis = 0,
            onAspectRatioChanged = if (song.id == activeSongId) onAspectRatioChanged else null,
        )
        BoxWithConstraints(
            Modifier.matchParentSize(),
        ) {
            val fullWidth = maxWidth
            val fullHeight = maxHeight
            val reflectionHeight = maxHeight * 0.28f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(reflectionHeight)
                    .offset(y = fullHeight + HifiSpacing.sm)
                    .graphicsLayer {
                        alpha = 0.24f
                        clip = true
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White,
                                0.45f to Color.White.copy(alpha = 0.55f),
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Layout(
                    modifier = Modifier.matchParentSize(),
                    content = {
                        SongCover(
                            albumArtUri = song.albumArtUri,
                            fallbackColor = coverColor,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer {
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                scaleY = -1f
                            },
                            letterboxAlpha = 0f,
                            crossfadeMillis = 0,
                        )
                    },
                ) { measurables, constraints ->
                    val coverWidthPx = fullWidth.roundToPx()
                    val coverHeightPx = fullHeight.roundToPx()
                    val placeable = measurables.first().measure(
                        Constraints.fixed(coverWidthPx, coverHeightPx),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeRelative(0, 0)
                    }
                }
            }
        }
    }
}

private fun coverFlowCenterScale(mode: PlayerCoverFlowMode, foldProgress: Float): Float {
    return when (mode) {
        PlayerCoverFlowMode.RETRO_3D -> 1f - 0.38f * foldProgress
        PlayerCoverFlowMode.PAUSE_FOLD -> 1f - 0.24f * foldProgress
        PlayerCoverFlowMode.STANDARD -> 1f
    }
}

private fun coverFlowSlotScale(
    distance: Float,
    centerScale: Float,
    mode: PlayerCoverFlowMode,
): Float {
    if (mode != PlayerCoverFlowMode.RETRO_3D) return centerScale
    val d = distance.coerceIn(0f, 2f)
    return if (d <= 1f) {
        centerScale + (0.52f - centerScale) * d
    } else {
        0.52f + (0.44f - 0.52f) * (d - 1f)
    }
}

private fun coverFlowSlotAlpha(
    distance: Float,
    foldProgress: Float,
    mode: PlayerCoverFlowMode,
): Float {
    val d = distance.coerceIn(0f, 2f)
    val farAlpha = if (mode == PlayerCoverFlowMode.RETRO_3D) 1f else 0.48f
    val alpha = when {
        d <= 1f -> 1f
        else -> 1f + (farAlpha - 1f) * (d - 1f)
    }
    return if (d < 0.05f) alpha else alpha * foldProgress
}

private fun coverFlowSlotTranslation(
    offset: Float,
    screenWidthPx: Float,
    mode: PlayerCoverFlowMode,
): Float {
    val distance = abs(offset)
    if (distance < 0.001f) return 0f
    val sign = if (offset < 0f) -1f else 1f
    if (mode != PlayerCoverFlowMode.RETRO_3D) {
        val step = 0.92f
        val fraction = if (distance <= 1f) {
            step * distance
        } else {
            step + step * (distance - 1f)
        }
        return sign * screenWidthPx * fraction
    }
    val d = distance.coerceIn(0f, 2f)
    val fraction = if (d <= 1f) {
        0.81f * d
    } else {
        0.81f + (0.90f - 0.81f) * (d - 1f)
    }
    return sign * screenWidthPx * fraction
}

private fun coverFlowSlotZIndex(distance: Float, mode: PlayerCoverFlowMode): Float {
    if (mode != PlayerCoverFlowMode.RETRO_3D) return 10f - distance
    return when {
        distance < 0.05f -> 30f
        distance <= 1.05f -> 20f
        else -> 10f - distance
    }
}

private fun coverFlowSlotRotationY(offset: Float, mode: PlayerCoverFlowMode): Float {
    if (mode != PlayerCoverFlowMode.RETRO_3D) return 0f
    val distance = abs(offset)
    if (distance < 0.001f) return 0f
    val turn = distance.coerceIn(0f, 1f)
    val easedTurn = turn * turn * (3f - 2f * turn)
    val sign = if (offset < 0f) 1f else -1f
    return sign * 75f * easedTurn
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
