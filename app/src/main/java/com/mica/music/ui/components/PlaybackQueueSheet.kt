package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.R
import com.mica.music.data.ArtistNames
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
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
    landscape: Boolean = false,
    listState: LazyListState? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f
    val haptic = LocalHapticFeedback.current

    val lazyListState = listState ?: rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0),
    )
    val lazyGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0),
    )
    var observedCurrentIndex by remember { mutableIntStateOf(currentIndex) }
    var previewFromIndex by remember { mutableIntStateOf(-1) }
    var previewToIndex by remember { mutableIntStateOf(-1) }
    val previewProjection = if (previewFromIndex >= 0 && previewToIndex >= 0) {
        QueueMoveProjection(previewFromIndex, previewToIndex)
    } else {
        null
    }
    val reorderSession = remember { QueueReorderDragSession() }
    val reorderListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (previewFromIndex < 0) previewFromIndex = from.index
        previewToIndex = to.index
        reorderSession.recordPreviewMove(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val reorderGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        if (previewFromIndex < 0) previewFromIndex = from.index
        previewToIndex = to.index
        reorderSession.recordPreviewMove(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val isReordering by remember(landscape) {
        derivedStateOf {
            if (landscape) {
                reorderGridState.isAnyItemDragging
            } else {
                reorderListState.isAnyItemDragging
            }
        }
    }

    LaunchedEffect(isReordering) {
        if (!isReordering) {
            val commit = reorderSession.finish()
            commit?.let { onMove(it.fromIndex, it.toIndex) }
            previewFromIndex = -1
            previewToIndex = -1
        }
    }
    LaunchedEffect(currentIndex, queue.size) {
        if (
            observedCurrentIndex != currentIndex &&
            !isReordering &&
            currentIndex in queue.indices
        ) {
            val targetIndex = (currentIndex - 2).coerceAtLeast(0)
            if (landscape) {
                lazyGridState.scrollToItem(targetIndex)
            } else {
                lazyListState.scrollToItem(targetIndex)
            }
        }
        observedCurrentIndex = currentIndex
    }

    val scrimColor = Color.Black.copy(alpha = if (isDark) 0.42f else 0.28f)
    val sheetContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (landscape) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxSheetHeight),
                )
                .padding(bottom = HifiSpacing.xxl),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (landscape) 72.dp else 64.dp)
                    .padding(horizontal = HifiSpacing.lg),
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
                    text = "${queue.size} 首",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )
                if (landscape) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(start = HifiSpacing.sm)
                            .size(HifiSize.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭播放队列",
                            tint = MicaTheme.colors.textSecondary,
                        )
                    }
                }
            }

            if (landscape) {
                HorizontalDivider(
                    thickness = HifiSize.dividerHairline,
                    color = MicaTheme.colors.divider,
                )
            }

            if (queue.isEmpty()) {
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
                if (landscape) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = lazyGridState,
                        modifier = Modifier.weight(1f),
                    ) {
                        items(
                            count = queue.size,
                            key = { visualIndex: Int ->
                                val sourceIndex =
                                    previewProjection?.sourceIndexAt(visualIndex) ?: visualIndex
                                queue[sourceIndex].id
                            },
                        ) { index: Int ->
                            val sourceIndex = previewProjection?.sourceIndexAt(index) ?: index
                            val song = queue[sourceIndex]
                            ReorderableItem(reorderGridState, key = song.id) { isDragging ->
                                QueueSongRow(
                                    index = index,
                                    song = song,
                                    isCurrent = sourceIndex == currentIndex,
                                    isPlaying = sourceIndex == currentIndex && isPlaying,
                                    isDragging = isDragging,
                                    onClick = {
                                        onPlayAt(sourceIndex)
                                        onDismiss()
                                    },
                                    onRemove = { onRemove(sourceIndex) },
                                    dragModifier = Modifier.draggableHandle(),
                                    landscape = true,
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                    ) {
                        items(
                            count = queue.size,
                            key = { visualIndex ->
                                val sourceIndex =
                                    previewProjection?.sourceIndexAt(visualIndex) ?: visualIndex
                                queue[sourceIndex].id
                            },
                        ) { index ->
                        val sourceIndex = previewProjection?.sourceIndexAt(index) ?: index
                        val song = queue[sourceIndex]
                            ReorderableItem(reorderListState, key = song.id) { isDragging ->
                            QueueSongRow(
                                index = index,
                                song = song,
                                isCurrent = sourceIndex == currentIndex,
                                isPlaying = sourceIndex == currentIndex && isPlaying,
                                isDragging = isDragging,
                                onClick = {
                                    onPlayAt(sourceIndex)
                                    onDismiss()
                                },
                                onRemove = {
                                    onRemove(sourceIndex)
                                },
                                dragModifier = Modifier.draggableHandle(),
                                    landscape = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (landscape) {
        PlayerSidePanel(
            onDismiss = onDismiss,
            containerColor = sheetBackground,
            scrimColor = scrimColor,
            widthFraction = 0.82f,
            minPanelWidth = 520.dp,
            maxPanelWidth = 900.dp,
            paneTitle = "播放队列",
            content = sheetContent,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = sheetBackground,
            scrimColor = scrimColor,
        ) {
            sheetContent()
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
    landscape: Boolean,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (landscape) 60.dp else HifiSize.listRowHeight)
                .clickable(onClick = onClick)
                .padding(start = if (landscape) HifiSpacing.sm else HifiSpacing.lg),
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
                noCoverPlaceholderResId = R.drawable.no_cover_placeholder_small,
                decodeTarget = CoverDecodeTarget.forCompactCover(),
                modifier = Modifier
                    .padding(horizontal = if (landscape) HifiSpacing.xs else HifiSpacing.sm)
                    .size(if (landscape) 36.dp else HifiSize.coverXs),
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
                modifier = Modifier.size(if (landscape) 36.dp else HifiSize.touchTarget),
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
                    .padding(end = if (landscape) HifiSpacing.sm else HifiSpacing.md)
                    .size(if (landscape) HifiSize.iconMd else HifiSize.iconLg),
            )
        }
        HorizontalDivider(
            thickness = HifiSize.dividerHairline,
            color = MicaTheme.colors.divider,
            modifier = Modifier.padding(start = HifiSpacing.lg),
        )
    }
}
