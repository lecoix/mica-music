package com.mica.music.ui.screens.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.mica.music.data.PlaylistStore
import com.mica.music.data.AlbumBrowseKey
import com.mica.music.data.Song
import com.mica.music.ui.components.AddToPlaylistSheet
import com.mica.music.ui.components.MicaConfirmDialog
import com.mica.music.ui.components.MicaTextInputDialog
import com.mica.music.ui.components.SongActionMenuSheet
import com.mica.music.ui.components.SongMenuAction

@Composable
internal fun HomeOverlays(
    overlay: HomeOverlayState,
    playlistStore: PlaylistStore,
    resolveSong: (String) -> Song?,
    onDismissActionMenu: () -> Unit,
    onSongMenuAction: (SongMenuAction, Song) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (AlbumBrowseKey) -> Unit,
    onDismissAddToPlaylist: () -> Unit,
    onAddToPlaylistCreated: (String) -> Unit,
    onDismissCreatePlaylist: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onConfirmDeleteSong: (Song) -> Unit,
    onDismissDeleteSong: () -> Unit,
    onConfirmDeletePlaylist: (String) -> Unit,
    onDismissDeletePlaylist: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    overlay.actionMenuSong?.let { song ->
        SongActionMenuSheet(
            song = song,
            onDismiss = onDismissActionMenu,
            fromPlaylistId = overlay.actionMenuPlaylistId,
            onAction = { onSongMenuAction(it, song) },
            onArtistClick = { artistName ->
                onArtistClick(artistName)
                onDismissActionMenu()
            },
            onAlbumClick = { albumKey ->
                onAlbumClick(albumKey)
                onDismissActionMenu()
            },
            landscape = landscape,
        )
    }

    overlay.addToPlaylistSongs?.let { songs ->
        AddToPlaylistSheet(
            songs = songs,
            playlistStore = playlistStore,
            addAsCustomOrder = overlay.addToPlaylistAsCustomOrder,
            resolveSong = resolveSong,
            onDismiss = onDismissAddToPlaylist,
            onCreated = onAddToPlaylistCreated,
            landscape = landscape,
        )
    }

    MicaTextInputDialog(
        visible = overlay.showCreatePlaylistDialog,
        title = "新建歌单",
        hint = "歌单名称",
        confirmLabel = "创建",
        onConfirm = onCreatePlaylist,
        onDismiss = onDismissCreatePlaylist,
    )

    overlay.pendingDeleteSong?.let { song ->
        MicaConfirmDialog(
            visible = true,
            title = "删除音乐",
            message = "确定从设备删除「${song.title}」？此操作不可撤销。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { onConfirmDeleteSong(song) },
            onDismiss = onDismissDeleteSong,
        )
    }

    overlay.pendingDeletePlaylistId?.let { playlistId ->
        val playlistName = playlistStore.playlistById(playlistId)?.name ?: "歌单"
        MicaConfirmDialog(
            visible = true,
            title = "删除歌单",
            message = "确定删除歌单「$playlistName」？歌单内的歌曲不会被删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { onConfirmDeletePlaylist(playlistId) },
            onDismiss = onDismissDeletePlaylist,
        )
    }
}
