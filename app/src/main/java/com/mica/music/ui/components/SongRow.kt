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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.R
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongTrailingInfo
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
    trackNumber: String? = null,
    trailingLabel: String? = null,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    showCover: Boolean = true,
    compact: Boolean = false,
    subtitleOverride: String? = null,
    infoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    modifier: Modifier = Modifier,
) {
    val titleStyle = if (compact) MicaTheme.typography.bodyMd else MicaTheme.typography.bodyLg
    val rowHeight = if (compact) 48.dp else HifiSize.listRowHeight
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (selectionMode) {
                        "${if (isSelected) "取消选择" else "选择"} ${song.title}，${ArtistNames.normalizeDisplay(song.artist)}"
                    } else {
                        "播放 ${song.title}，${ArtistNames.normalizeDisplay(song.artist)}"
                    }
                    selected = if (selectionMode) isSelected else isCurrent
                    role = Role.Button
                }
                .then(
                    if (selectionMode) {
                        Modifier.clickable(onClick = onClick)
                    } else if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier.clickable(onClick = onClick)
                    },
                )
                // 与 LibraryStatsRow 右侧（排序/重载）同一内容边距
                .padding(end = HifiSpacing.lg),
        ) {
            Box(
                Modifier
                    .width(HifiSize.accentBarWidth)
                    .fillMaxHeight()
                    .background(if (isCurrent) MicaTheme.colors.accent else Color.Transparent)
            )

            Spacer(Modifier.width(HifiSpacing.md))

            trackNumber?.let { number ->
                Text(
                    text = number,
                    style = MicaTheme.typography.monoSm,
                    color = if (isCurrent) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.width(28.dp),
                )
                Spacer(Modifier.width(HifiSpacing.sm))
            }

            if (showCover) {
                SongCover(
                    albumArtUri = song.albumArtUri,
                    fallbackColor = song.coverColor,
                    contentDescription = song.title,
                    noCoverPlaceholderResId = R.drawable.no_cover_placeholder_small,
                    modifier = Modifier.size(HifiSize.coverSm),
                )
                Spacer(Modifier.width(HifiSpacing.md))
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title,
                        style = titleStyle,
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
                if (!compact || subtitleOverride != null) {
                    val meta = subtitleOverride ?: songSubtitle(song, infoVisibility)
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MicaTheme.typography.bodySm,
                            color = MicaTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.width(HifiSpacing.xs))

            if (selectionMode) {
                SongSelectionCheckbox(selected = isSelected)
            } else {
                (trailingLabel ?: songTrailingLabel(song, infoVisibility.trailingInfo))?.let { label ->
                    Text(
                        text = label,
                        style = MicaTheme.typography.monoSm,
                        color = MicaTheme.colors.textTertiary,
                    )
                }
            }
        }
    }
}

internal fun songTrailingLabel(song: Song, mode: SongTrailingInfo): String? = when (mode) {
    SongTrailingInfo.PLAY_COUNT -> song.playCount.toString()
    SongTrailingInfo.FORMAT -> song.formatLabel
    SongTrailingInfo.DURATION -> song.durationLabel
    SongTrailingInfo.NONE -> null
}

internal fun songSubtitle(song: Song, visibility: SongListInfoVisibility): String =
    listOfNotNull(
        ArtistNames.normalizeDisplay(song.artist).takeIf { visibility.showSongArtist },
        song.album.takeIf { visibility.showSongAlbum },
        "${song.playCount} 次播放".takeIf { visibility.showSongPlayCount && song.playCount > 0 },
        song.durationLabel.takeIf { visibility.showSongDuration && song.durationSec > 0 },
    ).joinToString(" · ")
