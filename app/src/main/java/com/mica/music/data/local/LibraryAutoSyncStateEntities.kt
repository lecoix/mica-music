package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mica.music.data.ScanSource
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryRetryItem
import com.mica.music.data.library.LibraryRetryKind
import com.mica.music.data.library.LibrarySyncCheckpoint
import com.mica.music.data.library.LibraryUserExclusion
import com.mica.music.data.library.SourceIdentityKey

@Entity(
    tableName = "library_sync_state",
    primaryKeys = ["source", "stableIdentity", "partitionKey"],
)
data class LibrarySyncCheckpointEntity(
    val source: String,
    val stableIdentity: String,
    val partitionKey: String,
    val providerVersion: String,
    val generation: Long,
    val configFingerprint: String,
    val lastSuccessfulAutoSyncAtMs: Long,
)

@Entity(
    tableName = "library_retry_items",
    primaryKeys = ["source", "stableIdentity", "retryKey"],
    indices = [
        Index(
            name = "index_library_retry_items_due_kind",
            value = ["source", "stableIdentity", "retryKind", "nextRetryAtMs", "retryKey"],
        ),
        Index(
            name = "index_library_retry_items_next_retry",
            value = ["source", "stableIdentity", "nextRetryAtMs"],
        ),
    ],
)
data class LibraryRetryItemEntity(
    val source: String,
    val stableIdentity: String,
    val retryKey: String,
    val activationEpoch: Long?,
    val stableObjectKey: String,
    val observedFingerprint: String,
    val retryKind: String,
    val failureKind: String,
    val attemptCount: Int,
    val nextRetryAtMs: Long,
)

@Entity(
    tableName = "library_followup_outbox",
    indices = [
        Index(
            name = "index_library_followup_outbox_created_event",
            value = ["createdAtMs", "eventId"],
        ),
    ],
)
data class LibraryFollowupOutboxEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val libraryRevision: Long,
    val action: String,
    val source: String,
    val stableIdentity: String,
    val activationEpoch: Long?,
    val stableObjectKey: String,
    val payload: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "library_user_exclusions",
    primaryKeys = ["source", "stableIdentity", "stableObjectKey"],
)
data class LibraryUserExclusionEntity(
    val source: String,
    val stableIdentity: String,
    val stableObjectKey: String,
    val exclusionRevision: Long,
    val createdAtMs: Long,
)

@Dao
interface LibrarySyncStateDao {
    @Query(
        "SELECT * FROM library_sync_state WHERE source = :source AND stableIdentity = :stableIdentity",
    )
    suspend fun getBySource(source: String, stableIdentity: String): List<LibrarySyncCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibrarySyncCheckpointEntity>)

    @Query(
        "DELETE FROM library_sync_state " +
            "WHERE source = :source AND stableIdentity = :stableIdentity AND partitionKey IN (:partitionKeys)",
    )
    suspend fun deleteByKeys(source: String, stableIdentity: String, partitionKeys: List<String>)

    @Query("DELETE FROM library_sync_state")
    suspend fun deleteAll()
}

@Dao
interface LibraryRetryItemDao {
    @Query(
        "SELECT * FROM library_retry_items " +
            "WHERE source = :source AND stableIdentity = :stableIdentity AND retryKey > :afterRetryKey " +
            "ORDER BY retryKey LIMIT :limit",
    )
    suspend fun getPage(
        source: String,
        stableIdentity: String,
        afterRetryKey: String,
        limit: Int,
    ): List<LibraryRetryItemEntity>

    @Query(
        "SELECT * FROM library_retry_items " +
            "WHERE source = :source AND stableIdentity = :stableIdentity " +
            "AND retryKind = :retryKind " +
            "AND (activationEpoch IS NULL OR activationEpoch = :activationEpoch) " +
            "AND nextRetryAtMs <= :nowMs " +
            "ORDER BY nextRetryAtMs, retryKey LIMIT :limit",
    )
    suspend fun getDueByKind(
        source: String,
        stableIdentity: String,
        retryKind: String,
        activationEpoch: Long,
        nowMs: Long,
        limit: Int,
    ): List<LibraryRetryItemEntity>

    @Query(
        "SELECT * FROM library_retry_items " +
            "WHERE source = :source AND stableIdentity = :stableIdentity " +
            "AND retryKey IN (:retryKeys)",
    )
    suspend fun getByKeys(
        source: String,
        stableIdentity: String,
        retryKeys: List<String>,
    ): List<LibraryRetryItemEntity>

    @Query(
        "SELECT MIN(nextRetryAtMs) FROM library_retry_items " +
            "WHERE source = :source AND stableIdentity = :stableIdentity " +
            "AND (activationEpoch IS NULL OR activationEpoch = :activationEpoch) " +
            "AND nextRetryAtMs > :afterMs",
    )
    suspend fun minNextRetryAtAfter(
        source: String,
        stableIdentity: String,
        activationEpoch: Long,
        afterMs: Long,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryRetryItemEntity>)

    @Query(
        "DELETE FROM library_retry_items " +
            "WHERE source = :source AND stableIdentity = :stableIdentity AND retryKey IN (:retryKeys)",
    )
    suspend fun deleteByKeys(source: String, stableIdentity: String, retryKeys: List<String>)

    @Query("DELETE FROM library_retry_items")
    suspend fun deleteAll()
}

@Dao
interface LibraryFollowupOutboxDao {
    @Query(
        "SELECT * FROM library_followup_outbox " +
            "WHERE createdAtMs > :afterCreatedAtMs " +
            "OR (createdAtMs = :afterCreatedAtMs AND eventId > :afterEventId) " +
            "ORDER BY createdAtMs, eventId LIMIT :limit",
    )
    suspend fun getPage(
        afterCreatedAtMs: Long,
        afterEventId: String,
        limit: Int,
    ): List<LibraryFollowupOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryFollowupOutboxEntity)

    @Query("DELETE FROM library_followup_outbox WHERE eventId = :eventId")
    suspend fun deleteById(eventId: String): Int
}

@Dao
interface LibraryUserExclusionDao {
    @Query(
        "SELECT * FROM library_user_exclusions WHERE source = :source AND stableIdentity = :stableIdentity",
    )
    suspend fun getBySource(source: String, stableIdentity: String): List<LibraryUserExclusionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryUserExclusionEntity)

    @Query(
        "DELETE FROM library_user_exclusions " +
            "WHERE source = :source AND stableIdentity = :stableIdentity AND stableObjectKey = :stableObjectKey",
    )
    suspend fun delete(source: String, stableIdentity: String, stableObjectKey: String)
}

internal fun LibrarySyncCheckpoint.toEntity() = LibrarySyncCheckpointEntity(
    source = sourceIdentity.source.storageValue,
    stableIdentity = sourceIdentity.stableIdentity,
    partitionKey = partitionKey,
    providerVersion = providerVersion,
    generation = generation,
    configFingerprint = configFingerprint,
    lastSuccessfulAutoSyncAtMs = lastSuccessfulAutoSyncAtMs,
)

internal fun LibrarySyncCheckpointEntity.toModel() = LibrarySyncCheckpoint(
    sourceIdentity = SourceIdentityKey(ScanSource.fromStorage(source), stableIdentity),
    partitionKey = partitionKey,
    providerVersion = providerVersion,
    generation = generation,
    configFingerprint = configFingerprint,
    lastSuccessfulAutoSyncAtMs = lastSuccessfulAutoSyncAtMs,
)

internal fun LibraryRetryItem.toEntity() = LibraryRetryItemEntity(
    source = sourceIdentity.source.storageValue,
    stableIdentity = sourceIdentity.stableIdentity,
    retryKey = retryKey,
    activationEpoch = activationEpoch,
    stableObjectKey = stableObjectKey,
    observedFingerprint = observedFingerprint,
    retryKind = retryKind.name,
    failureKind = failureKind,
    attemptCount = attemptCount,
    nextRetryAtMs = nextRetryAtMs,
)

internal fun LibraryRetryItemEntity.toModel() = LibraryRetryItem(
    sourceIdentity = SourceIdentityKey(ScanSource.fromStorage(source), stableIdentity),
    retryKey = retryKey,
    activationEpoch = activationEpoch,
    stableObjectKey = stableObjectKey,
    observedFingerprint = observedFingerprint,
    retryKind = runCatching { LibraryRetryKind.valueOf(retryKind) }
        .getOrDefault(LibraryRetryKind.OBJECT_PROBE),
    failureKind = failureKind,
    attemptCount = attemptCount,
    nextRetryAtMs = nextRetryAtMs,
)

internal fun LibraryFollowupOutboxItem.toEntity() = LibraryFollowupOutboxEntity(
    eventId = eventId,
    libraryRevision = libraryRevision,
    action = action,
    source = sourceIdentity.source.storageValue,
    stableIdentity = sourceIdentity.stableIdentity,
    activationEpoch = activationEpoch,
    stableObjectKey = stableObjectKey,
    payload = payload,
    createdAtMs = createdAtMs,
)

internal fun LibraryFollowupOutboxEntity.toModel() = LibraryFollowupOutboxItem(
    eventId = eventId,
    libraryRevision = libraryRevision,
    action = action,
    sourceIdentity = SourceIdentityKey(ScanSource.fromStorage(source), stableIdentity),
    activationEpoch = activationEpoch,
    stableObjectKey = stableObjectKey,
    payload = payload,
    createdAtMs = createdAtMs,
)

internal fun LibraryUserExclusion.toEntity() = LibraryUserExclusionEntity(
    source = sourceIdentity.source.storageValue,
    stableIdentity = sourceIdentity.stableIdentity,
    stableObjectKey = stableObjectKey,
    exclusionRevision = exclusionRevision,
    createdAtMs = createdAtMs,
)

internal fun LibraryUserExclusionEntity.toModel() = LibraryUserExclusion(
    sourceIdentity = SourceIdentityKey(ScanSource.fromStorage(source), stableIdentity),
    stableObjectKey = stableObjectKey,
    exclusionRevision = exclusionRevision,
    createdAtMs = createdAtMs,
)
