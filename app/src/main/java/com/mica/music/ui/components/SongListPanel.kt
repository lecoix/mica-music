package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.ArtistNames
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.ui.theme.MicaTheme

@Composable
fun SongListPanel(
    songs: List<Song>,
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    emptyMessage: String,
    listState: LazyListState? = null,
    fastScrollSortField: SongSortField? = library.sortField,
    fastScrollSortDirection: SortDirection = library.sortDirection,
    fastScrollLabels: List<String>? = fastScrollSortField?.let { songs.fastScrollLabels(it) },
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val lazyListState = listState ?: rememberLazyListState()

    if (songs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    if (fastScrollLabels == null) {
        SongRows(
            songs = songs,
            playerController = playerController,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            listState = lazyListState,
            listBottomPadding = listBottomPadding,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        AlphabetFastScroller(
            labels = fastScrollLabels,
            listState = lazyListState,
            descending = fastScrollSortDirection == SortDirection.DESC,
            modifier = modifier.fillMaxSize(),
        ) {
            SongRows(
                songs = songs,
                playerController = playerController,
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                listState = lazyListState,
                listBottomPadding = listBottomPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SongRows(
    songs: List<Song>,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)?,
    listState: LazyListState,
    listBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        items(songs, key = { it.id }) { song ->
            val isCurrent = playerController.currentSong?.id == song.id
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && playerController.isPlaying,
                onClick = { onSongClick(song.id) },
                onLongClick = onSongOpenMenu?.let { open -> { open(song) } },
            )
        }
    }
}

private fun List<Song>.fastScrollLabels(field: SongSortField): List<String>? = when (field) {
    SongSortField.TITLE -> map { it.title }
    SongSortField.FILE_NAME -> map { it.fileName }
    SongSortField.ALBUM -> map { it.album }
    SongSortField.ARTIST -> map { ArtistNames.primary(it.artist) }
    SongSortField.FOLDER -> map { it.folderPath.ifBlank { it.filePath } }
    SongSortField.SIZE,
    SongSortField.YEAR,
    SongSortField.PLAY_COUNT,
    SongSortField.LAST_PLAYED,
    SongSortField.DURATION,
    SongSortField.DATE_MODIFIED,
    SongSortField.DATE_ADDED,
    SongSortField.CUSTOM,
    -> null
}
