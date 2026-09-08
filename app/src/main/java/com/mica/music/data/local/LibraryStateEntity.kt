package com.mica.music.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mica.music.data.ScanSource
import com.mica.music.data.library.LibraryAccessState
import com.mica.music.data.library.LibraryIntentState
import com.mica.music.data.library.LibrarySourceState
import com.mica.music.data.library.PersistedLibraryState
import com.mica.music.data.library.SourceActivation
import com.mica.music.data.library.SourceIdentityKey

@Entity(tableName = "library_state")
data class LibraryStateEntity(
    @PrimaryKey val id: Int = 1,
    val intent: String,
    val access: String,
    val activeSource: String = "",
    val activeStableIdentity: String = "",
    val activeEpoch: Long = 0L,
    val pendingSource: String = "",
    val pendingStableIdentity: String = "",
    val pendingEpoch: Long = 0L,
    val configFingerprint: String = "",
)

@Dao
interface LibraryStateDao {
    @Query("SELECT * FROM library_state WHERE id = 1 LIMIT 1")
    suspend fun get(): LibraryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LibraryStateEntity)
}

internal fun PersistedLibraryState.toEntity(): LibraryStateEntity {
    val active = sourceState.active
    val pending = sourceState.pendingTransition
    return LibraryStateEntity(
        intent = intent.name,
        access = access.name,
        activeSource = active?.sourceIdentity?.source?.storageValue.orEmpty(),
        activeStableIdentity = active?.sourceIdentity?.stableIdentity.orEmpty(),
        activeEpoch = active?.activationEpoch ?: 0L,
        pendingSource = pending?.sourceIdentity?.source?.storageValue.orEmpty(),
        pendingStableIdentity = pending?.sourceIdentity?.stableIdentity.orEmpty(),
        pendingEpoch = pending?.activationEpoch ?: 0L,
        configFingerprint = configFingerprint,
    )
}

internal fun LibraryStateEntity.toModel(): PersistedLibraryState = PersistedLibraryState(
    intent = runCatching { LibraryIntentState.valueOf(intent) }
        .getOrDefault(LibraryIntentState.UNINITIALIZED),
    access = runCatching { LibraryAccessState.valueOf(access) }
        .getOrDefault(LibraryAccessState.AVAILABLE),
    sourceState = LibrarySourceState(
        active = activation(activeSource, activeStableIdentity, activeEpoch),
        pendingTransition = activation(pendingSource, pendingStableIdentity, pendingEpoch),
    ),
    configFingerprint = configFingerprint,
)

private fun activation(
    sourceValue: String,
    stableIdentity: String,
    epoch: Long,
): SourceActivation? {
    if (sourceValue.isBlank() || stableIdentity.isBlank() || epoch <= 0L) return null
    val source = ScanSource.fromStorage(sourceValue)
    return SourceActivation(
        sourceIdentity = SourceIdentityKey(source, stableIdentity),
        activationEpoch = epoch,
    )
}
