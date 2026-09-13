package com.mica.music.data.local

import androidx.room.withTransaction
import com.mica.music.data.SortDirection
import com.mica.music.data.SongSortField
import com.mica.music.data.UserPlaylist
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.data.library.MembershipRemovalReason

internal data class PlaylistStorageSnapshot(
    val playlists: List<UserPlaylist>,
    val revision: Long,
)

internal class PlaylistRepository(
    private val database: MicaDatabase,
) {
    private val dao = database.playlistDao()
    private val followupDao = database.libraryFollowupOutboxDao()
    private val membershipEvidenceDao = database.libraryMembershipEvidenceDao()
    private val songDao = database.songDao()
    private val libraryStateDao = database.libraryStateDao()

    suspend fun load(): List<UserPlaylist> = loadSnapshot().playlists

    suspend fun loadSnapshot(): PlaylistStorageSnapshot = database.withTransaction {
        PlaylistStorageSnapshot(
            playlists = loadPlaylistsInTransaction(),
            revision = currentRevisionInTransaction(),
        )
    }

    suspend fun currentRevision(): Long = database.withTransaction {
        currentRevisionInTransaction()
    }

    suspend fun insertPlaylist(playlist: UserPlaylist, position: Int): Long =
        database.withTransaction {
            dao.replacePlaylist(playlist.toEntity(position), playlist.toSongEntities())
            bumpRevisionInTransaction()
        }

    suspend fun updatePlaylistMetadata(playlist: UserPlaylist, position: Int): Long =
        database.withTransaction {
            dao.updatePlaylist(playlist.toEntity(position))
            bumpRevisionInTransaction()
        }

    suspend fun replacePlaylist(playlist: UserPlaylist, position: Int): Long =
        database.withTransaction {
            dao.replacePlaylist(playlist.toEntity(position), playlist.toSongEntities())
            bumpRevisionInTransaction()
        }

    suspend fun addSong(playlistId: String, songId: String, position: Int): Long =
        database.withTransaction {
            dao.insertSongs(listOf(PlaylistSongEntity(playlistId, songId, position)))
            bumpRevisionInTransaction()
        }

    suspend fun deletePlaylist(playlistId: String): Long =
        database.withTransaction {
            dao.deletePlaylist(playlistId)
            bumpRevisionInTransaction()
        }

    suspend fun moveSong(
        playlist: UserPlaylist,
        playlistPosition: Int,
        songId: String,
        fromIndex: Int,
        toIndex: Int,
    ): Long = database.withTransaction {
            dao.moveSongAndUpdatePlaylist(
                playlist.toEntity(playlistPosition),
                songId,
                fromIndex,
                toIndex,
            )
            bumpRevisionInTransaction()
        }

    suspend fun removeSong(
        playlist: UserPlaylist,
        playlistPosition: Int,
        songId: String,
        removedIndex: Int,
    ): Long = database.withTransaction {
            dao.removeSongAndUpdatePlaylist(
                playlist.toEntity(playlistPosition),
                songId,
                removedIndex,
            )
            bumpRevisionInTransaction()
        }

    suspend fun removeSongEverywhere(songId: String): Long = database.withTransaction {
            dao.removeSongEverywhere(songId)
            bumpRevisionInTransaction()
        }

    suspend fun consumeConfirmedMissingFollowup(
        expected: LibraryFollowupOutboxItem,
        songId: String,
    ): PlaylistFollowupConsumeOutcome = database.withTransaction {
        val persistedEntity = followupDao.getById(expected.eventId)
            ?: return@withTransaction PlaylistFollowupConsumeOutcome(
                acknowledged = true,
                disposition = PlaylistFollowupDisposition.ALREADY_CONSUMED,
                playlistRevision = null,
            )
        val persisted = persistedEntity.toModel()
        if (
            persisted != expected ||
            persisted.action != LibraryFollowupProtocol.PLAYLIST_REMOVE_CONFIRMED_MISSING ||
            persisted.removalReason != MembershipRemovalReason.CONFIRMED_MISSING ||
            persisted.evidenceRevision.isBlank()
        ) {
            return@withTransaction PlaylistFollowupConsumeOutcome(
                acknowledged = false,
                disposition = PlaylistFollowupDisposition.REJECTED,
                playlistRevision = null,
            )
        }

        val persistedState = libraryStateDao.get()?.toModel()
            ?: return@withTransaction PlaylistFollowupConsumeOutcome(
                acknowledged = false,
                disposition = PlaylistFollowupDisposition.REJECTED,
                playlistRevision = null,
            )
        val eventSourceIsActive =
            persistedState.sourceState.active?.sourceIdentity == persisted.sourceIdentity
        val objectIsPresentAgain =
            eventSourceIsActive && songDao.getById(persisted.stableObjectKey) != null
        val currentEvidence = membershipEvidenceDao.get(
            source = persisted.sourceIdentity.source.storageValue,
            stableIdentity = persisted.sourceIdentity.stableIdentity,
            stableObjectKey = persisted.stableObjectKey,
        )
        val removalEvidenceStillCurrent =
            currentEvidence?.removalReason == MembershipRemovalReason.CONFIRMED_MISSING.name &&
                currentEvidence.evidenceRevision == persisted.evidenceRevision &&
                currentEvidence.songId == songId

        if (objectIsPresentAgain || !removalEvidenceStillCurrent) {
            followupDao.deleteById(persisted.eventId)
            return@withTransaction PlaylistFollowupConsumeOutcome(
                acknowledged = true,
                disposition = PlaylistFollowupDisposition.OBSOLETE,
                playlistRevision = null,
            )
        }

        dao.removeSongEverywhere(songId)
        val playlistRevision = bumpRevisionInTransaction()
        followupDao.deleteById(persisted.eventId)
        // Read the post-removal snapshot inside the same transaction so the in-memory publication
        // is by construction the durable state at [playlistRevision]. Non-APPLIED outcomes never
        // pay for a full playlist read.
        PlaylistFollowupConsumeOutcome(
            acknowledged = true,
            disposition = PlaylistFollowupDisposition.APPLIED,
            playlistRevision = playlistRevision,
            playlists = loadPlaylistsInTransaction(),
        )
    }

    private suspend fun loadPlaylistsInTransaction(): List<UserPlaylist> {
        val playlists = dao.getPlaylists()
        val songsByPlaylist = dao.getSongs().groupBy(PlaylistSongEntity::playlistId)
        return playlists.map { entity ->
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

    private suspend fun currentRevisionInTransaction(): Long {
        dao.ensureState(PlaylistStateEntity())
        return requireNotNull(dao.getRevision())
    }

    private suspend fun bumpRevisionInTransaction(): Long {
        dao.ensureState(PlaylistStateEntity())
        check(dao.incrementRevision() == 1) { "playlist_state revision row missing" }
        return requireNotNull(dao.getRevision())
    }

    suspend fun replaceAll(playlists: List<UserPlaylist>): Long = database.withTransaction {
            dao.replaceAll(
                playlists = playlists.mapIndexed { index, playlist -> playlist.toEntity(index) },
                songs = playlists.flatMap { it.toSongEntities() },
            )
            bumpRevisionInTransaction()
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

internal enum class PlaylistFollowupDisposition {
    APPLIED,
    OBSOLETE,
    ALREADY_CONSUMED,
    REJECTED,
}

internal data class PlaylistFollowupConsumeOutcome(
    val acknowledged: Boolean,
    val disposition: PlaylistFollowupDisposition,
    val playlistRevision: Long?,
    /** Durable playlists at [playlistRevision]; only present when the disposition is APPLIED. */
    val playlists: List<UserPlaylist>? = null,
)

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
