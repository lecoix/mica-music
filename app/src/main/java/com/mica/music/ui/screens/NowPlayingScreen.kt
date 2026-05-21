package com.mica.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.mica.music.ui.components.MarqueeTitleText
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.components.PlayerPlaybackControlsSection
import com.mica.music.ui.components.PlayerProgressBarSection
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.components.coverIntrinsicMorphProgress
import com.mica.music.ui.components.measureIntrinsicCoverSize
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
import com.mica.music.util.shareSong
import kotlinx.coroutines.delay

private val LyricsFocusMiniCoverSize = 56.dp

/** 进入歌词聚焦时，常规进度条 + 控制区整体下沉量（两种进度模式一致） */
private val LyricsChromeSink = 20.dp

private const val LyricsCoverMorphEndFocus = 0.05f
private const val LyricsFocusDurationMs = 400

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
        NowPlayingTopBar(
            onClose = onClose,
            onShare = { shareSong(context, activeSong) },
            onOpenEqualizer = onOpenEqualizer,
            colors = contentColors,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(Modifier.fillMaxSize()) {
            val statusBarTop = homeStatusBarTopPadding()
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
                val (intrinsicW, intrinsicH) = measureIntrinsicCoverSize(
                    coverAspectRatio,
                    screenWidth,
                    coverSize,
                )
                val coverWidth = lerpDp(coverSize, intrinsicW, morphToIntrinsic)
                val coverHeight = lerpDp(coverSize, intrinsicH, morphToIntrinsic)
                val coverBlockHeight = lerpDp(
                    coverHeight + coverTopPadding,
                    miniHeaderHeight,
                    lyricsFocus,
                )
                SideEffect {
                    coverZoneStop = (coverBlockHeight.value / screenHeight.value)
                        .coerceIn(0.12f, 0.62f)
                }

                val letterboxAlpha by animateFloatAsState(
                    targetValue = if (fitOriginal && !lyricsExpanded && lyricsFocus <= 0.001f) 1f else 0f,
                    animationSpec = if (fitOriginal && !lyricsExpanded && lyricsFocus <= 0.001f) {
                        tween(
                            durationMillis = 160,
                            delayMillis = LyricsFocusDurationMs,
                            easing = FastOutSlowInEasing,
                        )
                    } else {
                        tween(durationMillis = 0)
                    },
                    label = "coverLetterboxAlpha",
                )

                Box(Modifier.height(coverBlockHeight).fillMaxWidth()) {
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
                        LyricsFocusHeaderTexts(
                            title = activeSong.title,
                            artist = activeSong.artist,
                            colors = contentColors,
                            coverStart = coverStartPadding,
                            coverTop = coverTopPadding,
                            coverSize = LyricsFocusMiniCoverSize,
                            focusAlpha = lyricsFocus,
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
                // 固定位移，避免底部控制条收起后 panel 变高导致 translationY 二次跳动
                val titleSlideDown = 72.dp
                val metaLayoutMode = if (useCoverEdgeProgress && !lyricsExpanded) {
                    PlayerLowerLayoutMode.COVER_EDGE_PROGRESS
                } else {
                    PlayerLowerLayoutMode.STANDARD
                }
                val spacing = rememberPlayerLowerPanelSpacing(panelHeight, layoutMode = metaLayoutMode)

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
                        if (!lyricsExpanded) {
                            val metaFade = 1f - immersiveProgress
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.height(spacing.afterCover))
                                Box(
                                    Modifier.graphicsLayer {
                                        alpha = metaFade
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
                                val titleOffsetY = lerpDp(0.dp, titleSlideDown, immersiveProgress)
                                SongTitleSection(
                                    title = activeSong.title,
                                    artist = activeSong.artist,
                                    album = activeSong.album,
                                    isBuffering = playerController.isBuffering,
                                    playbackError = playerController.playbackError,
                                    colors = contentColors,
                                    immersiveProgress = immersiveProgress,
                                    modifier = Modifier.graphicsLayer {
                                        translationY = titleOffsetY.toPx()
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
                                            .fillMaxWidth()
                                            .graphicsLayer { alpha = metaFade },
                                    ) {
                                        LyricsSection(
                                            lyrics = activeSong.lyrics,
                                            positionMs = playerController.positionMs,
                                            colors = contentColors,
                                            onClick = { lyricsExpanded = true },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(spacing.beforePlaybackChrome))
                            }
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
                }
            }

            // 与 immersiveProgress 同步收起高度，避免 AnimatedVisibility 导致中间区突然变高
            val chromeBlockHeight = 132.dp
            val chromeVisibleHeight = lerpDp(chromeBlockHeight, 0.dp, immersiveProgress)
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
                    useCoverEdgeProgress = useCoverEdgeProgress,
                    lyricsExpanded = lyricsExpanded,
                    onOpenQueue = { queueSheetOpen = true },
                )
            }
        }
    }
    }
}

@Composable
private fun NowPlayingTopBar(
    onClose: () -> Unit,
    onShare: () -> Unit,
    onOpenEqualizer: () -> Unit,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = homeStatusBarTopPadding()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarTop, start = HifiSpacing.sm, end = HifiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(HifiSize.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "收起",
                tint = colors.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onShare, modifier = Modifier.size(HifiSize.touchTarget)) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = "分享",
                tint = colors.secondary,
            )
        }
        IconButton(onClick = onOpenEqualizer, modifier = Modifier.size(HifiSize.touchTarget)) {
            Icon(
                imageVector = Icons.Outlined.Equalizer,
                contentDescription = "均衡器",
                tint = colors.secondary,
            )
        }
    }
}

@Composable
private fun LyricsPlaybackChrome(
    playerController: PlayerController,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    useCoverEdgeProgress: Boolean,
    lyricsExpanded: Boolean,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val afterControls = HifiSize.iconLg + HifiSize.touchTarget / 2 +
        if (useCoverEdgeProgress && !lyricsExpanded) HifiSpacing.sm else 0.dp

    val chromeSinkOffset by animateDpAsState(
        targetValue = if (lyricsExpanded) LyricsChromeSink else 0.dp,
        animationSpec = MicaMotion.tweenDp(motionEnabled, MicaMotion.DurationLongMs),
        label = "lyricsChromeSink",
    )

    val chromeFade = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationLongMs)
    val chromeSize = MicaMotion.tweenIntSize(motionEnabled, MicaMotion.DurationLongMs)
    val progressEnter = expandVertically(animationSpec = chromeSize) + fadeIn(animationSpec = chromeFade)
    val progressExit = shrinkVertically(animationSpec = chromeSize) + fadeOut(animationSpec = chromeFade)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = chromeSinkOffset),
    ) {
        if (useCoverEdgeProgress) {
            AnimatedVisibility(
                visible = lyricsExpanded,
                enter = progressEnter,
                exit = progressExit,
            ) {
                LyricsChromeProgressBlock(seekState, colors)
            }
        } else {
            // 标准进度条始终存在，随整列 offset 下沉；勿用双 AnimatedVisibility 切换，否则会闪现
            LyricsChromeProgressBlock(seekState, colors)
        }
        PlayerPlaybackControlsSection(
            playerController = playerController,
            colors = colors,
            onOpenQueue = onOpenQueue,
            modifier = Modifier.padding(horizontal = HifiSpacing.lg),
        )
        Spacer(Modifier.height(afterControls))
    }
}

@Composable
private fun LyricsChromeProgressBlock(
    seekState: PlaybackSeekState,
    colors: PlayerContentColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        PlayerProgressBarSection(seekState, colors)
        Spacer(Modifier.height(HifiSize.iconLg / 2))
    }
}

@Composable
private fun LyricsFocusHeaderTexts(
    title: String,
    artist: String,
    colors: PlayerContentColors,
    coverStart: Dp,
    coverTop: Dp,
    coverSize: Dp,
    focusAlpha: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = coverTop,
                start = coverStart + coverSize + HifiSpacing.md,
                end = HifiSpacing.lg,
            )
            .height(coverSize)
            .graphicsLayer { alpha = focusAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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

private const val LYRIC_LINE_PLACEHOLDER = "\u00A0"
private val LyricLineColorAnimSpec = tween<Color>(durationMillis = 400)

@Composable
private fun LyricsSection(
    lyrics: List<LyricLine>,
    positionMs: Int,
    colors: PlayerContentColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = LyricsSync.indexForPosition(lyrics, positionMs)
    val otherStyle = MicaTheme.typography.bodySm
    val currentStyle = MicaTheme.typography.bodyMd.copy(fontWeight = FontWeight.SemiBold)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg),
        ) {
            when {
                lyrics.isEmpty() -> {
                    Text(
                        text = "暂无歌词",
                        style = otherStyle,
                        color = colors.tertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                index < 0 -> {
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
                    LyricLineText(
                        text = lyrics.getOrNull(index - 1)?.text,
                        style = otherStyle,
                        isCurrent = false,
                        colors = colors,
                    )
                    LyricLineText(
                        text = lyrics[index].text,
                        style = currentStyle,
                        isCurrent = true,
                        colors = colors,
                    )
                    LyricLineText(
                        text = lyrics.getOrNull(index + 1)?.text,
                        style = otherStyle,
                        isCurrent = false,
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricLineText(
    text: String?,
    style: TextStyle,
    isCurrent: Boolean,
    colors: PlayerContentColors,
) {
    val color by animateColorAsState(
        targetValue = if (isCurrent) colors.primary else colors.tertiary,
        animationSpec = LyricLineColorAnimSpec,
        label = "lyricLineColor",
    )
    Text(
        text = text?.takeIf { it.isNotBlank() } ?: LYRIC_LINE_PLACEHOLDER,
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}
