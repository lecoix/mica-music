package com.mica.music.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlaylistStore
import com.mica.music.data.Song
import com.mica.music.ui.components.SongMenuAction
import com.mica.music.util.deleteSongEverywhere
import com.mica.music.util.openSongInTagEditor
import com.mica.music.util.shareSong

data class HomeOverlayState(
    val actionMenuSong: Song? = null,
    val actionMenuPlaylistId: String? = null,
    val addToPlaylistSongs: List<Song>? = null,
    val addToPlaylistAsCustomOrder: Boolean = false,
    val pendingDeleteSong: Song? = null,
    val pendingDeletePlaylistId: String? = null,
    val renamePlaylistId: String? = null,
    val showCreatePlaylistDialog: Boolean = false,
)

data class SongMenuActionOutcome(
    val overlay: HomeOverlayState? = null,
    val snackbarMessage: String? = null,
    val openSongDetailId: String? = null,
    val insertPlayNext: Song? = null,
)

data class PlaylistDeleteOutcome(
    val snackbarMessage: String,
    val section: HomeSection? = null,
    val activePlaylistId: String? = null,
)

data class CreatePlaylistOutcome(
    val section: HomeSection? = null,
    val activePlaylistId: String? = null,
    val snackbarMessage: String? = null,
)

class HomeScreenController(
    private val library: MusicLibrary,
    private val playlistStore: PlaylistStore,
) {
    fun openActionMenu(
        overlay: HomeOverlayState,
        song: Song,
        playlistId: String? = null,
    ): HomeOverlayState =
        overlay.copy(
            actionMenuSong = song,
            actionMenuPlaylistId = playlistId,
        )

    fun dismissActionMenu(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(
            actionMenuSong = null,
            actionMenuPlaylistId = null,
        )

    fun handleSongMenuAction(
        context: Context,
        overlay: HomeOverlayState,
        action: SongMenuAction,
        song: Song,
    ): SongMenuActionOutcome {
        val fromPlaylistId = overlay.actionMenuPlaylistId
        return when (action) {
            SongMenuAction.AddToPlaylist -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay).copy(addToPlaylistSongs = listOf(song)),
            )
            SongMenuAction.PlayNext -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay),
                insertPlayNext = if (song.isRemote) song else library.songById(song.id),
            )
            SongMenuAction.Share -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay),
                snackbarMessage = if (shareSong(context, song)) null else "无法分享此歌曲",
            )
            SongMenuAction.EditTags -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay),
                snackbarMessage = if (openSongInTagEditor(context, song)) {
                    null
                } else {
                    "未找到可用的标签编辑应用"
                },
            )
            SongMenuAction.SongInfo -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay),
                openSongDetailId = song.id,
            )
            SongMenuAction.LyricsOffset -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay),
            )
            SongMenuAction.RemoveFromPlaylist -> {
                val removed = fromPlaylistId != null &&
                    playlistStore.removeSongFromPlaylist(fromPlaylistId, song.id)
                SongMenuActionOutcome(
                    overlay = dismissActionMenu(overlay),
                    snackbarMessage = if (removed) "已从歌单移除" else null,
                )
            }
            SongMenuAction.Delete -> SongMenuActionOutcome(
                overlay = dismissActionMenu(overlay).copy(pendingDeleteSong = song),
            )
        }
    }

    fun requestDeleteSong(overlay: HomeOverlayState, song: Song): HomeOverlayState =
        overlay.copy(pendingDeleteSong = song)

    fun clearPendingDeleteSong(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(pendingDeleteSong = null)

    fun requestDeletePlaylist(overlay: HomeOverlayState, playlistId: String): HomeOverlayState =
        overlay.copy(pendingDeletePlaylistId = playlistId)

    fun clearPendingDeletePlaylist(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(pendingDeletePlaylistId = null)

    fun requestRenamePlaylist(overlay: HomeOverlayState, playlistId: String): HomeOverlayState =
        overlay.copy(renamePlaylistId = playlistId)

    fun clearRenamePlaylist(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(renamePlaylistId = null)

    fun showCreatePlaylistDialog(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(showCreatePlaylistDialog = true)

    fun dismissCreatePlaylistDialog(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(showCreatePlaylistDialog = false)

    fun dismissAddToPlaylist(overlay: HomeOverlayState): HomeOverlayState =
        overlay.copy(addToPlaylistSongs = null, addToPlaylistAsCustomOrder = false)

    suspend fun deleteSong(
        context: Context,
        song: Song,
        currentQueue: List<Song>,
        setQueue: (List<Song>) -> Unit,
    ): String =
        deleteSongEverywhere(
            context = context,
            song = song,
            currentQueue = currentQueue,
            removeFromLibrary = library::removeSongFromLibrary,
            removeFromAllPlaylists = playlistStore::removeSongFromAllPlaylists,
            setQueue = setQueue,
        ).message

    fun deletePlaylist(
        playlistId: String,
        section: HomeSection,
        activePlaylistId: String?,
    ): PlaylistDeleteOutcome {
        val name = playlistStore.playlistById(playlistId)?.name ?: "歌单"
        playlistStore.deletePlaylist(playlistId)
        return if (section == HomeSection.Playlist && activePlaylistId == playlistId) {
            PlaylistDeleteOutcome(
                snackbarMessage = "已删除「$name」",
                section = HomeSection.Songs,
                activePlaylistId = null,
            )
        } else {
            PlaylistDeleteOutcome(snackbarMessage = "已删除「$name」")
        }
    }

    fun createPlaylist(name: String): CreatePlaylistOutcome =
        runCatching { playlistStore.createPlaylist(name) }
            .fold(
                onSuccess = { playlist ->
                    CreatePlaylistOutcome(
                        section = HomeSection.Playlist,
                        activePlaylistId = playlist.id,
                    )
                },
                onFailure = { error ->
                    CreatePlaylistOutcome(
                        snackbarMessage = error.message ?: "创建失败",
                    )
                },
            )
}

@Composable
fun rememberHomeScreenController(
    library: MusicLibrary,
    playlistStore: PlaylistStore,
): HomeScreenController = remember(library, playlistStore) {
    HomeScreenController(library, playlistStore)
}
