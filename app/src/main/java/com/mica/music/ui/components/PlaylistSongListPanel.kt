package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalHapticFeedback
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistSongListPanel(
    songs: List<Song>,
    customOrder: Boolean,
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onMoveSong: (Int, Int) -> Unit,
    emptyMessage: String,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
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
            playerController = playerController,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            emptyMessage = emptyMessage,
            listBottomPadding = listBottomPadding,
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
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = items.removeAt(from.index)
        items.add(to.index, moved)
        onMoveSong(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        itemsIndexed(items, key = { _, song -> song.id }) { index, song ->
            ReorderableItem(reorderState, key = song.id) { isDragging ->
                val isCurrent = playerController.currentSong?.id == song.id
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongRow(
                        song = song,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && playerController.isPlaying,
                        onClick = { onSongClick(song.id) },
                        onLongClick = { onSongOpenMenu(song) },
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
                        modifier = Modifier
                            .draggableHandle()
                            .padding(end = HifiSpacing.md)
                            .size(HifiSize.iconMd),
                    )
                }
            }
        }
    }
}
