package com.mica.music.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
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
    modifier: Modifier = Modifier,
) {
    val lazyListState = listState ?: rememberLazyListState()
  // favoritesRevision 参与重组，收藏图标即时刷新
    val favoritesRevision = library.favoritesRevision
    @Suppress("UNUSED_VARIABLE")
    val revisionKey = favoritesRevision

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

    LazyColumn(state = lazyListState, modifier = modifier.fillMaxSize()) {
        items(songs, key = { it.id }) { song ->
            val isCurrent = playerController.currentSong?.id == song.id
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && playerController.isPlaying,
                isFavorite = library.isFavorite(song.id),
                onClick = { onSongClick(song.id) },
                onToggleFavorite = { library.toggleFavorite(song.id) },
                onLongClick = onSongOpenMenu?.let { open -> { open(song) } },
            )
        }
    }
}
