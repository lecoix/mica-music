package com.mica.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.R
import com.mica.music.data.ArtistNames
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSession
import com.mica.music.data.MiniPlayerSwipeAction
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.Song
import com.mica.music.media.NotificationLyrics
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalCustomMicaBackground
import com.mica.music.ui.theme.LocalCustomWallpaperPath
import com.mica.music.ui.theme.LocalMicaBackgroundPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.bottomThemeColor
import com.mica.music.ui.theme.FloatingIslandShadowSpread
import com.mica.music.ui.theme.FloatingIslandShadowVerticalExtra
import com.mica.music.ui.theme.MicaCustomWallpaperSlice
import com.mica.music.ui.theme.MicaMaterialBackdrop
import com.mica.music.ui.theme.FloatingIslandShadowHalo

private val FloatingCoverSize = 48.dp
private val FloatingCardHeight = 64.dp
private val MiniPlayerSwipeCommitDistance = 56.dp
private const val MiniPlayerTestTag = "MiniPlayer"

private data class MiniPlayerText(
    val primary: String,
    val secondary: String,
)
/**
 * 云母卡片左右距屏幕边缘相等（竖中线对称）。
 * 与 [SongRow] 列表专辑图横向三等分后，取左段右缘作为卡片左缘。
 */
private val FloatingIslandScreenEdgeInset =
    HifiSize.accentBarWidth + HifiSpacing.md + HifiSize.coverSm / 3
/** 浮岛云母：略低于规范默认，便于目视确认 blur（规范 24/32dp）。 */
private val FloatingIslandBlurLight = 4.dp
private val FloatingIslandBlurDark = 5.dp
/** 在 surface.glass 基础上再降不透明度，提高透视感（规范约 60%/30%）。 */
private const val FloatingIslandGlassAlphaScale = 0.1375f
/** 与列表单行一致的高度。 */
private val AudiophileBarHeight = HifiSize.listRowHeight

/** 迷你栏自内容区底边向上的占用高度（不含列表缓冲）。 */
@Composable
fun miniPlayerOverlayHeight(style: MiniPlayerStyle): Dp {
    val safeBottom = maxOf(
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
        HifiSpacing.xs,
    )
    val floatGap = when (style) {
        MiniPlayerStyle.FLOATING_ISLAND -> HifiSpacing.sm
        MiniPlayerStyle.AUDIOPHILE -> 0.dp
    }
    return when (style) {
        MiniPlayerStyle.FLOATING_ISLAND ->
            FloatingCardHeight + FloatingIslandShadowVerticalExtra + floatGap + safeBottom
        MiniPlayerStyle.AUDIOPHILE ->
            AudiophileBarHeight + safeBottom
    }
}

/** 歌曲列表 [LazyColumn] 底部 contentPadding。 */
@Composable
fun miniPlayerListClearance(style: MiniPlayerStyle): Dp =
    when (style) {
        MiniPlayerStyle.FLOATING_ISLAND ->
            miniPlayerOverlayHeight(style) + HifiSpacing.md
        MiniPlayerStyle.AUDIOPHILE ->
            miniPlayerOverlayHeight(style)
    }

@Composable
fun MiniPlayer(
    style: MiniPlayerStyle,
    song: Song,
    isPlaying: Boolean,
    positionMs: Int = 0,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit = {},
    miniPlayerLyricsEnabled: Boolean = true,
    lyricSplitEnabled: Boolean = true,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    swipeEnabled: Boolean = false,
    leftSwipeAction: MiniPlayerSwipeAction = MiniPlayerSwipeAction.NEXT,
    rightSwipeAction: MiniPlayerSwipeAction = MiniPlayerSwipeAction.PREVIOUS,
    coverAlpha: Float = 1f,
    onCoverBoundsChanged: (Rect?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val safeBottom = maxOf(
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
        HifiSpacing.xs,
    )
    val bottomInset = safeBottom + when (style) {
        MiniPlayerStyle.FLOATING_ISLAND -> HifiSpacing.sm
        MiniPlayerStyle.AUDIOPHILE -> 0.dp
    }
    val swipeModifier = modifier
        .testTag(MiniPlayerTestTag)
        .miniPlayerSwipe(
            enabled = swipeEnabled,
            leftSwipeAction = leftSwipeAction,
            rightSwipeAction = rightSwipeAction,
            onPrevious = onPrevious,
            onNext = onNext,
        )
    val lyricsSession = remember(song.id, song.lyricsDocument) { LyricsSession(song.lyricsDocument) }
    val displayText = miniPlayerText(
        song = song,
        lyricsSession = lyricsSession,
        isPlaying = isPlaying,
        positionMs = positionMs,
        enabled = miniPlayerLyricsEnabled,
        lyricSplitEnabled = lyricSplitEnabled,
        lyricsBilingualDisplayMode = lyricsBilingualDisplayMode,
    )
    when (style) {
        MiniPlayerStyle.FLOATING_ISLAND -> FloatingIslandMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            text = displayText,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onLongPress = onLongPress,
            coverAlpha = coverAlpha,
            onCoverBoundsChanged = onCoverBoundsChanged,
            bottomInset = bottomInset,
            modifier = swipeModifier,
        )
        MiniPlayerStyle.AUDIOPHILE -> AudiophileMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            text = displayText,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onLongPress = onLongPress,
            onCoverBoundsChanged = onCoverBoundsChanged,
            bottomInset = bottomInset,
            modifier = swipeModifier,
        )
    }
}

private fun miniPlayerText(
    song: Song,
    lyricsSession: LyricsSession,
    isPlaying: Boolean,
    positionMs: Int,
    enabled: Boolean,
    lyricSplitEnabled: Boolean,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode,
): MiniPlayerText {
    val artist = ArtistNames.normalizeDisplay(song.artist)
    val fallback = MiniPlayerText(
        primary = song.title,
        secondary = artist,
    )
    if (!enabled || !isPlaying) return fallback

    val lyricIndex = NotificationLyrics.lyricIndexForPosition(lyricsSession, positionMs)
    if (lyricIndex < 0) return fallback

    val lyric = NotificationLyrics.lyricLineText(
        lyrics = lyricsSession.lyrics,
        index = lyricIndex,
        display = NotificationLyrics.DisplayOptions(
            splitEnabled = lyricSplitEnabled,
            bilingualMode = lyricsBilingualDisplayMode,
        ),
    ) ?: return fallback

    return MiniPlayerText(
        primary = lyric,
        secondary = NotificationLyrics.subtitle(song.title, artist),
    )
}

@Composable
private fun Modifier.miniPlayerSwipe(
    enabled: Boolean,
    leftSwipeAction: MiniPlayerSwipeAction,
    rightSwipeAction: MiniPlayerSwipeAction,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    if (!enabled) return this
    val thresholdPx = with(LocalDensity.current) { MiniPlayerSwipeCommitDistance.toPx() }
    var dragPx by remember { mutableFloatStateOf(0f) }

    fun runAction(action: MiniPlayerSwipeAction) {
        when (action) {
            MiniPlayerSwipeAction.PREVIOUS -> onPrevious()
            MiniPlayerSwipeAction.NEXT -> onNext()
        }
    }

    return pointerInput(enabled, leftSwipeAction, rightSwipeAction, onPrevious, onNext, thresholdPx) {
        detectHorizontalDragGestures(
            onDragEnd = {
                when {
                    dragPx <= -thresholdPx -> runAction(leftSwipeAction)
                    dragPx >= thresholdPx -> runAction(rightSwipeAction)
                }
                dragPx = 0f
            },
            onDragCancel = {
                dragPx = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                dragPx += dragAmount
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayerMarqueeText(
    text: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.basicMarquee(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingIslandMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    text: MiniPlayerText,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit,
    coverAlpha: Float,
    onCoverBoundsChanged: (Rect?) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MicaTheme.colors
    val blurRadius = if (colors.isDark) FloatingIslandBlurDark else FloatingIslandBlurLight
    val glassOverlay = colors.surfaceGlass.copy(
        alpha = colors.surfaceGlass.alpha * FloatingIslandGlassAlphaScale,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FloatingIslandScreenEdgeInset)
                .height(FloatingCardHeight + FloatingIslandShadowVerticalExtra)
                .graphicsLayer { clip = false },
        ) {
            val cardModifier = Modifier
                .fillMaxWidth()
                .height(FloatingCardHeight)
                .align(Alignment.TopCenter)
                .offset(y = FloatingIslandShadowSpread)

            FloatingIslandShadowHalo(
                isDark = colors.isDark,
                modifier = cardModifier,
            )
            Box(
                modifier = cardModifier
                    .semantics {
                        contentDescription = "展开播放器：${song.title}"
                        role = Role.Button
                    }
                    .combinedClickable(
                        onClick = onExpand,
                        onLongClick = onLongPress,
                    ),
            ) {
            MicaMaterialBackdrop(
                modifier = Modifier.fillMaxSize(),
                blurRadius = blurRadius,
                overlayColor = glassOverlay,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = HifiSpacing.md, vertical = HifiSpacing.sm),
            ) {
                SongCover(
                    albumArtUri = song.albumArtUri,
                    fallbackColor = song.coverColor,
                    contentDescription = song.title,
                    noCoverPlaceholderResId = R.drawable.no_cover_placeholder_small,
                    modifier = Modifier
                        .size(FloatingCoverSize)
                        .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
                        .graphicsLayer { alpha = coverAlpha },
                )
                Spacer(Modifier.width(HifiSpacing.md))
                Column(Modifier.weight(1f)) {
                    MiniPlayerMarqueeText(
                        text = text.primary,
                        style = MicaTheme.typography.bodyLg,
                        color = colors.textPrimary,
                    )
                    MiniPlayerMarqueeText(
                        text = text.secondary,
                        style = MicaTheme.typography.bodySm,
                        color = colors.textSecondary,
                    )
                }
                SharpPlayPauseButton(
                    isPlaying = isPlaying,
                    onToggle = onPlayPause,
                    size = HifiSize.iconLg,
                    color = colors.textPrimary,
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudiophileMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    text: MiniPlayerText,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit,
    onCoverBoundsChanged: (Rect?) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MicaTheme.colors
    val barSurface = LocalMicaBackgroundPreset.current.bottomThemeColor(
        colors.isDark,
        LocalCustomMicaBackground.current,
    )
    val hasCustomWallpaper = LocalCustomWallpaperPath.current != null
    LaunchedEffect(Unit) {
        onCoverBoundsChanged(null)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AudiophileBarHeight),
        ) {
            AudiophileBarBackdrop(
                barSurface = barSurface,
                isDark = colors.isDark,
                hasCustomWallpaper = hasCustomWallpaper,
                height = AudiophileBarHeight,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "展开播放器：${song.title}"
                        role = Role.Button
                    }
                    .combinedClickable(
                        onClick = onExpand,
                        onLongClick = onLongPress,
                    )
                    .padding(start = HifiSpacing.lg, end = HifiSpacing.xl),
            ) {
                SharpPlayPauseButton(
                    isPlaying = isPlaying,
                    onToggle = onPlayPause,
                    size = HifiSize.iconLg,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(HifiSpacing.md))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = HifiSpacing.sm),
                ) {
                    MiniPlayerMarqueeText(
                        text = text.primary,
                        style = MicaTheme.typography.bodyMd,
                        color = colors.textPrimary,
                    )
                    MiniPlayerMarqueeText(
                        text = text.secondary,
                        style = MicaTheme.typography.bodySm,
                        color = colors.textSecondary,
                    )
                }
                MiniPlayerSpectrumBars(
                    isPlaying = isPlaying,
                    height = 38.dp,
                )
            }
        }
        if (bottomInset > 0.dp) {
            AudiophileBarBackdrop(
                barSurface = barSurface,
                isDark = colors.isDark,
                hasCustomWallpaper = hasCustomWallpaper,
                height = bottomInset,
            )
        }
    }
}

/** 不透明底栏：无壁纸时用主题色，有壁纸时裁切同坐标切片与主背景衔接。 */
@Composable
private fun AudiophileBarBackdrop(
    barSurface: Color,
    isDark: Boolean,
    hasCustomWallpaper: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    if (hasCustomWallpaper) {
        MicaCustomWallpaperSlice(
            isDark = isDark,
            fallbackColor = barSurface,
            modifier = modifier
                .fillMaxWidth()
                .height(height),
        )
        return
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(barSurface),
    )
}
