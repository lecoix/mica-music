package com.mica.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

/**
 * 列表行：
 * - isCurrent：是否为"当前选中"的歌，决定紫色左侧条 + 标题着色
 * - isPlaying：是否真实在出声，决定动态均衡器图标的显示与动画
 *
 * 这两个状态分开是为了暂停场景：暂停时仍然是"当前曲"，但不应该有动态条。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(HifiSize.listRowHeight)
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier.clickable(onClick = onClick)
                    },
                ),
        ) {
            Box(
                Modifier
                    .width(HifiSize.accentBarWidth)
                    .fillMaxHeight()
                    .background(if (isCurrent) MicaTheme.colors.accent else Color.Transparent)
            )

            Spacer(Modifier.width(HifiSpacing.md))

            SongCover(
                albumArtUri = song.albumArtUri,
                fallbackColor = song.coverColor,
                contentDescription = song.title,
                modifier = Modifier.size(HifiSize.coverSm),
            )

            Spacer(Modifier.width(HifiSpacing.md))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title,
                        style = MicaTheme.typography.bodyLg,
                        color = if (isCurrent) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isPlaying) {
                        Spacer(Modifier.width(HifiSpacing.sm))
                        PlayingIndicator(modifier = Modifier.size(12.dp))
                    }
                }
                val meta = buildString {
                    append(ArtistNames.normalizeDisplay(song.artist))
                    append(" · ")
                    append(song.album)
                    if (song.playCount > 0) {
                        append(" · ")
                        append("${song.playCount} 次播放")
                    }
                }
                Text(
                    text = meta,
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(HifiSpacing.xs))

            Text(
                text = song.formatLabel,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MicaTheme.colors.like else MicaTheme.colors.textTertiary,
                    modifier = Modifier.size(HifiSize.iconMd),
                )
            }
        }

        HorizontalDivider(
            thickness = HifiSize.dividerHairline,
            color = MicaTheme.colors.divider,
        )
    }
}
