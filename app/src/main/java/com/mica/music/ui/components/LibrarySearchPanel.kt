package com.mica.music.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.preferences.LibraryZoomPage

@Composable
fun LibrarySearchPanel(
    query: String,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val results = library.searchSongs(query)
    val emptyMessage = if (query.isBlank()) {
        "输入关键词开始搜索"
    } else {
        "未找到「$query」相关歌曲"
    }

    SongListPanel(
        songs = results,
        library = library,
        currentSongId = currentSongId,
        isPlaying = isPlaying,
        onSongClick = { songId ->
            onQueueSongs(results)
            onSongClick(songId)
        },
        onSongOpenMenu = onSongOpenMenu,
        emptyMessage = emptyMessage,
        listBottomPadding = listBottomPadding,
        zoomPage = LibraryZoomPage.SEARCH,
        modifier = modifier.fillMaxSize(),
    )
}
