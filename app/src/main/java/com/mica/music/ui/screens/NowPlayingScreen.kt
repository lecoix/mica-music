package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.data.AppUiSettings
import com.mica.music.data.CoverDisplayMode
import com.mica.music.data.ArtistNames
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsSync
import com.mica.music.data.PlayerController
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.ui.components.CoverEdgeProgressBar
import com.mica.music.ui.components.HiFiInfoRow
import com.mica.music.ui.components.HiResIndicator
import com.mica.music.ui.components.LyricLineBlock
import com.mica.music.ui.components.LyricsAreaEdgeFade
import com.mica.music.ui.components.MarqueeTitleText
import com.mica.music.ui.components.lyricTransitionSpec
import com.mica.music.ui.components.rememberLyricLineColorSpec
import com.mica.music.ui.components.rememberPlayerPanelLyricStyles
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.coverIntrinsicMorphProgress
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.components.measureIntrinsicCoverSize
import com.mica.music.ui.components.measurePlayerCoverFitOriginal
import com.mica.music.ui.components.NowPlayingTrackWipe
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.components.textLineHeightDp
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCoverDisplayMode
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.NowPlayingBackground
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops
import com.mica.music.ui.theme.rememberPlayerScreenAppearance
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import com.mica.music.ui.system.homeStatusBarTopPadding
import kotlinx.coroutines.delay

private val LyricsFocusMiniCoverSize = 56.dp

/** 原样比例底衬显隐淡入淡出时长（慢于常规短动效） */
private const val CoverLetterboxFadeMs = 480

private const val LyricsCoverMorphEndFocus = 0.05f
/** 沉浸 progress 归零前仍视为过渡中（用于保留冻结快照与底栏 lerp） */
private const val ImmersiveProgressEpsilon = 0.001f

private data class ImmersiveTitleSlideSnapshot(
    val titleOffsetFromLowerTop: Dp,
    val titleSlideEnd: Dp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    onOpenEqualizer: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val song = playerController.currentSong
    val context = LocalContext.current
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    var queueSheetOpen by remember { mutableStateOf(false) }

    NowPlayingTrackWipe(
        targetSong = song,
        consumeSkipDirection = { playerController.consumeTrackSkipDirection() },
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) { activeSong ->
    LaunchedEffect(Unit) {
        while (true) {
            playerController.syncPosition()
            delay(50)
        }
    }

    val lowerBackground = uiSettings.playerLowerBackground
    val appearance = rememberPlayerScreenAppearance(activeSong, lowerBackground)
    val coverColor = appearance.coverColor
    val contentColors = appearance.contentColors
    val hifiBadgeColors = appearance.hifiBadgeColors
    val artworkJunction = appearance.artworkJunction
    var lyricsExpanded by remember { mutableStateOf(false) }
    val useCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow()
    val immersiveLower = uiSettings.playerImmersiveLower
    val seekState = rememberPlaybackSeekState(playerController)
    val motionEnabled = rememberMicaMotionEnabled()

    LaunchedEffect(activeSong.id) {
        lyricsExpanded = false
    }

    val lyricsFocus by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
        label = "lyricsFocus",
    )
    val immersiveProgress by animateFloatAsState(
        targetValue = if (immersiveLower) 1f else 0f,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs),
        label = "immersiveProgress",
    )
    BackHandler(enabled = lyricsExpanded) {
        lyricsExpanded = false
    }
    BackHandler(enabled = !lyricsExpanded) {
        onClose()
    }

    val showCoverEdgeProgress = useCoverEdgeProgress && lyricsFocus < 0.05f

    if (queueSheetOpen) {
        PlaybackQueueSheet(
            queue = playerController.songQueue,
            currentIndex = playerController.currentIndex,
            isPlaying = playerController.isPlaying,
            onDismiss = { queueSheetOpen = false },
            onPlayAt = { playerController.playSong(it) },
            onMove = { from, to -> playerController.moveInQueue(from, to) },
            onRemove = { index -> playerController.removeFromQueue(index) },
        )
    }

    var coverZoneStop by remember { mutableFloatStateOf(0.4f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight

        NowPlayingBackground(
            coverColor = coverColor,
            albumArtUri = activeSong.albumArtUri,
            mode = lowerBackground,
            coverZoneStop = coverZoneStop,
            modifier = Modifier.matchParentSize(),
        )
        Column(Modifier.fillMaxSize()) {
            val statusBarTop = homeStatusBarTopPadding(hideStatusBar = uiSettings.hideStatusBar)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val screenWidth = maxWidth
                val miniHeaderHeight = statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm
                val coverSize = lerpDp(screenWidth, LyricsFocusMiniCoverSize, lyricsFocus)
                val coverStartPadding = lerpDp(0.dp, HifiSpacing.lg, lyricsFocus)
                val coverTopPadding = lerpDp(0.dp, statusBarTop, lyricsFocus)
                val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
                    lyricsFocus < 0.5f

                var coverAspectRatio by remember(activeSong.albumArtUri) { mutableFloatStateOf(1f) }
                val fitOriginal = LocalCoverDisplayMode.current == CoverDisplayMode.FIT_ORIGINAL
                val morphToIntrinsic = if (fitOriginal) {
                    coverIntrinsicMorphProgress(lyricsFocus, LyricsCoverMorphEndFocus)
                } else {
                    0f
                }
                val (intrinsicW, intrinsicH) = if (fitOriginal) {
                    measurePlayerCoverFitOriginal(
                        coverAspectRatio,
                        screenWidth,
                        screenHeight,
                    )
                } else {
                    measureIntrinsicCoverSize(
                        coverAspectRatio,
                        screenWidth,
                        coverSize,
                    )
                }
                val coverWidth = lerpDp(coverSize, intrinsicW, morphToIntrinsic)
                val coverHeight = lerpDp(coverSize, intrinsicH, morphToIntrinsic)
                val coverBlockHeight = lerpDp(
                    coverHeight + coverTopPadding,
                    miniHeaderHeight,
                    lyricsFocus,
                )
                SideEffect {
                    coverZoneStop = (coverBlockHeight.value / screenHeight.value)
                        .coerceIn(0.12f, PlayerCoverMaxScreenFraction)
                }

                val letterboxAlpha = rememberFitOriginalLetterboxAlpha(
                    fitOriginal = fitOriginal,
                    lyricsExpanded = lyricsExpanded,
                    lyricsFocus = lyricsFocus,
                    motionEnabled = motionEnabled,
                )

                Box(
                    Modifier
                        .height(coverBlockHeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                ) {
                    // 单一 SongCover，避免 lyricsFocus≈0.01 切换分支时重挂载 + Coil crossfade 闪一下
                    Box(
                        modifier = Modifier
                            .padding(start = coverStartPadding, top = coverTopPadding)
                            .size(coverWidth, coverHeight)
                            .then(
                                if (lyricsExpanded) {
                                    Modifier.clickable { lyricsExpanded = false }
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
                        if (showCoverEdgeProgress) {
                            CoverEdgeProgressBar(
                                value = seekState.sliderValue,
                                onValueChange = seekState.onValueChange,
                                onValueChangeFinished = seekState.onValueChangeFinished,
                                valueRange = seekState.valueRange,
                                progressColor = contentColors.primary,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                    if (lyricsFocus > 0.01f) {
                        LyricsFocusHeaderOverlay(
                            title = activeSong.title,
                            artist = activeSong.artist,
                            coverWidth = coverWidth,
                            coverHeight = coverHeight,
                            coverStartPadding = coverStartPadding,
                            coverTopPadding = coverTopPadding,
                            colors = contentColors,
                            focusAlpha = lyricsFocus,
                            onCloseLyrics = { lyricsExpanded = false },
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val panelHeight = maxHeight
                val immersiveInTransition =
                    immersiveLower || immersiveProgress > ImmersiveProgressEpsilon
                val lowerLayoutFrozen = lyricsExpanded || immersiveInTransition
                val metaLayoutModeLive = if (showCoverEdgeProgress) {
                    PlayerLowerLayoutMode.COVER_EDGE_PROGRESS
                } else {
                    PlayerLowerLayoutMode.STANDARD
                }
                val metaLayoutMode = if (!lowerLayoutFrozen) {
                    metaLayoutModeLive
                } else {
                    remember(panelHeight, useCoverEdgeProgress, lowerLayoutFrozen) {
                        metaLayoutModeLive
                    }
                }
                val lowerLayout = rememberPlayerLowerLayout(
                    panelHeight = panelHeight,
                    layoutMode = metaLayoutMode,
                    immersiveProgress = immersiveProgress,
                    useCoverEdgeProgressSetting = useCoverEdgeProgress,
                    lyricsFocus = lyricsFocus,
                    lyricsCoverMorphEndFocus = LyricsCoverMorphEndFocus,
                    freezeSpacing = lowerLayoutFrozen,
                )
                val spacing = lowerLayout.spacing
                val chromeVisibleHeight = lowerLayout.chromeHeight

                // 先冻结再算：进沉浸时快照 titleOffset / 终点，动画只跟 immersiveProgress
                val density = LocalDensity.current
                val typography = MicaTheme.typography
                val infoLine = with(density) { typography.monoMd.lineHeight.toDp() }
                val titleLine = with(density) { typography.titleLg.lineHeight.toDp() }
                val immersiveLayoutSnapshot = remember(immersiveInTransition) {
                    if (!immersiveInTransition) {
                        null
                    } else {
                        val titleOffset = spacing.afterCover + infoLine + spacing.afterInfo
                        val metaEnd = panelHeight - lowerLayout.chromeHeightAtFullImmersive
                        ImmersiveTitleSlideSnapshot(
                            titleOffsetFromLowerTop = titleOffset,
                            titleSlideEnd = maxOf(
                                0.dp,
                                metaEnd / 2 - titleOffset - titleLine,
                            ),
                        )
                    }
                }
                val titleSlideDown = lerpDp(
                    0.dp,
                    immersiveLayoutSnapshot?.titleSlideEnd ?: 0.dp,
                    immersiveProgress,
                )

                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(
                                if (immersiveLower) {
                                    Modifier.combinedClickable(
                                        onClick = { playerController.togglePlay() },
                                        onLongClick = { uiSettings.togglePlayerImmersiveLower() },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        // 仅歌词聚焦时淡出元数据区；沉浸模式由 SongTitleSection 内交叉淡入淡出，
                        // 勿对整个 Column 乘 (1 - immersiveProgress)，否则标题行会被一并隐藏。
                        val metaFade = 1f - lyricsFocus
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = metaFade },
                        ) {
                            Spacer(Modifier.height(spacing.afterCover))
                            Box(
                                Modifier.graphicsLayer {
                                    alpha = metaFade * (1f - immersiveProgress)
                                    translationY = -immersiveProgress * 12f
                                },
                            ) {
                                HiFiBadgeSection(
                                    song = activeSong,
                                    colors = if (lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW) {
                                        hifiBadgeColors
                                    } else {
                                        contentColors
                                    },
                                )
                            }
                            Spacer(Modifier.height(spacing.afterInfo))
                            SongTitleSection(
                                title = activeSong.title,
                                artist = activeSong.artist,
                                album = activeSong.album,
                                isBuffering = playerController.isBuffering,
                                playbackError = playerController.playbackError,
                                colors = contentColors,
                                immersiveProgress = immersiveProgress,
                                modifier = Modifier.graphicsLayer {
                                    translationY = titleSlideDown.toPx()
                                },
                                onLongPress = if (!immersiveLower) {
                                    { uiSettings.togglePlayerImmersiveLower() }
                                } else {
                                    null
                                },
                            )
                            Spacer(Modifier.height(spacing.afterSubtitle))
                            if (!immersiveLower) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                ) {
                                    LyricsSection(
                                        lyrics = activeSong.lyrics,
                                        positionMs = playerController.positionMs,
                                        colors = contentColors,
                                        lineSlots = spacing.lyricLineSlots,
                                        onClick = { lyricsExpanded = true },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(spacing.beforePlaybackChrome))
                        }
                        if (lyricsFocus > 0.01f) {
                            ExpandedLyricsPanel(
                                lyrics = activeSong.lyrics,
                                positionMs = playerController.positionMs,
                                colors = contentColors,
                                onLineClick = { timeMs ->
                                    if (timeMs >= 0) {
                                        playerController.seekToMs(timeMs)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = lyricsFocus },
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(chromeVisibleHeight)
                            .clipToBounds()
                            .graphicsLayer { alpha = 1f - immersiveProgress },
                    ) {
                        LyricsPlaybackChrome(
                            playerController = playerController,
                            colors = contentColors,
                            seekState = seekState,
                            spacing = spacing,
                            useCoverEdgeProgress = useCoverEdgeProgress,
                            lyricsFocus = lyricsFocus,
                            onOpenQueue = { queueSheetOpen = true },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun rememberFitOriginalLetterboxAlpha(
    fitOriginal: Boolean,
    lyricsExpanded: Boolean,
    lyricsFocus: Float,
    motionEnabled: Boolean,
): Float {
    if (!fitOriginal) return 0f

    val settledOnLyrics =
        lyricsExpanded && lyricsFocus >= 1f - ImmersiveProgressEpsilon

    val target = if (settledOnLyrics) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = MicaMotion.tweenFloat(motionEnabled, CoverLetterboxFadeMs),
        label = "coverLetterboxAlpha",
    )
    return animated
}

@Composable
private fun LyricsPlaybackChrome(
    playerController: PlayerController,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    spacing: PlayerLowerPanelSpacing,
    useCoverEdgeProgress: Boolean,
    lyricsFocus: Float,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showProgressInChrome = !useCoverEdgeProgress || lyricsFocus >= LyricsCoverMorphEndFocus

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (showProgressInChrome) {
            val progressAlpha = if (useCoverEdgeProgress && lyricsFocus < LyricsCoverMorphEndFocus) {
                0f
            } else {
                1f
            }
            Box(Modifier.graphicsLayer { alpha = progressAlpha }) {
                // 标准进度条始终存在；勿用双 AnimatedVisibility 切换，否则会闪现
                LyricsChromeProgressBlock(seekState, colors, afterProgress = spacing.afterProgress)
            }
        }
        PlayerPlaybackControlsSection(
            playerController = playerController,
            colors = colors,
            onOpenQueue = onOpenQueue,
            modifier = Modifier.padding(horizontal = HifiSpacing.lg),
        )
        Spacer(Modifier.height(spacing.afterControls))
    }
}

@Composable
private fun LyricsChromeProgressBlock(
    seekState: PlaybackSeekState,
    colors: PlayerContentColors,
    afterProgress: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        PlayerProgressBarSection(seekState, colors)
        Spacer(Modifier.height(afterProgress))
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
                contentDescription = "收起歌词",
                tint = colors.primary,
            )
        }
    }
}

@Composable
private fun HiFiBadgeSection(
    song: com.mica.music.data.Song,
    colors: PlayerContentColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        HiFiInfoRow(
            format = song.metadata.containerName,
            quality = song.sampleRateLabel,
            bitrate = song.bitrateLabel,
            modifier = Modifier.weight(1f),
            textColor = colors.tertiary,
        )
        if (song.isHiRes) {
            HiResIndicator()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongTitleSection(
    title: String,
    artist: String,
    album: String,
    isBuffering: Boolean,
    playbackError: String?,
    colors: PlayerContentColors,
    immersiveProgress: Float,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val titleStyle = MicaTheme.typography.titleLg
    val titleLineHeight = textLineHeightDp(titleStyle)
    val artistLine = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "缓冲中…"
        else -> ArtistNames.normalizeDisplay(artist)
    }
    val fullSubtitle = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "缓冲中…"
        else -> "${ArtistNames.normalizeDisplay(artist)} · $album"
    }
    val isError = !playbackError.isNullOrBlank()
    val subtitleColor = if (isError) MicaTheme.colors.like else colors.secondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongPress,
                    )
                } else if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        MarqueeTitleText(
            text = title,
            style = titleStyle,
            color = colors.primary,
            lineHeight = titleLineHeight,
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = fullSubtitle,
                style = MicaTheme.typography.bodyMd,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - immersiveProgress },
            )
            Text(
                text = artistLine,
                style = MicaTheme.typography.bodyMd,
                color = subtitleColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = immersiveProgress },
            )
        }
    }
}

@Composable
private fun LyricsSection(
    lyrics: List<LyricLine>,
    positionMs: Int,
    colors: PlayerContentColors,
    lineSlots: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = LyricsSync.indexForPosition(lyrics, positionMs)
    val compact = lineSlots <= 1
    val (currentStyle, otherStyle) = rememberPlayerPanelLyricStyles()
    val colorSpec = rememberLyricLineColorSpec()
    val motionEnabled = rememberMicaMotionEnabled()
    val indexTransition = lyricTransitionSpec(motionEnabled)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clickable(onClick = onClick),
    ) {
        LyricsAreaEdgeFade(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    lyrics.isEmpty() -> {
                        Text(
                            text = "暂无歌词",
                            style = otherStyle,
                            color = colors.tertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HifiSpacing.lg),
                        )
                    }
                    else -> AnimatedContent(
                        targetState = index,
                        transitionSpec = { indexTransition },
                        label = "playerLyricsIndex",
                    ) { animatedIndex ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HifiSpacing.lg),
                        ) {
                            when {
                                compact -> {
                                    val lineText = when {
                                        animatedIndex in lyrics.indices -> lyrics[animatedIndex].text
                                        else -> lyrics.firstOrNull()?.text ?: "暂无歌词"
                                    }
                                    LyricLineBlock(
                                        text = lineText,
                                        isCurrent = animatedIndex in lyrics.indices,
                                        colors = colors,
                                        currentStyle = currentStyle,
                                        otherStyle = otherStyle,
                                        colorSpec = colorSpec,
                                    )
                                }
                                animatedIndex < 0 -> {
                                    Text(
                                        text = lyrics.firstOrNull()?.text ?: "暂无歌词",
                                        style = otherStyle,
                                        color = colors.tertiary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                else -> {
                                    LyricLineBlock(
                                        text = lyrics.getOrNull(animatedIndex - 1)?.text,
                                        isCurrent = false,
                                        colors = colors,
                                        currentStyle = currentStyle,
                                        otherStyle = otherStyle,
                                        colorSpec = colorSpec,
                                    )
                                    LyricLineBlock(
                                        text = lyrics[animatedIndex].text,
                                        isCurrent = true,
                                        colors = colors,
                                        currentStyle = currentStyle,
                                        otherStyle = otherStyle,
                                        colorSpec = colorSpec,
                                    )
                                    LyricLineBlock(
                                        text = lyrics.getOrNull(animatedIndex + 1)?.text,
                                        isCurrent = false,
                                        colors = colors,
                                        currentStyle = currentStyle,
                                        otherStyle = otherStyle,
                                        colorSpec = colorSpec,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
