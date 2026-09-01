package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
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
import com.mica.music.ui.theme.coverColor
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch

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
    embedded: Boolean = false,
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
                    when {
                        embedded -> Modifier.fillMaxSize()
                        landscape -> Modifier.fillMaxHeight()
                        else -> Modifier.heightIn(max = maxSheetHeight)
                    },
                )
                .padding(bottom = if (embedded) 0.dp else HifiSpacing.xxl),
        ) {
            if (embedded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${queue.size} 首",
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.textSecondary,
                    )
                    Text(
                        text = "  ·  拖动左侧把手调整顺序",
                        style = MicaTheme.typography.caption,
                        color = MicaTheme.colors.textTertiary,
                    )
                }
            } else {
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
                        text = "拖动左侧把手调整顺序",
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
            }

            if (landscape && !embedded) {
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
                val queueViewportModifier = if (landscape || embedded) {
                    Modifier.weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                }
                if (landscape) {
                    Box(modifier = queueViewportModifier) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = lazyGridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = HifiSpacing.lg),
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
                                            if (!embedded) onDismiss()
                                        },
                                        onRemove = { onRemove(sourceIndex) },
                                        dragModifier = Modifier.draggableHandle(),
                                        landscape = true,
                                    )
                                }
                            }
                        }
                        val layoutInfo = lazyGridState.layoutInfo
                        PlaybackQueueVerticalScrollbar(
                            itemCount = (queue.size + 1) / 2,
                            visibleItemSizes = layoutInfo.visibleItemsInfo.map { it.size.height },
                            firstVisibleItemIndex = lazyGridState.firstVisibleItemIndex / 2,
                            firstVisibleItemScrollOffset = lazyGridState.firstVisibleItemScrollOffset,
                            viewportHeightPx = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset,
                            onScrollBy = { delta -> lazyGridState.scrollBy(delta) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = HifiSpacing.sm,
                                    bottom = HifiSpacing.sm,
                                    end = HifiSpacing.xs,
                                )
                                .width(32.dp)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Box(modifier = queueViewportModifier) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = HifiSpacing.lg),
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
                                            if (!embedded) onDismiss()
                                        },
                                        onRemove = { onRemove(sourceIndex) },
                                        dragModifier = Modifier.draggableHandle(),
                                        landscape = false,
                                    )
                                }
                            }
                        }
                        val layoutInfo = lazyListState.layoutInfo
                        PlaybackQueueVerticalScrollbar(
                            itemCount = layoutInfo.totalItemsCount,
                            visibleItemSizes = layoutInfo.visibleItemsInfo.map { it.size },
                            firstVisibleItemIndex = lazyListState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = lazyListState.firstVisibleItemScrollOffset,
                            viewportHeightPx = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset,
                            onScrollBy = { delta -> lazyListState.scrollBy(delta) },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(
                                    top = HifiSpacing.sm,
                                    bottom = HifiSpacing.sm,
                                    end = HifiSpacing.xs,
                                )
                                .width(32.dp)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    if (embedded) {
        sheetContent()
    } else if (landscape) {
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
    Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (landscape) 60.dp else HifiSize.listRowHeight)
                .clickable(onClick = onClick)
                .padding(start = if (landscape) HifiSpacing.sm else HifiSpacing.lg),
        ) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = "拖动排序",
                tint = if (isDragging) {
                    MicaTheme.colors.accent
                } else {
                    MicaTheme.colors.textTertiary
                },
                modifier = dragModifier
                    .padding(end = if (landscape) HifiSpacing.xs else HifiSpacing.sm)
                    .size(if (landscape) HifiSize.iconMd else HifiSize.iconLg),
            )

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
        }
}

@Composable
private fun PlaybackQueueVerticalScrollbar(
    itemCount: Int,
    visibleItemSizes: List<Int>,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    viewportHeightPx: Int,
    onScrollBy: suspend (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 0 || visibleItemSizes.isEmpty()) return

    val density = LocalDensity.current
    val minimumThumbHeightPx = with(density) { 24.dp.toPx() }
    val latestItemCount by rememberUpdatedState(itemCount)
    val latestAverageItemSizePx by rememberUpdatedState(visibleItemSizes.average().toFloat())
    val latestViewportHeightPx by rememberUpdatedState(viewportHeightPx)
    val latestFirstVisibleItemIndex by rememberUpdatedState(firstVisibleItemIndex)
    val latestFirstVisibleItemScrollOffset by rememberUpdatedState(firstVisibleItemScrollOffset)
    val latestOnScrollBy by rememberUpdatedState(onScrollBy)
    val scrollScope = rememberCoroutineScope()
    val thumbColor = MicaTheme.colors.accent.copy(alpha = 0.78f)
    Box(
        modifier = modifier.pointerInput(Unit) {
            var grabOffsetPx = 0f
            var previousScrollOffsetPx = 0f
            var dragging = false

            detectDragGestures(
                onDragStart = { offset ->
                    playbackQueueScrollbarMetrics(
                        trackHeightPx = size.height.toFloat(),
                        itemCount = latestItemCount,
                        averageItemSizePx = latestAverageItemSizePx,
                        viewportHeightPx = latestViewportHeightPx,
                        firstVisibleItemIndex = latestFirstVisibleItemIndex,
                        firstVisibleItemScrollOffset = latestFirstVisibleItemScrollOffset,
                        minimumThumbHeightPx = minimumThumbHeightPx,
                    )?.let { metrics ->
                        val distanceFromThumb = offset.y - metrics.thumbTopPx
                        grabOffsetPx = if (
                            distanceFromThumb in 0f..metrics.thumbHeightPx
                        ) {
                            distanceFromThumb
                        } else {
                            metrics.thumbHeightPx / 2f
                        }
                        previousScrollOffsetPx = metrics.currentScrollOffsetPx
                        dragging = true
                    }
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, _ ->
                if (!dragging) return@detectDragGestures

                val metrics = playbackQueueScrollbarMetrics(
                    trackHeightPx = size.height.toFloat(),
                    itemCount = latestItemCount,
                    averageItemSizePx = latestAverageItemSizePx,
                    viewportHeightPx = latestViewportHeightPx,
                    firstVisibleItemIndex = latestFirstVisibleItemIndex,
                    firstVisibleItemScrollOffset = latestFirstVisibleItemScrollOffset,
                    minimumThumbHeightPx = minimumThumbHeightPx,
                ) ?: return@detectDragGestures
                change.consume()

                val thumbTop = (
                    change.position.y - grabOffsetPx
                ).coerceIn(0f, metrics.maxThumbTopPx)
                val targetScrollOffset = if (metrics.maxThumbTopPx <= 0f) {
                    0f
                } else {
                    thumbTop / metrics.maxThumbTopPx * metrics.maxScrollOffsetPx
                }
                val delta = targetScrollOffset - previousScrollOffsetPx
                if (delta != 0f) {
                    scrollScope.launch { latestOnScrollBy(delta) }
                    previousScrollOffsetPx = targetScrollOffset
                }
            }
        },
    ) {
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight(),
        ) {
            val metrics = playbackQueueScrollbarMetrics(
                trackHeightPx = size.height,
                itemCount = itemCount,
                averageItemSizePx = visibleItemSizes.average().toFloat(),
                viewportHeightPx = viewportHeightPx,
                firstVisibleItemIndex = firstVisibleItemIndex,
                firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                minimumThumbHeightPx = minimumThumbHeightPx,
            ) ?: return@Canvas

            drawRect(
                color = thumbColor,
                topLeft = Offset(0f, metrics.thumbTopPx),
                size = Size(size.width, metrics.thumbHeightPx),
            )
        }
    }
}

private data class PlaybackQueueScrollbarMetrics(
    val thumbHeightPx: Float,
    val thumbTopPx: Float,
    val maxThumbTopPx: Float,
    val currentScrollOffsetPx: Float,
    val maxScrollOffsetPx: Float,
)

private fun playbackQueueScrollbarMetrics(
    trackHeightPx: Float,
    itemCount: Int,
    averageItemSizePx: Float,
    viewportHeightPx: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    minimumThumbHeightPx: Float,
): PlaybackQueueScrollbarMetrics? {
    if (trackHeightPx <= 0f || itemCount <= 0 || averageItemSizePx <= 0f) return null

    val viewportHeight = viewportHeightPx.toFloat().coerceAtLeast(1f)
    val contentHeight = averageItemSizePx * itemCount
    if (contentHeight <= viewportHeight) return null

    val thumbHeight = (trackHeightPx * viewportHeight / contentHeight)
        .coerceIn(minimumThumbHeightPx.coerceAtMost(trackHeightPx), trackHeightPx)
    val maxScrollOffset = (contentHeight - viewportHeight).coerceAtLeast(1f)
    val maxThumbTop = (trackHeightPx - thumbHeight).coerceAtLeast(0f)
    val currentScrollOffset = (
        firstVisibleItemIndex * averageItemSizePx + firstVisibleItemScrollOffset
    ).coerceIn(0f, maxScrollOffset)
    val thumbTop = if (maxThumbTop <= 0f) {
        0f
    } else {
        maxThumbTop * (currentScrollOffset / maxScrollOffset)
    }

    return PlaybackQueueScrollbarMetrics(
        thumbHeightPx = thumbHeight,
        thumbTopPx = thumbTop,
        maxThumbTopPx = maxThumbTop,
        currentScrollOffsetPx = currentScrollOffset,
        maxScrollOffsetPx = maxScrollOffset,
    )
}
