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
import androidx.compose.runtime.remember
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
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.rememberCoverGestureState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.CoverFlowCarouselHost
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHaloFraction
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHost
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops
import com.mica.music.util.TrackSwitchPerformance

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
    particleCoverTuning: ParticleCoverTuning,
    lyricsExpanded: Boolean,
    coverContentAlpha: Float,
    onCoverBoundsChanged: (Rect?) -> Unit,
    onCoverAspectRatioChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
    onCoverMotionActiveChanged: (Boolean) -> Unit,
    coverFlowNavigation: CoverFlowCarouselNavigationBridge,
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
    val particleCoverActive = coverFlowMode.usesParticleCover && frame.lyricsProgress < 0.01f
    val coverDecodeTarget = remember(screenWidthPx, coverWidthPx, coverFlowMode) {
        val targetPx = if (coverFlowMode.usesParticleCover) {
            coverWidthPx.coerceAtLeast(1f)
        } else {
            screenWidthPx
        }
        CoverDecodeTarget.forSpecialTheme(targetPx)
    }
    val reflectionGapPx = with(density) { HifiSpacing.sm.toPx() }
    val reflectionExtraDp =
        cover.height * CoverFlowMath.ReflectionHeightFraction + HifiSpacing.sm + 4.dp
    val coverFlowReflection = frame.coverFlowStageActive &&
        (coverFlowMode == PlayerCoverFlowMode.PAUSE_FOLD ||
            coverFlowMode == PlayerCoverFlowMode.RETRO_3D)
    val coverBoxHeight = if (coverFlowReflection) {
        cover.height + reflectionExtraDp
    } else {
        cover.height
    }
    val cameraDistancePx = with(density) { 18.dp.toPx() }

    val standardMode = !coverFlowMode.usesCoverFlowStage && !frame.coverFlowStageActive
    val gestureState = rememberCoverGestureState(
        gesturesEnabled = frame.gesturesEnabled,
        standardMode = standardMode,
        screenWidthPx = screenWidthPx,
        onPrevious = onPrevious,
        onNext = onNext,
    )

    LaunchedEffect(song.id, standardMode, frame.coverFlowStageActive) {
        if (frame.coverFlowStageActive) return@LaunchedEffect
        TrackSwitchPerformance.mark(
            "standard-cover-transition",
            "song=${song.id.takeLast(12)} swipeMode=$standardMode",
        )
    }

    LaunchedEffect(frame.coverFlowStageActive, currentIndex, queue, coverDecodeTarget) {
        if (!frame.coverFlowStageActive) return@LaunchedEffect
        for (offset in listOf(-1, 1)) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
            MicaImageLoaders.preloadCover(context, uri, coverDecodeTarget)
            if (lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW) {
                MicaImageLoaders.preloadBackground(context, uri)
            }
        }
    }

    val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
        frame.lyricsProgress < 0.5f
    val effectiveCoverDisplayMode = if (coverFlowMode.forcesSquareCrop) {
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
                    coverDecodeTarget = coverDecodeTarget,
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
                    onMotionActiveChanged = onCoverMotionActiveChanged,
                    navigationBridge = coverFlowNavigation,
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
                if (frame.lower.coverEdgeOnPlaySurface) {
                    val centerScale = CoverFlowMath.centerScale(
                        mode = coverFlowMode,
                        foldProgress = frame.coverFlowProgress,
                    )
                    val centerCoverWidth = cover.width * centerScale
                    val centerCoverHeight = cover.height * centerScale
                    CoverEdgePlaybackOverlay(
                        seekState = seekState,
                        spectrumEnabled = frame.spectrumEnabled,
                        isPlaying = isPlaying,
                        contentColors = contentColors,
                        alpha = (1f - frame.lower.chromeProgressAlpha) * coverContentAlpha,
                        reflectionHeight =
                            centerCoverHeight * CoverFlowMath.ReflectionHeightFraction,
                        reflectionGap = HifiSpacing.sm * centerScale,
                        modifier = Modifier
                            .padding(
                                start = cover.startPadding + (cover.width - centerCoverWidth) / 2,
                                top = cover.topPadding + (cover.height - centerCoverHeight) / 2,
                            )
                            .size(centerCoverWidth, centerCoverHeight)
                            .zIndex(2f),
                    )
                }
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
                    .graphicsLayer { clip = !coverFlowReflection && !particleCoverActive }
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
                            clip = !coverFlowReflection && !particleCoverActive
                            if (standardMode && !frame.coverFlowStageActive) {
                                translationX = gestureState.standardSwipeOffsetFraction *
                                    size.width * 0.35f
                            }
                        }
                        .zIndex(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (particleCoverActive) {
                        val halo = cover.width * ThreeParticleCoverHaloFraction
                        ThreeParticleCoverHost(
                            song = song,
                            coverDecodeTarget = coverDecodeTarget,
                            motionEnabled = motionEnabled,
                            coverColor = coverColor,
                            tuning = particleCoverTuning,
                            onAspectRatioChanged = onCoverAspectRatioChanged,
                            onMotionActiveChanged = onCoverMotionActiveChanged,
                            modifier = Modifier
                                .size(cover.width + halo * 2f, cover.height + halo * 2f),
                        )
                    } else {
                        AnimatedContent(
                            targetState = song,
                            transitionSpec = {
                                fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs)) togetherWith
                                    fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs))
                            },
                            label = "standardCover",
                        ) { animatedSong ->
                            SongCover(
                                albumArtUri = animatedSong.albumArtUri,
                                fallbackColor = coverColor,
                                contentDescription = animatedSong.album,
                                modifier = Modifier.matchParentSize(),
                                letterboxAlpha = cover.letterboxAlpha,
                                crossfadeMillis = if (motionEnabled) 200 else 0,
                                onAspectRatioChanged = onCoverAspectRatioChanged,
                                decodeTarget = coverDecodeTarget.takeIf {
                                    coverFlowMode.forcesSquareCrop
                                },
                            )
                        }
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
                        val coverEdgeProgressAlpha = 1f - frame.lower.chromeProgressAlpha
                        CoverEdgePlaybackOverlay(
                            seekState = seekState,
                            spectrumEnabled = frame.spectrumEnabled,
                            isPlaying = isPlaying,
                            contentColors = contentColors,
                            alpha = coverEdgeProgressAlpha,
                            modifier = Modifier.matchParentSize(),
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

@Composable
private fun CoverEdgePlaybackOverlay(
    seekState: PlaybackSeekState,
    spectrumEnabled: Boolean,
    isPlaying: Boolean,
    contentColors: PlayerContentColors,
    alpha: Float,
    reflectionHeight: Dp = 0.dp,
    reflectionGap: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val overlayAlpha = alpha.coerceIn(0f, 1f)
    if (overlayAlpha <= 0.01f) return
    val progressReflectionHeight = minOf(reflectionHeight, 12.dp)
    Box(modifier = modifier) {
        LivePlayerSpectrumStrip(
            enabled = spectrumEnabled,
            isPlaying = isPlaying,
            colors = contentColors,
            height = 72.dp,
            alpha = overlayAlpha,
            reflectionHeight = reflectionHeight,
            reflectionGap = reflectionGap,
            reflectionAlpha = CoverFlowMath.ReflectionAlpha,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        CoverEdgeProgressBar(
            value = seekState.sliderValue,
            onValueChange = seekState.onValueChange,
            onValueChangeFinished = seekState.onValueChangeFinished,
            valueRange = seekState.valueRange,
            progressColor = contentColors.primary,
            reflectionHeight = progressReflectionHeight,
            reflectionGap = reflectionGap,
            reflectionAlpha = CoverFlowMath.ReflectionAlpha,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { this.alpha = overlayAlpha },
        )
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
