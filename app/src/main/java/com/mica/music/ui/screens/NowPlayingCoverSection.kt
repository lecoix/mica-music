package com.mica.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import com.mica.music.data.ArtistNames
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.CoverLaneBinding
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.rememberCoverGestureState
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingCoverSection(
    song: Song,
    queue: List<Song>,
    currentIndex: Int,
    frame: PlayerPageFrame,
    coverColor: Color,
    contentColors: PlayerContentColors,
    lowerBackground: PlayerLowerBackgroundMode,
    artworkJunction: Color,
    seekState: PlaybackSeekState,
    isPlaying: Boolean,
    coverFlowMode: PlayerCoverFlowMode,
    lyricsExpanded: Boolean,
    coverContentAlpha: Float,
    onCoverBoundsChanged: (Rect?) -> Unit,
    onCoverAspectRatioChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
    screenWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val cover = frame.cover
    val motionEnabled = rememberMicaMotionEnabled()
    val density = LocalDensity.current
    val context = LocalContext.current
    val screenWidthPx = with(density) { screenWidth.coerceAtLeast(1.dp).toPx() }

    val standardMode = coverFlowMode == PlayerCoverFlowMode.STANDARD && !frame.coverFlowStageActive

    val gestureState = rememberCoverGestureState(
        queue = queue,
        currentIndex = currentIndex,
        coverFlowStageActive = frame.coverFlowStageActive,
        coverFlowMode = coverFlowMode,
        gesturesEnabled = frame.gesturesEnabled,
        standardMode = standardMode,
        screenWidthPx = screenWidthPx,
        motionEnabled = motionEnabled,
        onPlayQueueIndex = onPlayQueueIndex,
        onPrevious = onPrevious,
        onNext = onNext,
    )

    LaunchedEffect(gestureState.centerAnchorIndex, queue) {
        for (binding in gestureState.laneBindings) {
            val uri = binding.song?.albumArtUri ?: continue
            MicaImageLoaders.ensureCoverCached(context, uri)
            if (lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW) {
                MicaImageLoaders.ensureBackgroundCached(context, uri)
            }
        }
    }

    val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
        frame.lyricsProgress < 0.5f
    val effectiveCoverDisplayMode = if (coverFlowMode != PlayerCoverFlowMode.STANDARD) {
        CoverDisplayMode.CROP_FILL
    } else {
        LocalCoverDisplayMode.current
    }

    CompositionLocalProvider(LocalCoverDisplayMode provides effectiveCoverDisplayMode) {
        Box(
            modifier
                .height(cover.blockHeight)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = cover.startPadding, top = cover.topPadding)
                    .size(cover.width, cover.height)
                    .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
                    .pointerInput(frame.gesturesEnabled) {
                        if (frame.gesturesEnabled) {
                            detectHorizontalDragGestures(
                                onDragStart = { gestureState.handlers.onDragStart() },
                                onDragEnd = { gestureState.handlers.onDragEnd() },
                                onHorizontalDrag = { _, dragAmount ->
                                    gestureState.handlers.onHorizontalDrag(dragAmount)
                                },
                            )
                        }
                    }
                    .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onCoverLongPress)),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = coverContentAlpha
                            if (standardMode && !frame.coverFlowStageActive) {
                                translationX = gestureState.standardSwipeOffsetFraction *
                                    size.width * 0.35f
                            }
                        }
                        .zIndex(1f),
                ) {
                    if (frame.coverFlowStageActive) {
                        CoverFlowLaneStage(
                            bindings = gestureState.laneBindings,
                            virtualCenterIndex = gestureState.virtualCenterIndex,
                            coverColor = coverColor,
                            coverWidth = cover.width,
                            coverHeight = cover.height,
                            screenWidthPx = screenWidthPx,
                            foldProgress = frame.coverFlowProgress,
                            coverFlowMode = coverFlowMode,
                    letterboxAlpha = cover.letterboxAlpha,
                            onAspectRatioChanged = onCoverAspectRatioChanged,
                            onPlayQueueIndex = onPlayQueueIndex,
                            onCoverLongPress = onCoverLongPress,
                        )
                    } else {
                        AnimatedContent(
                            targetState = song.id,
                            transitionSpec = {
                                fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)) togetherWith
                                    fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs))
                            },
                            label = "standardCover",
                        ) { _ ->
                            SongCover(
                                albumArtUri = song.albumArtUri,
                                fallbackColor = coverColor,
                                contentDescription = song.album,
                                modifier = Modifier.matchParentSize(),
                                letterboxAlpha = cover.letterboxAlpha,
                                crossfadeMillis = if (motionEnabled) 200 else 0,
                                onAspectRatioChanged = onCoverAspectRatioChanged,
                            )
                        }
                    }
                    if (!frame.coverFlowStageActive && coverEdgeFade) {
                        artworkEdgeFadeStops(artworkJunction)?.let { stops ->
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(Brush.verticalGradient(colorStops = stops)),
                            )
                        }
                    }
                    if (!frame.coverFlowStageActive && frame.lower.coverEdgeOnPlaySurface) {
                        val coverEdgeProgressAlpha = 1f - frame.lower.lyricsChromeFade
                        if (coverEdgeProgressAlpha > 0.01f) {
                            LivePlayerSpectrumStrip(
                                enabled = frame.spectrumEnabled,
                                isPlaying = isPlaying,
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
            if (frame.lyricsProgress > 0.01f) {
                LyricsFocusHeaderOverlay(
                    title = song.title,
                    artist = song.artist,
                    coverWidth = cover.width,
                    coverHeight = cover.height,
                    coverStartPadding = cover.startPadding,
                    coverTopPadding = cover.topPadding,
                    colors = contentColors,
                    focusAlpha = frame.lyricsProgress,
                    onCloseLyrics = onCloseLyrics,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun coverClickModifier(
    lyricsExpanded: Boolean,
    onCloseLyrics: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
): Modifier = when {
    lyricsExpanded && onCoverLongPress != null ->
        Modifier.combinedClickable(
            onClick = onCloseLyrics,
            onLongClick = onCoverLongPress,
        )
    lyricsExpanded -> Modifier.clickable(onClick = onCloseLyrics)
    onCoverLongPress != null ->
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = onCoverLongPress,
        )
    else -> Modifier
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.CoverFlowLaneStage(
    bindings: List<CoverLaneBinding>,
    virtualCenterIndex: Float,
    coverColor: Color,
    coverWidth: Dp,
    coverHeight: Dp,
    screenWidthPx: Float,
    foldProgress: Float,
    coverFlowMode: PlayerCoverFlowMode,
    letterboxAlpha: Float,
    onAspectRatioChanged: (Float) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onCoverLongPress: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val maxDistance = CoverFlowMath.MaxViewDistance

    for (binding in bindings) {
        val laneOffset = binding.laneOffset
        val song = binding.song
        val queueIndex = binding.queueIndex
        if (song == null) continue
        key("cover_lane_$laneOffset") {
            val offset = queueIndex - virtualCenterIndex
            val distance = abs(offset)
            val withinView = distance <= maxDistance
            val centerScale = CoverFlowMath.centerScale(coverFlowMode, foldProgress)
            val slotScale = CoverFlowMath.slotScale(distance, centerScale, coverFlowMode)
            val slotAlpha = if (withinView) {
                CoverFlowMath.slotAlpha(distance, foldProgress, coverFlowMode)
            } else {
                0f
            }
            val slotTranslation = CoverFlowMath.slotTranslation(
                offset = offset,
                screenWidthPx = screenWidthPx,
                mode = coverFlowMode,
            )
            val slotRotationY = CoverFlowMath.slotRotationY(offset, coverFlowMode)
            val transformOrigin = when {
                distance < 0.08f -> TransformOrigin.Center
                offset < 0f -> TransformOrigin(1f, 0.5f)
                else -> TransformOrigin(0f, 0.5f)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(coverWidth, coverHeight)
                    .zIndex(CoverFlowMath.slotZIndex(distance, coverFlowMode))
                    .graphicsLayer {
                        alpha = slotAlpha
                        translationX = slotTranslation
                        rotationY = slotRotationY
                        scaleX = slotScale
                        scaleY = slotScale
                        cameraDistance = 18f * density.density
                        this.transformOrigin = transformOrigin
                    }
                    .then(
                        when {
                            withinView && distance > 0.08f ->
                                Modifier.clickable { onPlayQueueIndex(queueIndex) }
                            withinView && distance < 0.08f && onCoverLongPress != null ->
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = onCoverLongPress,
                                )
                            else -> Modifier
                        },
                    ),
            ) {
                ParallelCoverWithReflection(
                    song = song,
                    coverColor = coverColor,
                    letterboxAlpha = letterboxAlpha,
                    onAspectRatioChanged = if (distance < 0.08f) onAspectRatioChanged else null,
                )
            }
        }
    }
}

@Composable
private fun ParallelCoverWithReflection(
    song: Song,
    coverColor: Color,
    letterboxAlpha: Float,
    onAspectRatioChanged: ((Float) -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        SongCover(
            albumArtUri = song.albumArtUri,
            fallbackColor = coverColor,
            contentDescription = song.album,
            modifier = Modifier.matchParentSize(),
            letterboxAlpha = letterboxAlpha,
            crossfadeMillis = 0,
            stableMemoryCacheKey = song.albumArtUri,
            onAspectRatioChanged = onAspectRatioChanged,
        )
        BoxWithConstraints(Modifier.matchParentSize()) {
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
                            stableMemoryCacheKey = song.albumArtUri,
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
