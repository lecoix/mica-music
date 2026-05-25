package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.ui.components.PlaylistSongListPanel
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun HomePlaylistContent(
    playlistId: String,
    playlistStore: PlaylistStore,
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: (Song) -> Unit,
    onMoveSong: (Int, Int) -> Unit,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val playlist = playlistStore.playlists.find { it.id == playlistId }
    if (playlist == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "歌单不存在",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    val songs = remember(
        playlist,
        library.songs,
        playlistStore.revision,
        playlist.sortField,
        playlist.sortDirection,
    ) {
        playlistStore.songsForPlaylist(playlistId) { library.songById(it) }
    }

    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "歌单为空\n长按歌曲可添加到此歌单",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    PlaylistSongListPanel(
        songs = songs,
        customOrder = playlist.sortField == SongSortField.CUSTOM,
        library = library,
        playerController = playerController,
        onSongClick = onSongClick,
        onSongOpenMenu = onSongOpenMenu,
        onMoveSong = onMoveSong,
        emptyMessage = "歌单为空",
        listBottomPadding = listBottomPadding,
        modifier = modifier,
    )
}
