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
import com.mica.music.ui.screens.player.ImmersiveProgressEpsilon
import com.mica.music.ui.screens.player.pinnedVideoCover
import com.mica.music.ui.screens.player.ParticleCoverThemePolicy
import com.mica.music.ui.screens.player.PlayerPageFrame
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
    val photoStackDecodeTarget = remember(screenWidthPx) {
        val immersiveArtworkSizePx =
            screenWidthPx *
                PlayerPageLayoutEngine.PhotoStackImmersiveScreenFraction *
                (1f - PlayerPageLayoutEngine.PhotoStackArtworkInsetHorizontalFraction * 2f)
        CoverDecodeTarget.fromPixels(immersiveArtworkSizePx, immersiveArtworkSizePx)
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
        Box(
            modifier
                .height(cover.blockHeight)
                .fillMaxWidth()
                .graphicsLayer {
                    // Scrim / reflection / optional cover halo may paint past the layout box.
                    clip = !coverArtworkScrim && !coverFlowReflection && !coverShadowEnabled
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
                    gesturesEnabled = coverFlowGesturesEnabledOverride ?: frame.gesturesEnabled,
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
                if (lyricsExpanded || frame.queueProgress > 0.01f) {
                    Box(
                        modifier = Modifier
                            .padding(start = cover.startPadding, top = cover.topPadding)
                            .size(cover.width, cover.height)
                            .zIndex(2f)
                            .then(
                                coverClickModifier(
                                    headerClose = if (frame.queueProgress > 0.01f) {
                                        onCloseQueue
                                    } else {
                                        onCloseLyrics
                                    },
                                    onCoverClick = onCoverClick,
                                    onCoverLongPress = onCoverLongPress,
                                ),
                            ),
                    )
                }
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && frame.photoStack.normalLayerVisible) {
                val photoStackSlotStart =
                    ((screenWidth - frame.photoStack.slotWidth) / 2).coerceAtLeast(0.dp)
                val photoStackCardLeftPx = with(density) {
                    ((frame.photoStack.slotWidth - frame.photoStack.cardWidth) / 2).toPx()
                }
                val photoStackCardTopPx = with(density) { frame.photoStack.cardTopInset.toPx() }
                Box(
                    modifier = Modifier
                        .padding(start = photoStackSlotStart, top = cover.topPadding)
                        .size(frame.photoStack.slotWidth, frame.photoStack.slotHeight)
                        .graphicsLayer {
                            alpha = coverContentAlpha
                            clip = false
                        }
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInRoot()
                            onCoverBoundsChanged(
                                Rect(
                                    left = bounds.left + photoStackCardLeftPx,
                                    top = bounds.top + photoStackCardTopPx,
                                    right = bounds.left + photoStackCardLeftPx + coverWidthPx,
                                    bottom = bounds.top + photoStackCardTopPx + coverHeightPx,
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
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onCoverClick = onCoverClick,
                        onCoverLongPress = onCoverLongPress,
                        onCoverMotionActiveChanged = onCoverMotionActiveChanged,
                        navigationBridge = photoStackNavigation,
                        onPlayQueueIndex = onPlayQueueIndex,
                        decodeTargetOverride = photoStackDecodeTarget,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            if (!frame.coverFlowStageActive && coverSlotVisible && !frame.photoStack.normalLayerVisible) {
            val wipeLayerHeight = if (coverArtworkScrim) {
                cover.height + coverScrimExtend
            } else {
                coverBoxHeight
            }
            Box(
                modifier = Modifier
                    .padding(start = cover.startPadding, top = cover.topPadding)
                    .size(cover.width, coverBoxHeight)
                    .graphicsLayer {
                        // Allow wipe layers (+ scrim extend / optional cover halo) to paint past the layout slot.
                        clip = !coverArtworkScrim &&
                            !coverFlowReflection &&
                            !particleNormalLayerVisible &&
                            !useNativeParticleCover &&
                            !coverShadowEnabled
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
                            clip = !coverArtworkScrim &&
                                !coverFlowReflection &&
                                !particleNormalLayerVisible &&
                                !useNativeParticleCover &&
                                !coverShadowEnabled
                            if (standardMode && !frame.coverFlowStageActive) {
                                translationX = gestureState.standardSwipeOffsetFraction *
                                    size.width * 0.35f
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
                    if (particleFrame.enabled && frame.queueProgress <= ImmersiveProgressEpsilon) {
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
                        val sharedWipe = sharedCoverWipeState
                        val sharedTarget = sharedCoverWipeTarget
                        val useSharedWipe = sharedWipe != null &&
                            sharedTarget != null &&
                            sharedWipe.wipeEnabled
                        var localVisibleSong by remember { mutableStateOf(song) }
                        var localOutgoingSong by remember { mutableStateOf<Song?>(null) }
                        var localWipeDirection by remember { mutableStateOf<TrackSkipDirection?>(null) }
                        val localWipeProgress = remember { Animatable(1f) }
                        SideEffect {
                            if (useSharedWipe) return@SideEffect
                            if (localVisibleSong.id == song.id && localVisibleSong != song) {
                                localVisibleSong = song
                            }
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
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = MicaMotion.DurationMediumMs,
                                        easing = MicaMotion.Easing,
                                    ),
                                )
                            } else {
                                localWipeProgress.snapTo(1f)
                            }
                            localOutgoingSong = null
                            localWipeDirection = null
                        }
                        val coverVisibleSong = if (useSharedWipe) {
                            sharedWipe!!.visible.song
                        } else {
                            localVisibleSong
                        }
                        val coverOutgoing = if (useSharedWipe) {
                            sharedWipe!!.renderOutgoing(sharedTarget!!)?.song
                        } else {
                            localOutgoingSong
                        }
                        val coverWipeDirection = if (useSharedWipe) {
                            sharedWipe!!.direction ?: trackSkipDirection
                        } else {
                            localWipeDirection
                        }
                        val coverWipeProgress: () -> Float = if (useSharedWipe) {
                            { sharedWipe!!.renderProgress(sharedTarget!!) }
                        } else {
                            { localWipeProgress.value }
                        }
                        val musicVideoVisible = musicVideoState.effective &&
                            musicVideoState.mediaId == coverVisibleSong.id &&
                            !coverVisibleSong.musicVideoUri.isNullOrBlank() &&
                            coverFlowMode == PlayerCoverFlowMode.STANDARD &&
                            !lyricsExpanded
                        fun videoUriOf(track: Song): String? =
                            track.videoCoverUri?.takeIf {
                                videoAlbumCoverEnabled &&
                                    !musicVideoVisible &&
                                    coverFlowMode == PlayerCoverFlowMode.STANDARD &&
                                    failedVideoCovers[it] != true
                            }
                        val pinnedVideo = pinnedVideoCover(
                            wiping = coverOutgoing != null,
                            outgoingVideoUri = coverOutgoing?.let(::videoUriOf),
                            visibleVideoUri = videoUriOf(coverVisibleSong),
                        )
                        Box(
                            modifier = Modifier.size(cover.width, wipeLayerHeight),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .then(
                                        if (coverOutgoing != null) {
                                            Modifier.trackWipeLayer(
                                                progress = coverWipeProgress,
                                                direction = coverWipeDirection,
                                                incoming = true,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                CoverArtworkWipeLayer(
                                    track = coverVisibleSong,
                                    coverWidth = cover.width,
                                    coverHeight = cover.height,
                                    letterboxAlpha = cover.letterboxAlpha,
                                    motionEnabled = motionEnabled,
                                    coverDecodeTarget = coverDecodeTarget,
                                    standardRequestSpec = standardRequestSpec,
                                    forcesSquareCrop = ParticleCoverThemePolicy.forcesSquareCrop(
                                        coverFlowMode,
                                    ),
                                    artworkScrim = coverArtworkScrim,
                                    scrimHeightPx = coverScrimHeightPx,
                                    scrimBottomFraction = coverScrimBottomFraction,
                                    isDark = isDark,
                                    contentDescription = coverVisibleSong.album,
                                    onAspectRatioChanged = onCoverAspectRatioChanged,
                                )
                            }
                            if (coverOutgoing != null) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .trackWipeLayer(
                                            progress = coverWipeProgress,
                                            direction = coverWipeDirection,
                                            incoming = false,
                                        ),
                                ) {
                                    CoverArtworkWipeLayer(
                                        track = coverOutgoing,
                                        coverWidth = cover.width,
                                        coverHeight = cover.height,
                                        letterboxAlpha = cover.letterboxAlpha,
                                        motionEnabled = false,
                                        coverDecodeTarget = coverDecodeTarget,
                                        standardRequestSpec = standardRequestSpec,
                                        forcesSquareCrop = ParticleCoverThemePolicy.forcesSquareCrop(
                                            coverFlowMode,
                                        ),
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
                                val musicVideoWipeModifier = if (coverOutgoing != null) {
                                    Modifier.trackWipeLayer(
                                        progress = coverWipeProgress,
                                        direction = coverWipeDirection,
                                        incoming = true,
                                    )
                                } else {
                                    Modifier
                                }
                                MusicVideoHost(
                                    state = musicVideoState,
                                    attach = attachMusicVideoOutput,
                                    detach = detachMusicVideoOutput,
                                    modifier = musicVideoWipeModifier.size(cover.width, cover.height),
                                )
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
                                            progress = coverWipeProgress,
                                            direction = coverWipeDirection,
                                            incoming = false,
                                        )
                                        coverOutgoing != null && !holdFullScreenWhileWipe ->
                                            Modifier.trackWipeLayer(
                                                progress = coverWipeProgress,
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
                                            .size(cover.width, cover.height)
                                            .then(wipeModifier),
                                    )
                                }
                            }
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
                            modifier = Modifier.size(cover.width, cover.height),
                        )
                    }
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
                LyricsFocusHeaderOverlay(
                    title = displayTitle,
                    artist = song.artist,
                    coverWidth = cover.width,
                    coverHeight = cover.height,
                    coverStartPadding = cover.startPadding,
                    coverTopPadding = cover.topPadding,
                    colors = contentColors,
                    focusAlpha = headerFocus,
                    onCloseLyrics = closeFocusedHeader,
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

/** How far the artwork scrim continues past the cover bottom into the lower panel. */
private const val ArtworkCoverScrimExtendDp = 48

@Composable
private fun CoverArtworkWipeLayer(
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
            diagnosticRole = if (publishHoldoverOnSuccess) "cover-current" else "cover-outgoing",
            standardRequestSpec = standardRequestSpec,
            allowPreviousImageUnderlay = false,
            onAspectRatioChanged = onAspectRatioChanged,
            decodeTarget = coverDecodeTarget.takeIf { forcesSquareCrop },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun coverClickModifier(
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
                contentDescription = "关闭",
                tint = colors.primary,
            )
        }
    }
}
