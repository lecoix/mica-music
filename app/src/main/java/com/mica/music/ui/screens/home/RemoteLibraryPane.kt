package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.mica.music.data.Song
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun RemoteLibraryPane(
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongClick: (List<Song>, String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    listState: LazyListState,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    listBottomPadding: Dp,
    locateSongId: String? = null,
    locateRequestKey: Int = 0,
    onLocateConsumed: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(locateRequestKey, locateSongId) {
        if (locateRequestKey > 0 && locateSongId != null) {
            val targetIndex = songs.indexOfFirst { it.id == locateSongId }
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
            onLocateConsumed(locateRequestKey)
        }
    }

    when {
        songs.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                modifier = Modifier.padding(HifiSpacing.xl),
            ) {
                Text(
                    text = "暂无已同步的远程歌曲",
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                )
                Text(
                    text = "请先在设置 → 曲库 → 远程曲库中添加并同步来源",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = listBottomPadding),
            modifier = modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
            ) { index, song ->
                val isCurrent = currentSongId == song.id
                SongRow(
                    song = song,
                    trackNumber = (index + 1).toString().padStart(2, '0'),
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    selectionMode = selectionMode,
                    isSelected = song.id in selectedSongIds,
                    onClick = {
                        if (selectionMode) {
                            onSelectionToggle(song.id)
                        } else {
                            onQueueSongClick(songs, song.id)
                        }
                    },
                    onLongClick = if (selectionMode) null else ({ onSongOpenMenu(song) }),
                )
            }
        }
    }
}
