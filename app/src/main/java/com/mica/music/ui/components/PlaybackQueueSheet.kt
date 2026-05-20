package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    onDismiss: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    val haptic = LocalHapticFeedback.current

    val items = remember { mutableStateListOf<Song>() }
    LaunchedEffect(queue) {
        if (items.toList() != queue) {
            items.clear()
            items.addAll(queue)
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = items.removeAt(from.index)
        items.add(to.index, moved)
        onMove(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(bottom = HifiSpacing.xxl),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.lg)
                    .padding(bottom = HifiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "播放列表",
                        style = MicaTheme.typography.titleMd,
                        color = MicaTheme.colors.textPrimary,
                    )
                    Text(
                        text = "长按右侧把手拖动排序",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textTertiary,
                    )
                }
                Text(
                    text = "${items.size} 首",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )
            }

            if (items.isEmpty()) {
                Text(
                    text = "队列为空",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = HifiSpacing.xl),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(state = lazyListState) {
                    itemsIndexed(items, key = { _, song -> song.id }) { index, song ->
                        ReorderableItem(reorderState, key = song.id) { isDragging ->
                            QueueSongRow(
                                index = index,
                                song = song,
                                isCurrent = index == currentIndex,
                                isPlaying = index == currentIndex && isPlaying,
                                isDragging = isDragging,
                                onClick = {
                                    onPlayAt(index)
                                    onDismiss()
                                },
                                onRemove = {
                                    items.removeAt(index)
                                    onRemove(index)
                                },
                                dragModifier = Modifier.draggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSongRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(HifiSize.listRowHeight)
                .clickable(onClick = onClick)
                .padding(start = HifiSpacing.lg),
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) {
                    PlayingIndicator(modifier = Modifier.size(14.dp))
                } else {
                    Text(
                        text = "${index + 1}",
                        style = MicaTheme.typography.monoSm,
                        color = if (isCurrent) {
                            MicaTheme.colors.accent
                        } else {
                            MicaTheme.colors.textTertiary
                        },
                    )
                }
            }

            SongCover(
                albumArtUri = song.albumArtUri,
                fallbackColor = song.coverColor,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = HifiSpacing.sm)
                    .size(HifiSize.coverXs),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MicaTheme.typography.bodyLg,
                    color = if (isCurrent) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${ArtistNames.normalizeDisplay(song.artist)} · ${song.durationLabel}",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "从队列移除",
                    tint = MicaTheme.colors.textTertiary,
                    modifier = Modifier.size(HifiSize.iconMd),
                )
            }

            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = "拖动排序",
                tint = if (isDragging) {
                    MicaTheme.colors.accent
                } else {
                    MicaTheme.colors.textTertiary
                },
                modifier = dragModifier
                    .padding(end = HifiSpacing.md)
                    .size(HifiSize.iconLg),
            )
        }
        HorizontalDivider(
            thickness = HifiSize.dividerHairline,
            color = MicaTheme.colors.divider,
            modifier = Modifier.padding(start = HifiSpacing.lg),
        )
    }
}
