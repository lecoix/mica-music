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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.mica.music.data.AppUiSettings
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
import com.mica.music.ui.components.PlaybackSeekState
import com.mica.music.ui.components.rememberPlaybackSeekState
import com.mica.music.ui.components.textLineHeightDp
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.NowPlayingBackground
import com.mica.music.ui.theme.PlayerContentColors
import com.mica.music.ui.theme.artworkEdgeFadeStops
import com.mica.music.ui.theme.rememberPlayerScreenAppearance
import com.mica.music.ui.system.homeStatusBarTopPadding
import kotlinx.coroutines.delay

private val LyricsFocusMiniCoverSize = 56.dp

/** 进入歌词聚焦时，常规进度条 + 控制区整体下沉量（两种进度模式一致） */
private val LyricsChromeSink = 20.dp

private val LyricsChromeAnimSpec = tween<Float>(durationMillis = 400, easing = FastOutSlowInEasing)
private val LyricsChromeAnimSpecDp = tween<Dp>(durationMillis = 400, easing = FastOutSlowInEasing)

@Composable
fun NowPlayingScreen(
    playerController: PlayerController,
    uiSettings: AppUiSettings,
    onClose: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val song = playerController.currentSong
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    LaunchedEffect(Unit) {
        while (true) {
            playerController.syncPosition()
            delay(50)
        }
    }

    val lowerBackground = uiSettings.playerLowerBackground
    val appearance = rememberPlayerScreenAppearance(song, lowerBackground)
    val coverColor = appearance.coverColor
    val contentColors = appearance.contentColors
    val hifiBadgeColors = appearance.hifiBadgeColors
    val artworkJunction = appearance.artworkJunction
    var queueSheetOpen by remember { mutableStateOf(false) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    val useCoverEdgeProgress = uiSettings.useCoverEdgeProgressNow()
    val immersiveLower = uiSettings.playerImmersiveLower
    val seekState = rememberPlaybackSeekState(playerController)

    LaunchedEffect(song.id) {
        lyricsExpanded = false
    }

    val lyricsFocus by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "lyricsFocus",
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        NowPlayingBackground(
            coverColor = coverColor,
            albumArtUri = song.albumArtUri,
            mode = lowerBackground,
            modifier = Modifier.matchParentSize(),
        )
        Column(Modifier.fillMaxSize()) {
            val statusBarTop = homeStatusBarTopPadding()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val screenWidth = maxWidth
                val miniHeaderHeight = statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm
                val coverBlockHeight = lerp(screenWidth, miniHeaderHeight, lyricsFocus)
                val coverSize = lerp(screenWidth, LyricsFocusMiniCoverSize, lyricsFocus)
                val coverStartPadding = lerp(0.dp, HifiSpacing.lg, lyricsFocus)
                val coverTopPadding = lerp(0.dp, statusBarTop, lyricsFocus)
                val coverEdgeFade = lowerBackground == PlayerLowerBackgroundMode.ARTWORK_GRADIENT &&
                    lyricsFocus < 0.5f

                Box(Modifier.height(coverBlockHeight).fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .padding(start = coverStartPadding, top = coverTopPadding)
                            .size(coverSize)
                            .then(
                                if (lyricsExpanded) {
                                    Modifier.clickable { lyricsExpanded = false }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        SongCover(
                            albumArtUri = song.albumArtUri,
                            fallbackColor = coverColor,
                            contentDescription = song.album,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize(),
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
                            title = song.title,
                            artist = song.artist,
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
                val metaAlpha = 1f - lyricsFocus
                val panelHeight = maxHeight
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
                            .fillMaxWidth(),
                    ) {
                        if (!immersiveLower && metaAlpha > 0.01f) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = metaAlpha },
                            ) {
                                Spacer(Modifier.height(spacing.afterCover))
                                HiFiBadgeSection(
                                    song = song,
                                    colors = if (lowerBackground == PlayerLowerBackgroundMode.COVER_GLOW) {
                                        hifiBadgeColors
                                    } else {
                                        contentColors
                                    },
                                )
                                Spacer(Modifier.height(spacing.afterInfo))
                                SongTitleSection(
                                    title = song.title,
                                    artist = song.artist,
                                    album = song.album,
                                    isBuffering = playerController.isBuffering,
                                    playbackError = playerController.playbackError,
                                    colors = contentColors,
                                    onLongPress = { uiSettings.togglePlayerImmersiveLower() },
                                )
                                Spacer(Modifier.height(spacing.afterSubtitle))
                                LyricsSection(
                                    lyrics = song.lyrics,
                                    positionMs = playerController.positionMs,
                                    colors = contentColors,
                                    onClick = { lyricsExpanded = true },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.height(spacing.beforePlaybackChrome))
                            }
                        }
                        if (lyricsFocus > 0.01f) {
                            ExpandedLyricsPanel(
                                lyrics = song.lyrics,
                                positionMs = playerController.positionMs,
                                colors = contentColors,
                                onLineClick = { timeMs ->
                                    if (timeMs >= 0) {
                                        playerController.seek(timeMs / 1000)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = lyricsFocus },
                            )
                        }
                        if (immersiveLower) {
                            ImmersiveLowerPanel(
                                title = song.title,
                                artist = song.artist,
                                isBuffering = playerController.isBuffering,
                                playbackError = playerController.playbackError,
                                colors = contentColors,
                                onTogglePlay = { playerController.togglePlay() },
                                onToggleImmersive = { uiSettings.togglePlayerImmersiveLower() },
                            )
                        }
                    }
                }
            }

            // 固定在屏幕底部：封面缩小时中间区变高，但进度条/按钮不应跟着上移
            if (!immersiveLower) {
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

@Composable
private fun LyricsPlaybackChrome(
    playerController: PlayerController,
    colors: PlayerContentColors,
    seekState: PlaybackSeekState,
    useCoverEdgeProgress: Boolean,
    lyricsExpanded: Boolean,
    onOpenQueue: () -> Unit,
) {
    val afterControls = HifiSize.iconLg + HifiSize.touchTarget / 2 +
        if (useCoverEdgeProgress && !lyricsExpanded) HifiSpacing.sm else 0.dp

    val chromeSinkOffset by animateDpAsState(
        targetValue = if (lyricsExpanded) LyricsChromeSink else 0.dp,
        animationSpec = LyricsChromeAnimSpecDp,
        label = "lyricsChromeSink",
    )

    val progressEnter = expandVertically(
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = LyricsChromeAnimSpec)

    val progressExit = shrinkVertically(
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
    ) + fadeOut(animationSpec = LyricsChromeAnimSpec)

    Column(
        modifier = Modifier
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
private fun ImmersiveLowerPanel(
    title: String,
    artist: String,
    isBuffering: Boolean,
    playbackError: String?,
    colors: PlayerContentColors,
    onTogglePlay: () -> Unit,
    onToggleImmersive: () -> Unit,
) {
    val titleStyle = MicaTheme.typography.titleLg
    val titleLineHeight = textLineHeightDp(titleStyle)
    val artistLine = when {
        !playbackError.isNullOrBlank() -> playbackError
        isBuffering -> "缓冲中…"
        else -> ArtistNames.normalizeDisplay(artist)
    }
    val artistColor = if (!playbackError.isNullOrBlank()) MicaTheme.colors.like else colors.secondary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = HifiSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onTogglePlay,
                    onLongClick = onToggleImmersive,
                )
                .padding(vertical = HifiSpacing.xl),
        ) {
            MarqueeTitleText(
                text = title,
                style = titleStyle,
                color = colors.primary,
                lineHeight = titleLineHeight,
            )
            Text(
                text = artistLine,
                style = MicaTheme.typography.bodyMd,
                color = artistColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
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
    onLongPress: (() -> Unit)? = null,
) {
    val titleStyle = MicaTheme.typography.titleLg
    val titleLineHeight = textLineHeightDp(titleStyle)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    )
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
        val subtitle = when {
            !playbackError.isNullOrBlank() -> playbackError
            isBuffering -> "缓冲中…"
            else -> "${ArtistNames.normalizeDisplay(artist)} · $album"
        }
        val isError = !playbackError.isNullOrBlank()
        Text(
            text = subtitle,
            style = MicaTheme.typography.bodyMd,
            color = if (isError) MicaTheme.colors.like else colors.secondary,
            textAlign = TextAlign.Center,
        )
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
