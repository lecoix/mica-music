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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
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
import com.mica.music.R
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.imaging.StandardCoverRequestSpec
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.trackWipeLayer
import com.mica.music.ui.components.LivePlayerSpectrumStrip
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.resolveCoverAspectRatioFromUri
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.screens.player.CoverFlowMath
import com.mica.music.ui.screens.player.coverOriginPlacement
import com.mica.music.ui.screens.player.ImmersiveProgressEpsilon
import com.mica.music.ui.screens.player.pinnedVideoCover
import com.mica.music.ui.screens.player.ParticleCoverThemePolicy
import com.mica.music.ui.screens.player.PhotoStackImmersiveCaption
import com.mica.music.ui.screens.player.PlayerPageFrame
import com.mica.music.ui.screens.player.LyricsFocusCoverStartPadding
import com.mica.music.ui.screens.player.LyricsFocusMiniCoverSize
import com.mica.music.ui.screens.player.PlayerPageLayoutEngine
import com.mica.music.ui.screens.player.UseNativeParticleCoverInPlayer
import com.mica.music.ui.screens.player.playerHeaderFocus
import com.mica.music.ui.screens.player.rememberCoverGestureState
import com.mica.music.ui.screens.player.view.CoverFlowCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.PhotoStackCarouselNavigationBridge
import com.mica.music.ui.screens.player.view.CoverFlowCarouselHost
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHaloFraction
import com.mica.music.ui.screens.player.view.ThreeParticleCoverHost
import com.mica.music.ui.screens.player.view.VideoAlbumCoverHost
import com.mica.music.ui.screens.player.view.MusicVideoHost
import com.mica.music.playback.PlaybackVideoState
import android.view.TextureView
import com.mica.music.ui.theme.FloatingIslandShadowHalo
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import coil.size.Scale
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkCoverScrimStops
import com.mica.music.ui.theme.artworkGradientScrimColors
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
    photoStackImmersiveCaption: PhotoStackImmersiveCaption? = null,
    coverFlowMode: PlayerCoverFlowMode,
    videoAlbumCoverEnabled: Boolean,
    musicVideoState: PlaybackVideoState,
    attachMusicVideoOutput: (TextureView) -> Long?,
    detachMusicVideoOutput: (TextureView, Long) -> Unit,
    trackSkipDirection: TrackSkipDirection?,
    particleCoverTuning: ParticleCoverTuning,
    lyricsExpanded: Boolean,
    coverContentAlpha: Float,
    coverShadowEnabled: Boolean = false,
    onCoverBoundsChanged: (Rect?) -> Unit,
    onCoverAspectRatioChanged: (Float) -> Unit,
    onCloseLyrics: () -> Unit,
    onCloseQueue: () -> Unit = {},
    onCoverClick: (() -> Unit)?,
    onPlayQueueIndex: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCoverLongPress: (() -> Unit)?,
    onCoverMotionActiveChanged: (Boolean) -> Unit,
    coverFlowNavigation: CoverFlowCarouselNavigationBridge,
    photoStackNavigation: PhotoStackCarouselNavigationBridge,
    screenWidth: Dp,
    standardCoverRequestWidth: Dp,
    standardCoverRequestHeight: Dp,
    stripSongTitleParentheses: Boolean,
    coverDecodeTargetOverride: CoverDecodeTarget? = null,
    coverFlowGesturesEnabledOverride: Boolean? = null,
    coverStartPaddingOverride: Dp? = null,
    /** When set, cover wipe shares progress with [OutgoingCoverBackgroundWipe]. */
    sharedCoverWipeState: PlayerCoverWipeState? = null,
    sharedCoverWipeTarget: PlayerCoverWipeVisual? = null,
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
    val standardCoverRequestWidthPx = with(density) { standardCoverRequestWidth.toPx() }
    val standardCoverRequestHeightPx = with(density) { standardCoverRequestHeight.toPx() }
    val coverStartPaddingPx = with(density) { cover.startPadding.toPx() }
    val particleFrame = frame.particleCover
    val displayTitle = SongTitleDisplay.displayTitle(song.title, stripSongTitleParentheses)
    val nativeParticleCoverActive = particleFrame.enabled && UseNativeParticleCoverInPlayer
    val particleNormalLayerVisible = particleFrame.normalLayerVisible
    val coverSlotVisible = !particleFrame.lyricsBackgroundVisible || nativeParticleCoverActive
    // Lyrics focus lerps the slot toward the mini cover. Pin decode size so portrait
    // cover-flow does not cross into the landscape slot-sized path mid-fold.
    val pinCoverFlowDecodeToViewport = playerHeaderFocus(
        frame.lyricsProgress,
        frame.queueProgress,
    ) > ImmersiveProgressEpsilon
    val calculatedCoverDecodeTarget = remember(
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
    val coverDecodeTarget = coverDecodeTargetOverride ?: calculatedCoverDecodeTarget
    val photoStackDecodeTarget = remember(frame.photoStack.decodeArtworkSize, density) {
        val artworkSizePx = with(density) { frame.photoStack.decodeArtworkSize.toPx() }
        CoverDecodeTarget.fromPixels(artworkSizePx, artworkSizePx)
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
    val effectiveCoverDisplayMode = if (ParticleCoverThemePolicy.forcesSquareCrop(coverFlowMode)) {
        CoverDisplayMode.CROP_FILL
    } else {
        LocalCoverDisplayMode.current
    }
    val standardRequestSpec = remember(
        standardMode,
        standardCoverRequestWidthPx,
        standardCoverRequestHeightPx,
        effectiveCoverDisplayMode,
    ) {
        if (standardMode) {
            StandardCoverRequestSpec.fromPixels(
                widthPx = standardCoverRequestWidthPx,
                heightPx = standardCoverRequestHeightPx,
                scale = if (effectiveCoverDisplayMode == CoverDisplayMode.CROP_FILL) {
                    Scale.FILL
                } else {
                    Scale.FIT
                },
            )
        } else {
            null
        }
    }
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
    LaunchedEffect(
        preloadAdjacentCovers,
        currentIndex,
        queue,
        coverDecodeTarget,
        standardRequestSpec,
    ) {
        if (!preloadAdjacentCovers) return@LaunchedEffect
        for (offset in listOf(-1, 1)) {
            val uri = queue.getOrNull(currentIndex + offset)?.albumArtUri ?: continue
            withContext(Dispatchers.IO) {
                resolveCoverAspectRatioFromUri(context, uri)
            }
            if (standardRequestSpec != null) {
                MicaImageLoaders.preloadCover(context, uri, standardRequestSpec)
            } else {
                MicaImageLoaders.preloadCover(context, uri, coverDecodeTarget)
            }
            if (lowerBackground.usesBlurredArtwork) {
                MicaImageLoaders.preloadBackground(context, uri)
            }
        }
    }

    val coverArtworkScrim = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
        playerHeaderFocus(frame.lyricsProgress, frame.queueProgress) < 0.5f &&
        !particleFrame.enabled &&
        !coverFlowMode.usesPhotoStack
    val isDark = MicaTheme.colors.isDark
    val coverScrimExtend = ArtworkCoverScrimExtendDp.dp
    val coverScrimCoverHeightPx = with(density) { cover.height.toPx() }
    val coverScrimExtendPx = with(density) { coverScrimExtend.toPx() }
    val coverScrimHeightPx = coverScrimCoverHeightPx + coverScrimExtendPx
    val coverScrimBottomFraction = if (coverScrimHeightPx > 0f) {
        coverScrimCoverHeightPx / coverScrimHeightPx
    } else {
        1f
    }
    CompositionLocalProvider(LocalCoverDisplayMode provides effectiveCoverDisplayMode) {
        val photoStackRestBlockHeight = if (frame.photoStack.normalLayerVisible) {
            frame.photoStack.slotHeight + cover.topPadding +
                frame.lower.photoStackTitleToControlsGap
        } else {
            cover.blockHeight
        }
        Box(
            modifier
                .height(photoStackRestBlockHeight)
                .fillMaxWidth()
                .graphicsLayer {
                    // Scrim / reflection / optional cover halo may paint past the layout box.
                    clip = !coverArtworkScrim &&
                        !coverFlowReflection &&
                        !coverShadowEnabled &&
                        !frame.photoStack.normalLayerVisible
                }
                .then(
                    if (coverFlowReflection) {
                        // 倒影在布局高度外绘制，不占下半区纵向空间
                        Modifier.zIndex(1f)
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
                        .graphicsLayer {
                            alpha = coverContentAlpha *
                                (1f - frame.queueProgress.coerceIn(0f, 1f))
                        },
                    onLongPress = onCoverLongPress,
                )
            }
            if (frame.coverFlowStageActive) {
                CoverFlowCoverRenderer(
                    queue = queue,
                    currentIndex = currentIndex,
                    frame = frame.copy(cover = cover),
                    coverFlowMode = coverFlowMode,
                    coverDecodeTarget = coverDecodeTarget,
                    coverColor = coverColor,
                    contentColors = contentColors,
                    seekState = seekState,
                    isPlaying = isPlaying,
                    screenWidthPx = screenWidthPx,
                    coverWidthPx = coverWidthPx,
                    coverHeightPx = coverHeightPx,
                    coverStartPaddingPx = coverStartPaddingPx,
                    reflectionGapPx = reflectionGapPx,
                    cameraDistancePx = cameraDistancePx,
                    pinCoverFlowDecodeToViewport = pinCoverFlowDecodeToViewport,
                    coverBoxHeight = coverBoxHeight,
                    coverContentAlpha = coverContentAlpha,
                    gesturesEnabledOverride = coverFlowGesturesEnabledOverride,
                    lyricsExpanded = lyricsExpanded,
                    onCloseLyrics = onCloseLyrics,
                    onCloseQueue = onCloseQueue,
                    onCoverClick = onCoverClick,
                    onCoverLongPress = onCoverLongPress,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onAspectRatioChanged = onCoverAspectRatioChanged,
                    onMotionActiveChanged = onCoverMotionActiveChanged,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    navigationBridge = coverFlowNavigation,
                    motionEnabled = motionEnabled,
                )
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && frame.photoStack.normalLayerVisible) {
                PhotoStackCoverRenderer(
                    queue = queue,
                    currentIndex = currentIndex,
                    frame = frame.copy(cover = cover),
                    seekState = seekState,
                    isPlaying = isPlaying,
                    screenWidth = screenWidth,
                    coverWidthPx = coverWidthPx,
                    coverHeightPx = coverHeightPx,
                    coverContentAlpha = coverContentAlpha,
                    immersiveCaption = photoStackImmersiveCaption,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCoverClick = onCoverClick,
                    onCoverLongPress = onCoverLongPress,
                    onMotionActiveChanged = onCoverMotionActiveChanged,
                    navigationBridge = photoStackNavigation,
                    onPlayQueueIndex = onPlayQueueIndex,
                    decodeTarget = photoStackDecodeTarget,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    density = density,
                )
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && !frame.photoStack.normalLayerVisible) {
                CoverRendererSlot(
                    frame = frame.copy(cover = cover),
                    standardMode = standardMode,
                    gestureState = gestureState,
                    coverArtworkScrim = coverArtworkScrim,
                    coverFlowReflection = coverFlowReflection,
                    particleNormalLayerVisible = particleNormalLayerVisible,
                    useNativeParticleCover = useNativeParticleCover,
                    coverShadowEnabled = coverShadowEnabled,
                    coverContentAlpha = coverContentAlpha,
                    coverScrimExtend = coverScrimExtend,
                    coverBoxHeight = coverBoxHeight,
                    lyricsExpanded = lyricsExpanded,
                    onCloseLyrics = onCloseLyrics,
                    onCloseQueue = onCloseQueue,
                    onCoverClick = onCoverClick,
                    onCoverLongPress = onCoverLongPress,
                    onCoverBoundsChanged = onCoverBoundsChanged,
                    isDark = isDark,
                ) { wipeLayerHeight ->
                    StandardOrParticleCoverRenderer(
                        song = song,
                        frame = frame.copy(cover = cover),
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
                        useNativeParticleCover = useNativeParticleCover,
                        coverColor = coverColor,
                        particleCoverTuning = particleCoverTuning,
                        onAspectRatioChanged = onCoverAspectRatioChanged,
                        onMotionActiveChanged = onCoverMotionActiveChanged,
                    )
                    if (frame.lower.coverEdgeOnPlaySurface) {
                        val coverEdgeProgressAlpha = 1f - frame.lower.chromeProgressAlpha
                        CoverEdgePlaybackOverlay(
                            seekState = seekState,
                            spectrumEnabled = frame.spectrumEnabled,
                            isPlaying = isPlaying,
                            contentColors = contentColors,
                            alpha = coverEdgeProgressAlpha,
                            modifier = Modifier.size(cover.width, cover.height),
                        )
                    }
                }
            }
            val headerFocus = playerHeaderFocus(frame.lyricsProgress, frame.queueProgress)
            val closeFocusedHeader = {
                if (frame.queueProgress > 0.01f) {
                    onCloseQueue()
                } else {
                    onCloseLyrics()
                }
            }
            val showFocusHeader = headerFocus > 0.01f && (
                frame.queueProgress > 0.01f ||
                    (!particleFrame.enabled && !coverFlowMode.usesPhotoStack)
                )
            if (showFocusHeader) {
                val photoStackQueueHeader =
                    coverFlowMode.usesPhotoStack && frame.queueProgress > 0.01f
                LyricsFocusHeaderOverlay(
                    title = displayTitle,
                    artist = song.artist,
                    coverWidth = if (photoStackQueueHeader) {
                        LyricsFocusMiniCoverSize
                    } else {
                        cover.width
                    },
                    coverHeight = if (photoStackQueueHeader) {
                        LyricsFocusMiniCoverSize
                    } else {
                        cover.height
                    },
                    coverStartPadding = if (photoStackQueueHeader) {
                        LyricsFocusCoverStartPadding
                    } else {
                        cover.startPadding
                    },
                    coverTopPadding = if (photoStackQueueHeader) {
                        cover.particleInfoTopPadding - HifiSpacing.lg
                    } else {
                        cover.topPadding
                    },
                    colors = contentColors,
                    focusAlpha = headerFocus,
                    onCloseLyrics = closeFocusedHeader,
                    headerCoverSong = if (photoStackQueueHeader) song else null,
                )
            }
        }
    }
}

@Composable
internal fun CoverEdgePlaybackOverlay(
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

/** How far the artwork scrim continues past the cover bottom into the lower panel. */
internal const val ArtworkCoverScrimExtendDp = 48

@Composable
internal fun CoverArtworkWipeLayer(
    track: Song,
    coverWidth: Dp,
    coverHeight: Dp,
    letterboxAlpha: Float,
    motionEnabled: Boolean,
    coverDecodeTarget: CoverDecodeTarget,
    standardRequestSpec: StandardCoverRequestSpec?,
    forcesSquareCrop: Boolean,
    artworkScrim: Boolean,
    scrimHeightPx: Float,
    scrimBottomFraction: Float,
    isDark: Boolean,
    contentDescription: String?,
    onAspectRatioChanged: (Float) -> Unit,
    publishHoldoverOnSuccess: Boolean = true,
) {
    val (junction, hold) = remember(track.coverColorArgb, isDark) {
        artworkGradientScrimColors(Color(track.coverColorArgb), isDark)
    }
    Box(
        Modifier
            .size(coverWidth, coverHeight + if (artworkScrim) ArtworkCoverScrimExtendDp.dp else 0.dp)
            .then(
                if (artworkScrim) {
                    val stops = artworkCoverScrimStops(
                        junction = junction,
                        hold = hold,
                        coverBottomFraction = scrimBottomFraction,
                    )
                    Modifier.drawWithCache {
                        val brush = Brush.verticalGradient(
                            colorStops = stops,
                            startY = 0f,
                            endY = scrimHeightPx,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(
                                brush = brush,
                                topLeft = Offset.Zero,
                                size = Size(size.width, scrimHeightPx),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        SongCover(
            albumArtUri = track.albumArtUri,
            fallbackColor = Color(track.coverColorArgb),
            contentDescription = contentDescription,
            modifier = Modifier.size(coverWidth, coverHeight),
            letterboxAlpha = letterboxAlpha,
            crossfadeMillis = if (motionEnabled) 200 else 0,
            publishHoldoverOnSuccess = publishHoldoverOnSuccess,
            standardRequestSpec = standardRequestSpec,
            allowPreviousImageUnderlay = false,
            onAspectRatioChanged = onAspectRatioChanged,
            decodeTarget = coverDecodeTarget.takeIf { forcesSquareCrop },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun coverClickModifier(
    headerClose: (() -> Unit)?,
    onCoverClick: (() -> Unit)?,
    onCoverLongPress: (() -> Unit)?,
): Modifier {
    val onClick = headerClose ?: onCoverClick
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
    headerCoverSong: Song? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .coverOriginPlacement(start = coverStartPadding, top = coverTopPadding)
            .padding(end = HifiSpacing.lg)
            .graphicsLayer { alpha = focusAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (headerCoverSong != null) {
            CompositionLocalProvider(LocalCoverDisplayMode provides CoverDisplayMode.CROP_FILL) {
                SongCover(
                    albumArtUri = headerCoverSong.albumArtUri,
                    fallbackColor = Color(headerCoverSong.coverColorArgb),
                    modifier = Modifier
                        .size(coverWidth, coverHeight)
                        .clickable(onClick = onCloseLyrics),
                    contentDescription = null,
                    noCoverPlaceholderResId = R.drawable.no_cover_placeholder_small,
                    decodeTarget = CoverDecodeTarget.forCompactCover(),
                    crossfadeMillis = 0,
                    publishHoldoverOnSuccess = false,
                    allowPreviousImageUnderlay = false,
                )
            }
        } else {
            Spacer(Modifier.size(coverWidth, coverHeight))
        }
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
                contentDescription = "关闭",
                tint = colors.primary,
            )
        }
    }
}
