package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistSongListPanel(
    songs: List<Song>,
    customOrder: Boolean,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onMoveSong: (Int, Int) -> Unit,
    emptyMessage: String,
    sortField: SongSortField = library.sortField,
    sortDirection: SortDirection = library.sortDirection,
    listBottomPadding: Dp = 0.dp,
    infoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val columns = songListColumnsFor(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    if (!customOrder) {
        SongListPanel(
            songs = songs,
            library = library,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            emptyMessage = emptyMessage,
            fastScrollSortField = sortField,
            fastScrollSortDirection = sortDirection,
            listBottomPadding = listBottomPadding,
            infoVisibility = infoVisibility,
            modifier = modifier,
        )
        return
    }

    val haptic = LocalHapticFeedback.current
    val items = remember { mutableStateListOf<Song>() }
    LaunchedEffect(songs) {
        if (items.toList() != songs) {
            items.clear()
            items.addAll(songs)
        }
    }

    val lazyListState = rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
    val reorderListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = items.removeAt(from.index)
        items.add(to.index, moved)
        onMoveSong(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val reorderGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        val moved = items.removeAt(from.index)
        items.add(to.index, moved)
        onMoveSong(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val songRow: @Composable (
        index: Int,
        song: Song,
        isDragging: Boolean,
        dragModifier: Modifier,
    ) -> Unit = { _, song, isDragging, dragModifier ->
        val isCurrent = currentSongId == song.id
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                onClick = { onSongClick(song.id) },
                onLongClick = { onSongOpenMenu(song) },
                infoVisibility = infoVisibility,
                modifier = Modifier.weight(1f),
            )
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
                    .size(HifiSize.iconMd),
            )
        }
    }

    if (columns > 1) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = lazyGridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            gridItemsIndexed(items, key = { _, song -> song.id }) { index, song ->
                ReorderableItem(reorderGridState, key = song.id) { isDragging ->
                    songRow(index, song, isDragging, Modifier.draggableHandle())
                }
            }
        }
    } else {
        LazyColumn(
            state = lazyListState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            itemsIndexed(items, key = { _, song -> song.id }) { index, song ->
                ReorderableItem(reorderListState, key = song.id) { isDragging ->
                    songRow(index, song, isDragging, Modifier.draggableHandle())
                }
            }
        }
    }
}
