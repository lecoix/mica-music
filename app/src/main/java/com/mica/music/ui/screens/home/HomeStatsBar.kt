package com.mica.music.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsSession
import com.mica.music.media.NotificationLyrics
import com.mica.music.ui.components.NarrowBarSoftKaraokeLyric
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun SongMultiSelectStatsRow(
    selectedCount: Int,
    canSelectSongs: Boolean,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val statsRowMinHeight = HifiSize.iconMd + HifiSpacing.xs * 2

    Row(
        modifier = Modifier
            .padding(horizontal = HifiSpacing.lg)
            .heightIn(min = statsRowMinHeight)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount 首",
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "全选",
            style = MicaTheme.typography.bodyMd,
            color = if (canSelectSongs) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
            modifier = Modifier
                .clickable(enabled = canSelectSongs, onClick = onSelectAll)
                .padding(HifiSpacing.xs),
        )
        Text(
            text = "反选",
            style = MicaTheme.typography.bodyMd,
            color = if (canSelectSongs) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
            modifier = Modifier
                .clickable(enabled = canSelectSongs, onClick = onInvertSelection)
                .padding(HifiSpacing.xs),
        )
        Text(
            text = "清空",
            style = MicaTheme.typography.bodyMd,
            color = if (selectedCount > 0) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
            modifier = Modifier
                .clickable(enabled = selectedCount > 0, onClick = onClearSelection)
                .padding(HifiSpacing.xs),
        )
        Text(
            text = "加入歌单",
            style = MicaTheme.typography.bodyMd,
            color = if (selectedCount > 0) {
                MicaTheme.colors.accent
            } else {
                MicaTheme.colors.textTertiary
            },
            modifier = Modifier
                .clickable(enabled = selectedCount > 0, onClick = onAddToPlaylist)
                .padding(HifiSpacing.xs),
        )
    }
}

internal fun infoRowLyricText(
    enabled: Boolean,
    isPlaying: Boolean,
    lyricsSession: LyricsSession?,
    positionMs: Int,
    lyricSplitEnabled: Boolean,
    lyricsBilingualDisplayMode: LyricsBilingualDisplayMode,
): String? {
    if (!enabled || !isPlaying || lyricsSession == null) return null
    val lyricIndex = NotificationLyrics.lyricIndexForPosition(lyricsSession, positionMs)
    if (lyricIndex < 0) return null
    return NotificationLyrics.lyricLineText(
        lyrics = lyricsSession.lyrics,
        index = lyricIndex,
        display = NotificationLyrics.DisplayOptions(
            splitEnabled = lyricSplitEnabled,
            bilingualMode = lyricsBilingualDisplayMode,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryStatsRow(
    model: LibraryStatsBarModel,
    lyricText: String? = null,
    karaokeLine: LyricLine? = null,
    nextLyricLineTimeMs: Int? = null,
    positionMs: Int = 0,
    isPlaying: Boolean = false,
    onSortClick: () -> Unit,
    onRescan: () -> Unit,
    onDeletePlaylist: () -> Unit,
) {
    val lineText = model.segments.joinToString(" · ")
    val showKaraoke = karaokeLine != null && karaokeLine.cues.isNotEmpty()
    val showLyric = lyricText != null || showKaraoke

    val statsRowMinHeight = HifiSize.iconMd + HifiSpacing.xs * 2

    Column(modifier = Modifier.padding(horizontal = HifiSpacing.lg)) {
        Row(
            modifier = Modifier.heightIn(min = statsRowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showKaraoke) {
                NarrowBarSoftKaraokeLyric(
                    line = karaokeLine,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    nextLineTimeMs = nextLyricLineTimeMs,
                    filledColor = MicaTheme.colors.textSecondary,
                    unfilledColor = MicaTheme.colors.textTertiary,
                    textStyle = MicaTheme.typography.monoMd,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = lyricText ?: lineText,
                    style = if (!showLyric) MicaTheme.typography.monoSm else MicaTheme.typography.monoMd,
                    color = MicaTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee(),
                )
            }
            if (model.showSortAction) {
                Icon(
                    imageVector = Icons.Outlined.Sort,
                    contentDescription = "排序",
                    tint = MicaTheme.colors.textTertiary,
                    modifier = Modifier
                        .clickable(onClick = onSortClick)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
            if (model.showRescanAction) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "扫描",
                    tint = MicaTheme.colors.textTertiary,
                    modifier = Modifier
                        .clickable(enabled = !model.isScanning, onClick = onRescan)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
            if (model.showDeletePlaylistAction) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除歌单",
                    tint = HifiPalette.LikeRed.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clickable(onClick = onDeletePlaylist)
                        .padding(HifiSpacing.xs)
                        .size(HifiSize.iconMd),
                )
            }
        }
        if (!model.scanError.isNullOrBlank()) {
            Spacer(Modifier.height(HifiSpacing.xs))
            Text(
                text = "扫描失败：${model.scanError}",
                style = MicaTheme.typography.caption,
                color = HifiPalette.LikeRed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
