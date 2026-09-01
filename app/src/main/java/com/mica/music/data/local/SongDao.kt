package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SongLyricsOffsetDao {
    @Query("SELECT * FROM song_lyrics_offsets WHERE songId = :songId LIMIT 1")
    fun observe(songId: String): Flow<SongLyricsOffsetEntity?>

    @Query("SELECT * FROM song_lyrics_offsets WHERE songId = :songId LIMIT 1")
    suspend fun get(songId: String): SongLyricsOffsetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SongLyricsOffsetEntity)

    @Query("DELETE FROM song_lyrics_offsets WHERE songId = :songId")
    suspend fun delete(songId: String)

}

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY queueOrder ASC")
    suspend fun getAllOrdered(): List<SongEntity>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM songs ORDER BY queueOrder ASC")
    suspend fun getAllSummariesOrdered(): List<SongSummaryEntity>

    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSummariesByIds(ids: List<String>): List<SongSummaryEntity>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT lyricsJson FROM songs WHERE id = :id LIMIT 1")
    suspend fun getLyricsById(id: String): LyricsJsonRow?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(entities: List<SongEntity>) {
        deleteAll()
        if (entities.isNotEmpty()) insertAll(entities)
    }

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE songs SET queueOrder = :queueOrder WHERE id = :songId")
    suspend fun updateQueueOrder(songId: String, queueOrder: Int)

    @Query(
        """
        UPDATE songs SET
            loudnessIntegratedLufs = :integratedLufs,
            loudnessSamplePeak = :samplePeak,
            loudnessTrackGainDb = :trackGainDb,
            loudnessSourceSizeBytes = :sourceSizeBytes,
            loudnessSourceModifiedMs = :sourceModifiedMs,
            loudnessAnalyzerRevision = :analyzerRevision
        WHERE id = :songId
        """,
    )
    suspend fun updateLoudnessAnalysis(
        songId: String,
        integratedLufs: Float?,
        samplePeak: Float?,
        trackGainDb: Float?,
        sourceSizeBytes: Long,
        sourceModifiedMs: Long,
        analyzerRevision: Int,
    )

    @Query("UPDATE songs SET coverColorArgb = :coverColorArgb WHERE id = :songId")
    suspend fun updateCoverColorArgb(songId: String, coverColorArgb: Int)

    @Transaction
    suspend fun updateQueueOrders(songIds: List<String>) {
        songIds.forEachIndexed { index, songId -> updateQueueOrder(songId, index) }
    }

    @Transaction
    suspend fun syncIncremental(
        entities: List<SongEntity>,
        removeIds: List<String>,
        orderedSongIds: List<String>,
    ) {
        if (removeIds.isNotEmpty()) deleteByIds(removeIds)
        if (entities.isNotEmpty()) insertAll(entities)
        updateQueueOrders(orderedSongIds)
    }
}

@Dao
interface SongLyricsDao {
    @Query("SELECT * FROM song_lyrics WHERE songId = :songId")
    suspend fun getBySongId(songId: String): List<SongLyricsEntity>

    @Query("SELECT slot FROM song_lyrics WHERE songId = :songId")
    suspend fun getSlots(songId: String): List<String>

    @Query("SELECT lyricsJson FROM song_lyrics WHERE songId = :songId AND slot = :slot LIMIT 1")
    suspend fun getLyricsJson(songId: String, slot: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SongLyricsEntity>)

    @Query("DELETE FROM song_lyrics WHERE songId IN (:songIds)")
    suspend fun deleteBySongIds(songIds: List<String>)

    @Query("DELETE FROM song_lyrics")
    suspend fun deleteAll()

    @Query("DELETE FROM song_lyrics WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphans()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(entities: List<PendingSongLyricsEntity>)

    @Query("DELETE FROM song_lyrics_pending")
    suspend fun deleteAllPending()

    @Query("DELETE FROM song_lyrics_pending WHERE scanId = :scanId")
    suspend fun deletePendingScan(scanId: String)

    @Query(
        "DELETE FROM song_lyrics WHERE songId IN " +
            "(SELECT songId FROM song_lyrics_pending WHERE scanId = :scanId)",
    )
    suspend fun deleteLyricsReplacedByPending(scanId: String)

    @Query(
        "INSERT OR REPLACE INTO song_lyrics(songId, slot, revision, lyricsJson) " +
            "SELECT songId, 'EMBEDDED', revision, embeddedJson FROM song_lyrics_pending " +
            "WHERE scanId = :scanId AND embeddedJson IS NOT NULL",
    )
    suspend fun promotePendingEmbedded(scanId: String)

    @Query(
        "INSERT OR REPLACE INTO song_lyrics(songId, slot, revision, lyricsJson) " +
            "SELECT songId, 'EXTERNAL_LRC', revision, externalLrcJson FROM song_lyrics_pending " +
            "WHERE scanId = :scanId AND externalLrcJson IS NOT NULL",
    )
    suspend fun promotePendingExternalLrc(scanId: String)

    @Query(
        "INSERT OR REPLACE INTO song_lyrics(songId, slot, revision, lyricsJson) " +
            "SELECT songId, 'EXTERNAL_TTML', revision, externalTtmlJson FROM song_lyrics_pending " +
            "WHERE scanId = :scanId AND externalTtmlJson IS NOT NULL",
    )
    suspend fun promotePendingExternalTtml(scanId: String)
}
