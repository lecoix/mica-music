package com.mica.music.ui.screens

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.imaging.StandardCoverRequestSpec
import com.mica.music.playback.PlaybackVideoState
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.trackWipeLayer
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.CoverGestureState
import com.mica.music.ui.screens.player.PhotoStackImmersiveCaption
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.coverOriginPlacement
import com.mica.music.ui.screens.player.pinnedVideoCover
import com.mica.music.ui.screens.player.view.CoverFlowCarouselHost
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.MusicVideoHost
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHaloFraction
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHost
import com.mica.music.ui.screens.player.view.VideoAlbumCoverHost
import com.mica.music.ui.theme.FloatingIslandShadowHalo
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.PlayerContentColors

@Composable
internal fun CoverFlowCoverRenderer(
    queue: List<Song>,
    currentIndex: Int,
    frame: PlayerPageFrame,
    coverFlowMode: PlayerCoverFlowMode,
    coverDecodeTarget: CoverDecodeTarget,
    coverColor: androidx.compose.ui.graphics.Color,
    contentColors: PlayerContentColors,
    seekState: PlaybackSeekState,
    isPlaying: Boolean,
    screenWidthPx: Float,
    coverWidthPx: Float,
    coverHeightPx: Float,
    coverStartPaddingPx: Float,
    reflectionGapPx: Float,
    cameraDistancePx: Float,
    pinCoverFlowDecodeToViewport: Boolean,
    coverBoxHeight: Dp,
    coverContentAlpha: Float,
    gesturesEnabledOverride: Boolean?,
    lyricsExpanded: Boolean,
    onCloseLyrics: () -> Unit,
    onCloseQueue: () -> Unit,
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAspectRatioChanged: (Float) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
    onCoverBoundsChanged: (Rect?) -> Unit,
    navigationBridge: CoverFlowCarouselNavigationBridge,
    motionEnabled: Boolean,
) {
    val cover = frame.cover
    CoverFlowCarouselHost(
        queue = queue,
        currentIndex = currentIndex,
        coverFlowMode = coverFlowMode,
        foldProgress = frame.coverFlowProgress,
        screenWidthPx = screenWidthPx,
        coverWidthPx = coverWidthPx,
        coverHeightPx = coverHeightPx,
        coverDecodeTarget = coverDecodeTarget,
        laneMetricsCoverWidthPx = if (pinCoverFlowDecodeToViewport) screenWidthPx else coverWidthPx,
        coverStartPaddingPx = coverStartPaddingPx,
        reflectionGapPx = reflectionGapPx,
        cameraDistancePx = cameraDistancePx,
        motionEnabled = motionEnabled,
        coverColor = coverColor,
        stageActive = frame.coverFlowStageActive,
        gesturesEnabled = gesturesEnabledOverride ?: frame.gesturesEnabled,
        onPlayQueueIndex = onPlayQueueIndex,
        onPrevious = onPrevious,
        onNext = onNext,
        onCoverLongPress = onCoverLongPress,
        onAspectRatioChanged = onAspectRatioChanged,
        onMotionActiveChanged = onMotionActiveChanged,
        navigationBridge = navigationBridge,
        modifier = Modifier
            .fillMaxWidth()
            .height(coverBoxHeight)
            .coverOriginPlacement(top = cover.topPadding)
            .graphicsLayer {
                alpha = coverContentAlpha
                clip = false
            }
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                onCoverBoundsChanged(
                    Rect(
                        left = bounds.left + coverStartPaddingPx,
                        top = bounds.top,
                        right = bounds.left + coverStartPaddingPx + coverWidthPx,
                        bottom = bounds.top + coverHeightPx,
                    ),
                )
            },
    )
    if (frame.lower.coverEdgeOnPlaySurface) {
        val centerScale = CoverFlowMath.centerScale(coverFlowMode, frame.coverFlowProgress)
        val centerCoverWidth = cover.width * centerScale
        val centerCoverHeight = cover.height * centerScale
        CoverEdgePlaybackOverlay(
            seekState = seekState,
            spectrumEnabled = frame.spectrumEnabled,
            isPlaying = isPlaying,
            contentColors = contentColors,
            alpha = (1f - frame.lower.chromeProgressAlpha) * coverContentAlpha,
            reflectionHeight = centerCoverHeight * CoverFlowMath.ReflectionHeightFraction,
            reflectionGap = HifiSpacing.sm * centerScale,
            modifier = Modifier
                .coverOriginPlacement(
                    start = cover.startPadding + (cover.width - centerCoverWidth) / 2,
                    top = cover.topPadding + (cover.height - centerCoverHeight) / 2,
                )
                .size(centerCoverWidth, centerCoverHeight)
                .zIndex(2f),
        )
    }
    if (lyricsExpanded || frame.queueProgress > 0.01f) {
        Box(
            modifier = Modifier
                .coverOriginPlacement(start = cover.startPadding, top = cover.topPadding)
                .size(cover.width, cover.height)
                .zIndex(2f)
                .then(
                    coverClickModifier(
                        headerClose = if (frame.queueProgress > 0.01f) onCloseQueue else onCloseLyrics,
                        onCoverClick = onCoverClick,
                        onCoverLongPress = onCoverLongPress,
                    ),
                ),
        )
    }
}

@Composable
internal fun PhotoStackCoverRenderer(
    queue: List<Song>,
    currentIndex: Int,
    frame: PlayerPageFrame,
    seekState: PlaybackSeekState,
    isPlaying: Boolean,
    screenWidth: Dp,
    coverWidthPx: Float,
    coverHeightPx: Float,
    coverContentAlpha: Float,
    immersiveCaption: PhotoStackImmersiveCaption?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
    onMotionActiveChanged: (Boolean) -> Unit,
    navigationBridge: PhotoStackCarouselNavigationBridge,
    onPlayQueueIndex: (Int) -> Unit,
    decodeTarget: CoverDecodeTarget,
    onCoverBoundsChanged: (Rect?) -> Unit,
    density: androidx.compose.ui.unit.Density,
) {
    val cover = frame.cover
    val slotStart = ((screenWidth - frame.photoStack.slotWidth) / 2).coerceAtLeast(0.dp)
    val cardLeftPx = with(density) { ((frame.photoStack.slotWidth - frame.photoStack.cardWidth) / 2).toPx() }
    val cardTopPx = with(density) { frame.photoStack.cardTopInset.toPx() }
    Box(
        modifier = Modifier
            .coverOriginPlacement(start = slotStart, top = cover.topPadding)
            .size(frame.photoStack.slotWidth, frame.photoStack.slotHeight)
            .graphicsLayer {
                alpha = coverContentAlpha * (1f - frame.queueProgress.coerceIn(0f, 1f))
                clip = false
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                onCoverBoundsChanged(
                    Rect(
                        left = bounds.left + cardLeftPx,
                        top = bounds.top + cardTopPx,
                        right = bounds.left + cardLeftPx + coverWidthPx,
                        bottom = bounds.top + cardTopPx + coverHeightPx,
                    ),
                )
            },
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
            immersiveCaption = immersiveCaption,
            onPrevious = onPrevious,
            onNext = onNext,
            onCoverClick = onCoverClick,
            onCoverLongPress = onCoverLongPress,
            onCoverMotionActiveChanged = onMotionActiveChanged,
            navigationBridge = navigationBridge,
            onPlayQueueIndex = onPlayQueueIndex,
            decodeTargetOverride = decodeTarget,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
internal fun ParticleCoverRenderer(
    song: Song,
    frame: PlayerPageFrame,
    coverDecodeTarget: CoverDecodeTarget,
    coverColor: androidx.compose.ui.graphics.Color,
    tuning: ParticleCoverTuning,
    motionEnabled: Boolean,
    lyricsExpanded: Boolean,
    useNativeParticleCover: Boolean,
    onAspectRatioChanged: (Float) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
) {
    if (useNativeParticleCover || !frame.particleCover.normalLayerVisible) return
    val halo = frame.cover.width * ThreeParticleCoverHaloFraction
    ThreeParticleCoverHost(
        song = song,
        coverDecodeTarget = coverDecodeTarget,
        motionEnabled = motionEnabled,
        coverColor = coverColor,
        tuning = tuning,
        renderVisible = !lyricsExpanded,
        onAspectRatioChanged = onAspectRatioChanged,
        onMotionActiveChanged = onMotionActiveChanged,
        modifier = Modifier.size(frame.cover.width + halo * 2f, frame.cover.height + halo * 2f),
    )
}

@Composable
internal fun StandardCoverRenderer(
    song: Song,
    frame: PlayerPageFrame,
    coverFlowMode: PlayerCoverFlowMode,
    coverDecodeTarget: CoverDecodeTarget,
    standardRequestSpec: StandardCoverRequestSpec?,
    coverArtworkScrim: Boolean,
    coverScrimHeightPx: Float,
    coverScrimBottomFraction: Float,
    isDark: Boolean,
    motionEnabled: Boolean,
    trackSkipDirection: TrackSkipDirection?,
    sharedCoverWipeState: PlayerCoverWipeState?,
    sharedCoverWipeTarget: PlayerCoverWipeVisual?,
    videoAlbumCoverEnabled: Boolean,
    failedVideoCovers: MutableMap<String, Boolean>,
    musicVideoState: PlaybackVideoState,
    attachMusicVideoOutput: (TextureView) -> Long?,
    detachMusicVideoOutput: (TextureView, Long) -> Unit,
    lyricsExpanded: Boolean,
    isPlaying: Boolean,
    wipeLayerHeight: Dp,
    onAspectRatioChanged: (Float) -> Unit,
) {
    val cover = frame.cover
    val sharedWipe = sharedCoverWipeState
    val sharedTarget = sharedCoverWipeTarget
    val useSharedWipe = sharedWipe != null && sharedTarget != null && sharedWipe.wipeEnabled
    var localVisibleSong by remember { mutableStateOf(song) }
    var localOutgoingSong by remember { mutableStateOf<Song?>(null) }
    var localWipeDirection by remember { mutableStateOf<TrackSkipDirection?>(null) }
    val localWipeProgress = remember { Animatable(1f) }
    SideEffect {
        if (useSharedWipe) return@SideEffect
        if (localVisibleSong.id == song.id && localVisibleSong != song) localVisibleSong = song
    }
    LaunchedEffect(song.id, useSharedWipe) {
        if (useSharedWipe) return@LaunchedEffect
        if (localVisibleSong.id == song.id) {
            localVisibleSong = song
            return@LaunchedEffect
        }
        localOutgoingSong = localVisibleSong
        localVisibleSong = song
        localWipeDirection = trackSkipDirection
        localWipeProgress.snapTo(0f)
        if (motionEnabled) {
            localWipeProgress.animateTo(
                1f,
                tween(MicaMotion.DurationMediumMs, easing = MicaMotion.Easing),
            )
        } else {
            localWipeProgress.snapTo(1f)
        }
        localOutgoingSong = null
        localWipeDirection = null
    }
    val visibleSong = if (useSharedWipe) sharedWipe!!.visible.song else localVisibleSong
    val outgoingSong = if (useSharedWipe) sharedWipe!!.renderOutgoing(sharedTarget!!)?.song else localOutgoingSong
    val wipeDirection = if (useSharedWipe) sharedWipe!!.direction ?: trackSkipDirection else localWipeDirection
    val wipeProgress: () -> Float = if (useSharedWipe) {
        { sharedWipe!!.renderProgress(sharedTarget!!) }
    } else {
        { localWipeProgress.value }
    }
    val musicVideoVisible = musicVideoState.effective &&
        musicVideoState.mediaId == visibleSong.id &&
        !visibleSong.musicVideoUri.isNullOrBlank() &&
        coverFlowMode == PlayerCoverFlowMode.STANDARD &&
        !lyricsExpanded
    fun videoUriOf(track: Song): String? = track.videoCoverUri?.takeIf {
        videoAlbumCoverEnabled &&
            !musicVideoVisible &&
            coverFlowMode == PlayerCoverFlowMode.STANDARD &&
            failedVideoCovers[it] != true
    }
    val pinnedVideo = pinnedVideoCover(
        wiping = outgoingSong != null,
        outgoingVideoUri = outgoingSong?.let(::videoUriOf),
        visibleVideoUri = videoUriOf(visibleSong),
    )
    Box(
        modifier = Modifier.size(cover.width, wipeLayerHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier.matchParentSize().then(
                if (outgoingSong != null) {
                    Modifier.trackWipeLayer(wipeProgress, wipeDirection, incoming = true)
                } else Modifier,
            ),
        ) {
            CoverArtworkWipeLayer(
                track = visibleSong,
                coverWidth = cover.width,
                coverHeight = cover.height,
                letterboxAlpha = cover.letterboxAlpha,
                motionEnabled = motionEnabled,
                coverDecodeTarget = coverDecodeTarget,
                standardRequestSpec = standardRequestSpec,
                forcesSquareCrop = com.mica.music.ui.screens.player.ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode),
                artworkScrim = coverArtworkScrim,
                scrimHeightPx = coverScrimHeightPx,
                scrimBottomFraction = coverScrimBottomFraction,
                isDark = isDark,
                contentDescription = visibleSong.album,
                onAspectRatioChanged = onAspectRatioChanged,
            )
        }
        if (outgoingSong != null) {
            Box(
                Modifier.matchParentSize().trackWipeLayer(wipeProgress, wipeDirection, incoming = false),
            ) {
                CoverArtworkWipeLayer(
                    track = outgoingSong,
                    coverWidth = cover.width,
                    coverHeight = cover.height,
                    letterboxAlpha = cover.letterboxAlpha,
                    motionEnabled = false,
                    coverDecodeTarget = coverDecodeTarget,
                    standardRequestSpec = standardRequestSpec,
                    forcesSquareCrop = com.mica.music.ui.screens.player.ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode),
                    artworkScrim = coverArtworkScrim,
                    scrimHeightPx = coverScrimHeightPx,
                    scrimBottomFraction = coverScrimBottomFraction,
                    isDark = isDark,
                    contentDescription = null,
                    onAspectRatioChanged = {},
                    publishHoldoverOnSuccess = false,
                )
            }
        }
        if (musicVideoVisible) {
            val wipeModifier = if (outgoingSong != null) {
                Modifier.trackWipeLayer(wipeProgress, wipeDirection, incoming = true)
            } else Modifier
            MusicVideoHost(
                state = musicVideoState,
                attach = attachMusicVideoOutput,
                detach = detachMusicVideoOutput,
                modifier = wipeModifier.size(cover.width, cover.height),
            )
        }
        val videoSlots = buildList {
            pinnedVideo.incomingUri?.let { add(it to false) }
            pinnedVideo.outgoingUri?.takeIf { it != pinnedVideo.incomingUri }?.let { add(it to true) }
        }
        videoSlots.forEach { (videoUri, asOutgoing) ->
            key(videoUri) {
                val holdFullScreenWhileWipe = outgoingSong != null &&
                    !asOutgoing &&
                    pinnedVideo.outgoingUri == null &&
                    videoUriOf(outgoingSong) == videoUri
                val modifier = when {
                    asOutgoing -> Modifier.trackWipeLayer(wipeProgress, wipeDirection, incoming = false)
                    outgoingSong != null && !holdFullScreenWhileWipe ->
                        Modifier.trackWipeLayer(wipeProgress, wipeDirection, incoming = true)
                    else -> Modifier
                }
                VideoAlbumCoverHost(
                    uri = videoUri,
                    isPlaying = isPlaying && !asOutgoing && videoUriOf(visibleSong) == videoUri,
                    onPlaybackError = { failedVideoCovers[videoUri] = true },
                    modifier = Modifier.size(cover.width, cover.height).then(modifier),
                )
            }
        }
    }
}

@Composable
internal fun StandardOrParticleCoverRenderer(
    song: Song,
    frame: PlayerPageFrame,
    coverFlowMode: PlayerCoverFlowMode,
    coverDecodeTarget: CoverDecodeTarget,
    standardRequestSpec: StandardCoverRequestSpec?,
    coverArtworkScrim: Boolean,
    coverScrimHeightPx: Float,
    coverScrimBottomFraction: Float,
    isDark: Boolean,
    motionEnabled: Boolean,
    trackSkipDirection: TrackSkipDirection?,
    sharedCoverWipeState: PlayerCoverWipeState?,
    sharedCoverWipeTarget: PlayerCoverWipeVisual?,
    videoAlbumCoverEnabled: Boolean,
    failedVideoCovers: MutableMap<String, Boolean>,
    musicVideoState: PlaybackVideoState,
    attachMusicVideoOutput: (TextureView) -> Long?,
    detachMusicVideoOutput: (TextureView, Long) -> Unit,
    lyricsExpanded: Boolean,
    isPlaying: Boolean,
    wipeLayerHeight: Dp,
    useNativeParticleCover: Boolean,
    coverColor: androidx.compose.ui.graphics.Color,
    particleCoverTuning: ParticleCoverTuning,
    onAspectRatioChanged: (Float) -> Unit,
    onMotionActiveChanged: (Boolean) -> Unit,
) {
    if (frame.particleCover.enabled) {
        ParticleCoverRenderer(
            song = song,
            frame = frame,
            coverDecodeTarget = coverDecodeTarget,
            coverColor = coverColor,
            tuning = particleCoverTuning,
            motionEnabled = motionEnabled,
            lyricsExpanded = lyricsExpanded,
            useNativeParticleCover = useNativeParticleCover,
            onAspectRatioChanged = onAspectRatioChanged,
            onMotionActiveChanged = onMotionActiveChanged,
        )
    } else {
        StandardCoverRenderer(
            song = song,
            frame = frame,
            coverFlowMode = coverFlowMode,
            coverDecodeTarget = coverDecodeTarget,
            standardRequestSpec = standardRequestSpec,
            coverArtworkScrim = coverArtworkScrim,
            coverScrimHeightPx = coverScrimHeightPx,
            coverScrimBottomFraction = coverScrimBottomFraction,
            isDark = isDark,
            motionEnabled = motionEnabled,
            trackSkipDirection = trackSkipDirection,
            sharedCoverWipeState = sharedCoverWipeState,
            sharedCoverWipeTarget = sharedCoverWipeTarget,
            videoAlbumCoverEnabled = videoAlbumCoverEnabled,
            failedVideoCovers = failedVideoCovers,
            musicVideoState = musicVideoState,
            attachMusicVideoOutput = attachMusicVideoOutput,
            detachMusicVideoOutput = detachMusicVideoOutput,
            lyricsExpanded = lyricsExpanded,
            isPlaying = isPlaying,
            wipeLayerHeight = wipeLayerHeight,
            onAspectRatioChanged = onAspectRatioChanged,
        )
    }
}

@Composable
internal fun CoverRendererSlot(
    frame: PlayerPageFrame,
    standardMode: Boolean,
    gestureState: CoverGestureState,
    coverArtworkScrim: Boolean,
    coverFlowReflection: Boolean,
    particleNormalLayerVisible: Boolean,
    useNativeParticleCover: Boolean,
    coverShadowEnabled: Boolean,
    coverContentAlpha: Float,
    coverScrimExtend: Dp,
    coverBoxHeight: Dp,
    lyricsExpanded: Boolean,
    onCloseLyrics: () -> Unit,
    onCloseQueue: () -> Unit,
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
    onCoverBoundsChanged: (Rect?) -> Unit,
    isDark: Boolean,
    content: @Composable (Dp) -> Unit,
) {
    val cover = frame.cover
    val wipeLayerHeight = if (coverArtworkScrim) cover.height + coverScrimExtend else coverBoxHeight
    Box(
        modifier = Modifier
            .coverOriginPlacement(start = cover.startPadding, top = cover.topPadding)
            .size(cover.width, coverBoxHeight)
            .graphicsLayer {
                clip = !coverArtworkScrim && !coverFlowReflection && !particleNormalLayerVisible &&
                    !useNativeParticleCover && !coverShadowEnabled
            }
            .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
            .pointerInput(frame.gesturesEnabled, frame.coverFlowStageActive) {
                if (frame.gesturesEnabled && !frame.coverFlowStageActive) {
                    detectHorizontalDragGestures(
                        onDragStart = { gestureState.handlers.onDragStart() },
                        onDragEnd = { gestureState.handlers.onDragEnd() },
                        onHorizontalDrag = { _, dragAmount -> gestureState.handlers.onHorizontalDrag(dragAmount) },
                    )
                }
            }
            .then(
                coverClickModifier(
                    headerClose = when {
                        frame.queueProgress > 0.01f -> onCloseQueue
                        lyricsExpanded -> onCloseLyrics
                        else -> null
                    },
                    onCoverClick = onCoverClick,
                    onCoverLongPress = onCoverLongPress,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = coverContentAlpha
                    clip = !coverArtworkScrim && !coverFlowReflection && !particleNormalLayerVisible &&
                        !useNativeParticleCover && !coverShadowEnabled
                    if (standardMode && !frame.coverFlowStageActive) {
                        translationX = gestureState.standardSwipeOffsetFraction * size.width * 0.35f
                    }
                }
                .zIndex(1f),
            contentAlignment = Alignment.TopStart,
        ) {
            if (coverShadowEnabled) {
                FloatingIslandShadowHalo(
                    isDark = isDark,
                    modifier = Modifier.size(cover.width, cover.height),
                )
            }
            content(wipeLayerHeight)
        }
    }
}
