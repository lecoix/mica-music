package com.mica.music.data.local

import androidx.room.withTransaction
import com.mica.music.data.SortDirection
import com.mica.music.data.SongSortField
import com.mica.music.data.UserPlaylist

internal class PlaylistRepository(
    private val database: MicaDatabase,
) {
    private val dao = database.playlistDao()

    suspend fun load(): List<UserPlaylist> = database.withTransaction {
        val playlists = dao.getPlaylists()
        val songsByPlaylist = dao.getSongs().groupBy(PlaylistSongEntity::playlistId)
        playlists.map { entity ->
            UserPlaylist(
                id = entity.id,
                name = entity.name,
                songIds = songsByPlaylist[entity.id].orEmpty().map(PlaylistSongEntity::songId),
                sortField = SongSortField.fromStorage(entity.sortField),
                sortDirection = SortDirection.fromStorage(entity.sortDirection),
                coverSongId = entity.coverSongId,
                customCoverPath = entity.customCoverPath,
            )
        }
    }

    suspend fun insertPlaylist(playlist: UserPlaylist, position: Int) {
        dao.replacePlaylist(playlist.toEntity(position), playlist.toSongEntities())
    }

    suspend fun updatePlaylistMetadata(playlist: UserPlaylist, position: Int) {
        dao.updatePlaylist(playlist.toEntity(position))
    }

    suspend fun replacePlaylist(playlist: UserPlaylist, position: Int) {
        dao.replacePlaylist(playlist.toEntity(position), playlist.toSongEntities())
    }

    suspend fun addSong(playlistId: String, songId: String, position: Int) {
        dao.insertSongs(listOf(PlaylistSongEntity(playlistId, songId, position)))
    }

    suspend fun deletePlaylist(playlistId: String) = dao.deletePlaylist(playlistId)

    suspend fun moveSong(
        playlist: UserPlaylist,
        playlistPosition: Int,
        songId: String,
        fromIndex: Int,
        toIndex: Int,
    ) = dao.moveSongAndUpdatePlaylist(
        playlist.toEntity(playlistPosition),
        songId,
        fromIndex,
        toIndex,
    )

    suspend fun removeSong(
        playlist: UserPlaylist,
        playlistPosition: Int,
        songId: String,
        removedIndex: Int,
    ) = dao.removeSongAndUpdatePlaylist(
        playlist.toEntity(playlistPosition),
        songId,
        removedIndex,
    )

    suspend fun removeSongEverywhere(songId: String) = dao.removeSongEverywhere(songId)

    suspend fun replaceAll(playlists: List<UserPlaylist>) {
        dao.replaceAll(
            playlists = playlists.mapIndexed { index, playlist -> playlist.toEntity(index) },
            songs = playlists.flatMap { it.toSongEntities() },
        )
    }

    suspend fun migrateSongIds(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val migrated = load().map { playlist ->
            playlist.copy(
                songIds = playlist.songIds.map { mapping[it] ?: it }.distinct(),
                coverSongId = playlist.coverSongId?.let { mapping[it] ?: it },
            )
        }
        replaceAll(migrated)
    }
}

private fun UserPlaylist.toEntity(position: Int): PlaylistEntity = PlaylistEntity(
    id = id,
    name = name,
    sortField = sortField.storageValue,
    sortDirection = sortDirection.storageValue,
    coverSongId = coverSongId,
    customCoverPath = customCoverPath,
    position = position,
)

private fun UserPlaylist.toSongEntities(): List<PlaylistSongEntity> =
    songIds.mapIndexed { index, songId -> PlaylistSongEntity(id, songId, index) }
