package com.mica.music.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LibraryBrowse
import com.mica.music.data.LibrarySearchIndex
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.preferences.LibraryZoomPage
import java.util.Locale

@Composable
fun LibrarySearchPanel(
    query: String,
    library: MusicLibrary,
    remoteSongs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongClick: (List<Song>, String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    val localeTag = locale.toLanguageTag()
    val remoteSearchIndex = remember(remoteSongs, localeTag) {
        RemoteSongSearchIndex(remoteSongs, locale)
    }
    val localResults = library.searchSongs(query)
    val remoteResults = remember(remoteSearchIndex, query) {
        remoteSearchIndex.search(query)
    }
    val results = remember(localResults, remoteResults) {
        mergeLibrarySearchResults(localResults, remoteResults)
    }
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
            onQueueSongClick(results, songId)
        },
        onSongOpenMenu = onSongOpenMenu,
        emptyMessage = emptyMessage,
        listBottomPadding = listBottomPadding,
        zoomPage = LibraryZoomPage.SEARCH,
        modifier = modifier.fillMaxSize(),
    )
}

internal class RemoteSongSearchIndex(
    songs: List<Song>,
    private val locale: Locale,
) {
    private val index: LibrarySearchIndex = LibraryBrowse.searchIndex(songs, locale)

    fun search(query: String): List<Song> {
        val queryLower = query.trim().lowercase(locale)
        if (queryLower.isEmpty()) return emptyList()
        return LibraryBrowse.search(index, queryLower)
    }
}

internal fun mergeLibrarySearchResults(
    localResults: List<Song>,
    remoteResults: List<Song>,
): List<Song> = buildList {
    addAll(localResults)
    addAll(remoteResults)
}.distinctBy { it.id }
