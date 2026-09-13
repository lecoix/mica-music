package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Entity(tableName = "playlist_state")
data class PlaylistStateEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0L,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortField: String,
    val sortDirection: String,
    val coverSongId: String?,
    val customCoverPath: String?,
    val position: Int,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val position: Int,
)

data class PlaylistSongPosition(
    val playlistId: String,
    val position: Int,
)

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureState(state: PlaylistStateEntity): Long

    @Query("SELECT revision FROM playlist_state WHERE id = 1")
    suspend fun getRevision(): Long?

    @Query("UPDATE playlist_state SET revision = revision + 1 WHERE id = 1")
    suspend fun incrementRevision(): Int

    @Query("SELECT * FROM playlists ORDER BY position ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    suspend fun getSongs(): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteSongs(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deleteSong(playlistId: String, songId: String)

    @Query("SELECT playlistId, position FROM playlist_songs WHERE songId = :songId")
    suspend fun getSongPositions(songId: String): List<PlaylistSongPosition>

    @Query("SELECT DISTINCT playlistId FROM playlist_songs WHERE songId IN (:songIds)")
    suspend fun getPlaylistIdsContainingAny(songIds: List<String>): List<String>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongsForPlaylist(playlistId: String): List<PlaylistSongEntity>

    @Query("UPDATE playlists SET coverSongId = NULL WHERE coverSongId = :songId")
    suspend fun clearCoverSong(songId: String)

    @Query("UPDATE playlists SET coverSongId = NULL WHERE coverSongId IN (:songIds)")
    suspend fun clearCoverSongs(songIds: List<String>)

    @Query(
        "UPDATE playlist_songs SET position = position - 1 " +
            "WHERE playlistId = :playlistId AND position > :fromIndex AND position <= :toIndex",
    )
    suspend fun shiftSongsLeft(playlistId: String, fromIndex: Int, toIndex: Int)

    @Query(
        "UPDATE playlist_songs SET position = position + 1 " +
            "WHERE playlistId = :playlistId AND position >= :toIndex AND position < :fromIndex",
    )
    suspend fun shiftSongsRight(playlistId: String, fromIndex: Int, toIndex: Int)

    @Query(
        "UPDATE playlist_songs SET position = :position " +
            "WHERE playlistId = :playlistId AND songId = :songId",
    )
    suspend fun updateSongPosition(playlistId: String, songId: String, position: Int)

    @Query(
        "UPDATE playlist_songs SET position = position - 1 " +
            "WHERE playlistId = :playlistId AND position > :removedIndex",
    )
    suspend fun closePositionGap(playlistId: String, removedIndex: Int)

    @Transaction
    suspend fun replacePlaylist(playlist: PlaylistEntity, songs: List<PlaylistSongEntity>) {
        upsertPlaylist(playlist)
        deleteSongs(playlist.id)
        if (songs.isNotEmpty()) insertSongs(songs)
    }

    @Transaction
    suspend fun replaceAll(playlists: List<PlaylistEntity>, songs: List<PlaylistSongEntity>) {
        deleteAll()
        playlists.forEach { upsertPlaylist(it) }
        songs.chunked(PLAYLIST_SONG_INSERT_BATCH_SIZE).forEach { insertSongs(it) }
    }

    @Transaction
    suspend fun moveSong(
        playlistId: String,
        songId: String,
        fromIndex: Int,
        toIndex: Int,
    ) {
        when {
            fromIndex < toIndex -> shiftSongsLeft(playlistId, fromIndex, toIndex)
            fromIndex > toIndex -> shiftSongsRight(playlistId, fromIndex, toIndex)
            else -> return
        }
        updateSongPosition(playlistId, songId, toIndex)
    }

    @Transaction
    suspend fun moveSongAndUpdatePlaylist(
        playlist: PlaylistEntity,
        songId: String,
        fromIndex: Int,
        toIndex: Int,
    ) {
        moveSong(playlist.id, songId, fromIndex, toIndex)
        updatePlaylist(playlist)
    }

    @Transaction
    suspend fun removeSong(playlistId: String, songId: String, removedIndex: Int) {
        deleteSong(playlistId, songId)
        closePositionGap(playlistId, removedIndex)
    }

    @Transaction
    suspend fun removeSongAndUpdatePlaylist(
        playlist: PlaylistEntity,
        songId: String,
        removedIndex: Int,
    ) {
        removeSong(playlist.id, songId, removedIndex)
        updatePlaylist(playlist)
    }

    @Transaction
    suspend fun removeSongEverywhere(songId: String) {
        val positions = getSongPositions(songId)
        positions.forEach { deleteSong(it.playlistId, songId) }
        positions.forEach { closePositionGap(it.playlistId, it.position) }
        clearCoverSong(songId)
    }

    /**
     * Removes a bounded batch without repeatedly shifting the same playlist for every song.
     * Each affected playlist is read and compacted once for the whole batch.
     */
    @Transaction
    suspend fun removeSongsEverywhere(songIds: List<String>) {
        val removals = songIds.asSequence().filter(String::isNotBlank).distinct().toList()
        if (removals.isEmpty()) return
        val removalSet = removals.toHashSet()
        getPlaylistIdsContainingAny(removals).forEach { playlistId ->
            val remaining = getSongsForPlaylist(playlistId)
                .asSequence()
                .filterNot { it.songId in removalSet }
                .mapIndexed { index, row -> row.copy(position = index) }
                .toList()
            deleteSongs(playlistId)
            if (remaining.isNotEmpty()) insertSongs(remaining)
        }
        clearCoverSongs(removals)
    }
}

private const val PLAYLIST_SONG_INSERT_BATCH_SIZE = 500
