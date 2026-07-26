package com.mica.music.ui.screens

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import com.mica.music.data.SongTitleDisplay
import com.mica.music.data.TrackSkipDirection
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.trackWipeLayer
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.resolveCoverAspectRatioFromUri
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.ImmersiveProgressEpsilon
import com.mica.music.ui.screens.player.pinnedVideoCover
import com.mica.music.ui.screens.player.ParticleCoverThemePolicy
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.UseNativeParticleCoverInPlayer
import com.mica.music.ui.screens.player.rememberCoverGestureState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.CoverFlowCarouselHost
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHaloFraction
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHost
import com.mica.music.ui.screens.player.view.VideoAlbumCoverHost
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    videoAlbumCoverEnabled: Boolean,
    trackSkipDirection: TrackSkipDirection?,
    particleCoverTuning: ParticleCoverTuning,
    lyricsExpanded: Boolean,
    coverContentAlpha: Float,
    onCoverBoundsChanged: (Rect?) -> Unit,
    onCoverAspectRatioChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    onCoverClick: (() -> Unit)?,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
    onCoverMotionActiveChanged: (Boolean) -> Unit,
    coverFlowNavigation: CoverFlowCarouselNavigationBridge,
    photoStackNavigation: PhotoStackCarouselNavigationBridge,
    screenWidth: Dp,
    stripSongTitleParentheses: Boolean,
    coverStartPaddingOverride: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val cover = if (coverStartPaddingOverride != null) {
        frame.cover.copy(startPadding = coverStartPaddingOverride)
    } else {
        frame.cover
    }
    val motionEnabled = rememberMicaMotionEnabled()
    val density = LocalDensity.current
    val context = LocalContext.current
    val failedVideoCovers = remember { mutableStateMapOf<String, Boolean>() }
    val screenWidthPx = with(density) { screenWidth.coerceAtLeast(1.dp).toPx() }
    val coverWidthPx = with(density) { cover.width.toPx() }
    val coverHeightPx = with(density) { cover.height.toPx() }
    val coverStartPaddingPx = with(density) { cover.startPadding.toPx() }
    val particleFrame = frame.particleCover
    val displayTitle = SongTitleDisplay.displayTitle(song.title, stripSongTitleParentheses)
    val nativeParticleCoverActive = particleFrame.enabled && UseNativeParticleCoverInPlayer
    val particleNormalLayerVisible = particleFrame.normalLayerVisible
    val coverSlotVisible = !particleFrame.lyricsBackgroundVisible || nativeParticleCoverActive
    // Lyrics focus lerps the slot toward the mini cover. Pin decode size so portrait
    // cover-flow does not cross into the landscape slot-sized path mid-fold.
    val pinCoverFlowDecodeToViewport = frame.lyricsProgress > ImmersiveProgressEpsilon
    val coverDecodeTarget = remember(
        screenWidthPx,
        coverWidthPx,
        coverHeightPx,
        coverFlowMode,
        pinCoverFlowDecodeToViewport,
    ) {
        if (
            coverFlowMode == PlayerCoverFlowMode.PAUSE_FOLD ||
            coverFlowMode == PlayerCoverFlowMode.RETRO_3D
        ) {
            CoverDecodeTarget.forCoverFlow(
                viewportWidthPx = screenWidthPx,
                slotWidthPx = coverWidthPx,
                slotHeightPx = coverHeightPx,
                pinFullViewport = pinCoverFlowDecodeToViewport,
            )
        } else {
            CoverDecodeTarget.forSpecialTheme(screenWidthPx)
        }
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

    val standardMode =
        !ParticleCoverThemePolicy.coverFlowStageEnabled(coverFlowMode) &&
            !coverFlowMode.usesPhotoStack &&
            !frame.coverFlowStageActive
    val useNativeParticleCover = nativeParticleCoverActive
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

    LaunchedEffect(coverSlotVisible) {
        if (!coverSlotVisible) onCoverBoundsChanged(null)
    }

    val preloadAdjacentCovers = frame.coverFlowStageActive ||
        coverFlowMode == PlayerCoverFlowMode.STANDARD ||
        coverFlowMode == PlayerCoverFlowMode.CUSTOM_STANDARD
    LaunchedEffect(preloadAdjacentCovers, currentIndex, queue, coverDecodeTarget) {
        if (!preloadAdjacentCovers) return@LaunchedEffect
        for (offset in listOf(-1, 1)) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
            withContext(Dispatchers.IO) {
                resolveCoverAspectRatioFromUri(context, uri)
            }
            MicaImageLoaders.preloadCover(context, uri, coverDecodeTarget)
            if (lowerBackground.usesBlurredArtwork) {
                MicaImageLoaders.preloadBackground(context, uri)
            }
        }
    }

    val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
        frame.lyricsProgress < 0.5f &&
        !particleFrame.enabled
    val effectiveCoverDisplayMode = if (ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode)) {
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
            if (particleFrame.normalLayerVisible) {
                SongTitleSection(
                    title = displayTitle,
                    artist = song.artist,
                    album = song.album,
                    isBuffering = false,
                    playbackError = null,
                    colors = contentColors,
                    immersiveProgress = 0f,
                    modifier = Modifier
                        .padding(top = frame.cover.particleInfoTopPadding)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = coverContentAlpha },
                    onLongPress = onCoverLongPress,
                )
            }
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
                    laneMetricsCoverWidthPx = if (pinCoverFlowDecodeToViewport) {
                        screenWidthPx
                    } else {
                        coverWidthPx
                    },
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
                            .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onCoverClick, onCoverLongPress)),
                    )
                }
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && frame.photoStack.normalLayerVisible) {
                Box(
                    modifier = Modifier
                        .padding(start = cover.startPadding, top = cover.topPadding)
                        .size(cover.width, cover.height)
                        .graphicsLayer {
                            alpha = coverContentAlpha
                            clip = false
                        }
                        .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
                        .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onCoverClick, onCoverLongPress)),
                    contentAlignment = Alignment.Center,
                ) {
                    PhotoStackThemeHost(
                        queue = queue,
                        currentIndex = currentIndex,
                        frame = frame.photoStack,
                        seekState = seekState,
                        isPlaying = isPlaying,
                        spectrumEnabled = frame.spectrumEnabled,
                        gesturesEnabled = frame.gesturesEnabled,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onCoverLongPress = onCoverLongPress,
                        onCoverMotionActiveChanged = onCoverMotionActiveChanged,
                        navigationBridge = photoStackNavigation,
                        onPlayQueueIndex = onPlayQueueIndex,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && !frame.photoStack.normalLayerVisible) {
            Box(
                modifier = Modifier
                    .padding(start = cover.startPadding, top = cover.topPadding)
                    .size(cover.width, coverBoxHeight)
                    .graphicsLayer {
                        clip = !coverFlowReflection && !particleNormalLayerVisible && !useNativeParticleCover
                    }
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
                    .then(coverClickModifier(lyricsExpanded, onCloseLyrics, onCoverClick, onCoverLongPress)),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = coverContentAlpha
                            clip = !coverFlowReflection && !particleNormalLayerVisible && !useNativeParticleCover
                            if (standardMode && !frame.coverFlowStageActive) {
                                translationX = gestureState.standardSwipeOffsetFraction *
                                    size.width * 0.35f
                            }
                        }
                        .zIndex(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (particleFrame.enabled) {
                        if (!useNativeParticleCover && particleNormalLayerVisible) {
                            val halo = cover.width * ThreeParticleCoverHaloFraction
                            ThreeParticleCoverHost(
                                song = song,
                                coverDecodeTarget = coverDecodeTarget,
                                motionEnabled = motionEnabled,
                                coverColor = coverColor,
                                tuning = particleCoverTuning,
                                renderVisible = particleNormalLayerVisible && !lyricsExpanded,
                                onAspectRatioChanged = onCoverAspectRatioChanged,
                                onMotionActiveChanged = onCoverMotionActiveChanged,
                                modifier = Modifier.size(cover.width + halo * 2f, cover.height + halo * 2f),
                            )
                        }
                    } else {
                        var coverVisibleSong by remember { mutableStateOf(song) }
                        var coverOutgoingSong by remember { mutableStateOf<Song?>(null) }
                        var coverWipeDirection by remember { mutableStateOf<TrackSkipDirection?>(null) }
                        val coverWipeProgress = remember { Animatable(1f) }
                        SideEffect {
                            if (coverVisibleSong.id == song.id && coverVisibleSong != song) {
                                coverVisibleSong = song
                            }
                        }
                        LaunchedEffect(song.id) {
                            if (coverVisibleSong.id == song.id) {
                                coverVisibleSong = song
                                return@LaunchedEffect
                            }
                            coverOutgoingSong = coverVisibleSong
                            coverVisibleSong = song
                            coverWipeDirection = trackSkipDirection
                            coverWipeProgress.snapTo(0f)
                            if (motionEnabled) {
                                coverWipeProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = MicaMotion.DurationMediumMs,
                                        easing = MicaMotion.Easing,
                                    ),
                                )
                            } else {
                                coverWipeProgress.snapTo(1f)
                            }
                            coverOutgoingSong = null
                            coverWipeDirection = null
                        }
                        val coverOutgoing = coverOutgoingSong
                        fun videoUriOf(track: Song): String? =
                            track.videoCoverUri?.takeIf {
                                videoAlbumCoverEnabled &&
                                    coverFlowMode == PlayerCoverFlowMode.STANDARD &&
                                    failedVideoCovers[it] != true
                            }
                        val pinnedVideo = pinnedVideoCover(
                            wiping = coverOutgoing != null,
                            outgoingVideoUri = coverOutgoing?.let(::videoUriOf),
                            visibleVideoUri = videoUriOf(coverVisibleSong),
                        )
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .then(
                                        if (coverOutgoing != null) {
                                            Modifier.trackWipeLayer(
                                                progress = { coverWipeProgress.value },
                                                direction = coverWipeDirection,
                                                incoming = true,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                SongCover(
                                    albumArtUri = coverVisibleSong.albumArtUri,
                                    fallbackColor = coverColor,
                                    contentDescription = coverVisibleSong.album,
                                    modifier = Modifier.matchParentSize(),
                                    letterboxAlpha = cover.letterboxAlpha,
                                    crossfadeMillis = if (motionEnabled) 200 else 0,
                                    allowPreviousImageUnderlay = false,
                                    onAspectRatioChanged = onCoverAspectRatioChanged,
                                    decodeTarget = coverDecodeTarget.takeIf {
                                        ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode)
                                    },
                                )
                            }
                            if (coverOutgoing != null) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .trackWipeLayer(
                                            progress = { coverWipeProgress.value },
                                            direction = coverWipeDirection,
                                            incoming = false,
                                        ),
                                ) {
                                    SongCover(
                                        albumArtUri = coverOutgoing.albumArtUri,
                                        fallbackColor = coverColor,
                                        contentDescription = null,
                                        modifier = Modifier.matchParentSize(),
                                        letterboxAlpha = cover.letterboxAlpha,
                                        crossfadeMillis = 0,
                                        publishHoldoverOnSuccess = false,
                                        allowPreviousImageUnderlay = false,
                                    )
                                }
                            }
                            // One call-site + key(uri): video→normal must NOT move the host between
                            // separate incoming/outgoing branches (that remounted AndroidView and flashed).
                            val videoSlots = buildList {
                                pinnedVideo.incomingUri?.let { add(it to false) }
                                pinnedVideo.outgoingUri
                                    ?.takeIf { it != pinnedVideo.incomingUri }
                                    ?.let { add(it to true) }
                            }
                            videoSlots.forEach { (videoUri, asOutgoing) ->
                                key(videoUri) {
                                    val holdFullScreenWhileWipe =
                                        coverOutgoing != null &&
                                            !asOutgoing &&
                                            pinnedVideo.outgoingUri == null &&
                                            videoUriOf(coverOutgoing) == videoUri
                                    val wipeModifier = when {
                                        asOutgoing -> Modifier.trackWipeLayer(
                                            progress = { coverWipeProgress.value },
                                            direction = coverWipeDirection,
                                            incoming = false,
                                        )
                                        coverOutgoing != null && !holdFullScreenWhileWipe ->
                                            Modifier.trackWipeLayer(
                                                progress = { coverWipeProgress.value },
                                                direction = coverWipeDirection,
                                                incoming = true,
                                            )
                                        else -> Modifier
                                    }
                                    VideoAlbumCoverHost(
                                        uri = videoUri,
                                        isPlaying = isPlaying &&
                                            !asOutgoing &&
                                            videoUriOf(coverVisibleSong) == videoUri,
                                        onPlaybackError = { failedVideoCovers[videoUri] = true },
                                        modifier = Modifier
                                            .matchParentSize()
                                            .then(wipeModifier),
                                    )
                                }
                            }
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
            if (frame.lyricsProgress > 0.01f && !particleFrame.enabled) {
                LyricsFocusHeaderOverlay(
                    title = displayTitle,
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
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
): Modifier {
    val onClick = if (lyricsExpanded) onCloseLyrics else onCoverClick
    return when {
        onClick != null && onCoverLongPress != null ->
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onCoverLongPress,
            )
        onClick != null -> Modifier.clickable(onClick = onClick)
        onCoverLongPress != null ->
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = onCoverLongPress,
            )
        else -> Modifier
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
