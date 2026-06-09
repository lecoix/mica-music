package com.mica.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.ArtistNames
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.LocalMicaBackgroundPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.bottomThemeColor
import com.mica.music.ui.theme.micaFloatingCardBottomEdge

private val FloatingCoverSize = 48.dp
private val FloatingCardHeight = 64.dp
private val FloatingCardBottomEdgeWidth = 2.dp
/** 与列表单行一致：行高 + 底部分割线，便于与第 9 首下方分割线重合。 */
private val AudiophileBarHeight = HifiSize.listRowHeight + HifiSize.dividerHairline

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
            FloatingCardHeight + floatGap + safeBottom
        MiniPlayerStyle.AUDIOPHILE ->
            AudiophileBarHeight + safeBottom
    }
}

/** 歌曲列表 [LazyColumn] 底部 contentPadding；极简底栏顶线对齐末行分割线时不额外留白。 */
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
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit = {},
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
    when (style) {
        MiniPlayerStyle.FLOATING_ISLAND -> FloatingIslandMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onLongPress = onLongPress,
            coverAlpha = coverAlpha,
            onCoverBoundsChanged = onCoverBoundsChanged,
            bottomInset = bottomInset,
            modifier = modifier,
        )
        MiniPlayerStyle.AUDIOPHILE -> AudiophileMiniPlayer(
            song = song,
            isPlaying = isPlaying,
            onPlayPause = onPlayPause,
            onExpand = onExpand,
            onLongPress = onLongPress,
            onCoverBoundsChanged = onCoverBoundsChanged,
            bottomInset = bottomInset,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingIslandMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit,
    coverAlpha: Float,
    onCoverBoundsChanged: (Rect?) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MicaTheme.colors
    val pagePreset = LocalMicaBackgroundPreset.current
    val cardSurface = pagePreset.bottomThemeColor(colors.isDark)
    val bottomEdge = micaFloatingCardBottomEdge(cardSurface, colors.isDark)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.xl)
                .height(FloatingCardHeight)
                .background(cardSurface)
                .combinedClickable(
                    onClick = onExpand,
                    onLongClick = onLongPress,
                ),
        ) {
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
                    modifier = Modifier
                        .size(FloatingCoverSize)
                        .onGloballyPositioned { onCoverBoundsChanged(it.boundsInRoot()) }
                        .graphicsLayer { alpha = coverAlpha },
                )
                Spacer(Modifier.width(HifiSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MicaTheme.typography.bodyLg,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ArtistNames.normalizeDisplay(song.artist),
                        style = MicaTheme.typography.bodySm,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SharpPlayPauseButton(
                    isPlaying = isPlaying,
                    onToggle = onPlayPause,
                    size = HifiSize.iconLg,
                    color = colors.textPrimary,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FloatingCardBottomEdgeWidth)
                    .align(Alignment.BottomCenter)
                    .background(bottomEdge),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudiophileMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onLongPress: () -> Unit,
    onCoverBoundsChanged: (Rect?) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MicaTheme.colors
    val barSurface = LocalMicaBackgroundPreset.current.bottomThemeColor(colors.isDark)
    LaunchedEffect(Unit) {
        onCoverBoundsChanged(null)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(AudiophileBarHeight)
                .background(barSurface)
                .combinedClickable(
                    onClick = onExpand,
                    onLongClick = onLongPress,
                ),
        ) {
            HorizontalDivider(
                thickness = HifiSize.dividerHairline,
                color = colors.divider,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HifiSize.listRowHeight)
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
                    Text(
                        text = song.title,
                        style = MicaTheme.typography.bodyMd,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ArtistNames.normalizeDisplay(song.artist),
                        style = MicaTheme.typography.bodySm,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MiniPlayerSpectrumBars(
                    isPlaying = isPlaying,
                    height = 38.dp,
                )
            }
        }
        if (bottomInset > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomInset)
                    .background(barSurface),
            )
        }
    }
}
