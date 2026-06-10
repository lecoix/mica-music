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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.rememberCoverGestureState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselHost
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
    val coverWidthPx = with(density) { cover.width.toPx() }
    val coverHeightPx = with(density) { cover.height.toPx() }
    val coverStartPaddingPx = with(density) { cover.startPadding.toPx() }
    val reflectionGapPx = with(density) { HifiSpacing.sm.toPx() }
    val reflectionExtraDp = cover.height * 0.28f + HifiSpacing.sm + 4.dp
    val coverFlowReflection = frame.coverFlowStageActive &&
        (coverFlowMode == PlayerCoverFlowMode.PAUSE_FOLD ||
            coverFlowMode == PlayerCoverFlowMode.RETRO_3D)
    val coverBoxHeight = if (coverFlowReflection) {
        cover.height + reflectionExtraDp
    } else {
        cover.height
    }
    val cameraDistancePx = with(density) { 18.dp.toPx() }

    val standardMode = coverFlowMode == PlayerCoverFlowMode.STANDARD && !frame.coverFlowStageActive

    val gestureState = rememberCoverGestureState(
        gesturesEnabled = frame.gesturesEnabled,
        standardMode = standardMode,
        screenWidthPx = screenWidthPx,
        onPrevious = onPrevious,
        onNext = onNext,
    )

    LaunchedEffect(frame.coverFlowStageActive, currentIndex, queue) {
        if (!frame.coverFlowStageActive) return@LaunchedEffect
        for (offset in -3..3) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
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
                .fillMaxWidth()
                .then(
                    if (coverFlowReflection) {
                        // 倒影在布局高度外绘制，不占下半区纵向空间
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { clip = false }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopStart,
        ) {
            if (frame.coverFlowStageActive) {
                CoverFlowCarouselHost(
                    queue = queue,
                    currentIndex = currentIndex,
                    coverFlowMode = coverFlowMode,
                    foldProgress = frame.coverFlowProgress,
                    screenWidthPx = screenWidthPx,
                    coverWidthPx = coverWidthPx,
                    coverHeightPx = coverHeightPx,
                    coverStartPaddingPx = coverStartPaddingPx,
                    reflectionGapPx = reflectionGapPx,
                    cameraDistancePx = cameraDistancePx,
                    motionEnabled = motionEnabled,
                    coverColor = coverColor,
                    stageActive = frame.coverFlowStageActive,
                    gesturesEnabled = frame.gesturesEnabled,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCoverLongPress = onCoverLongPress,
                    onAspectRatioChanged = onCoverAspectRatioChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(coverBoxHeight)
                        .padding(top = cover.topPadding)
                        .graphicsLayer {
                            alpha = coverContentAlpha
                            clip = false
                        }
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInRoot()
                            onCoverBoundsChanged(
                                Rect(
                                    left = b.left + coverStartPaddingPx,
                                    top = b.top,
                                    right = b.left + coverStartPaddingPx + coverWidthPx,
                                    bottom = b.top + coverHeightPx,
                                ),
                            )
                        },
                )
                if (lyricsExpanded) {
                    Box(
                        modifier = Modifier
                            .padding(start = cover.startPadding, top = cover.topPadding)
                            .size(cover.width, cover.height)
                            .zIndex(2f)
                            .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onCoverLongPress)),
                    )
                }
            }
            if (!frame.coverFlowStageActive) {
            Box(
                modifier = Modifier
                    .padding(start = cover.startPadding, top = cover.topPadding)
                    .size(cover.width, coverBoxHeight)
                    .graphicsLayer { clip = !coverFlowReflection }
                    .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
                    .pointerInput(frame.gesturesEnabled, frame.coverFlowStageActive) {
                        if (frame.gesturesEnabled && !frame.coverFlowStageActive) {
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
                            clip = !coverFlowReflection
                            if (standardMode && !frame.coverFlowStageActive) {
                                translationX = gestureState.standardSwipeOffsetFraction *
                                    size.width * 0.35f
                            }
                        }
                        .zIndex(1f),
                ) {
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
                    if (coverEdgeFade) {
                        artworkEdgeFadeStops(artworkJunction)?.let { stops ->
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(Brush.verticalGradient(colorStops = stops)),
                            )
                        }
                    }
                    if (frame.lower.coverEdgeOnPlaySurface) {
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
