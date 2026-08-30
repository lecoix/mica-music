package com.mica.music.data.remote

import android.content.Context
import androidx.room.withTransaction
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.local.RemoteSourceEntity
import com.mica.music.data.local.toEntity
import com.mica.music.data.local.toRemoteSourceInstance
import com.mica.music.data.local.toRemoteTrackSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent owner for remote source definitions and source-scoped catalog snapshots.
 *
 * All source edits and catalog publication are serialized through [mutex]. This is intentional:
 * an HTTP/listing operation may finish after a newer refresh or source edit, but it only reaches
 * storage if its [RemoteOperationToken] is still current for the process owner and its persisted
 * config revision still matches inside the Room transaction.
 */
class RemoteCatalogRepository internal constructor(
    private val database: MicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RemoteTrackSummaryLookup {
    constructor(context: Context) : this(MicaDatabase.get(context))

    private val sourceDao = database.remoteSourceDao()
    private val trackDao = database.remoteTrackDao()
    private val mutex = Mutex()
    private val owners = LinkedHashMap<String, RemoteSourceOwner>()

    suspend fun sources(enabledOnly: Boolean = false): List<RemoteSourceInstance> =
        if (enabledOnly) sourceDao.getEnabled().map(RemoteSourceEntity::toRemoteSourceInstance)
        else sourceDao.getAll().map(RemoteSourceEntity::toRemoteSourceInstance)

    suspend fun source(sourceInstanceId: String): RemoteSourceInstance? =
        sourceDao.getById(sourceInstanceId)?.toRemoteSourceInstance()

    suspend fun sourceStatuses(): List<RemoteSourceStatus> = sourceDao.getAll().map { entity ->
        RemoteSourceStatus(
            instance = entity.toRemoteSourceInstance(),
            configRevision = entity.configRevision,
            catalogRevision = entity.catalogRevision,
            catalogConfigRevision = entity.catalogConfigRevision,
            lastSyncAtMs = entity.lastSyncAtMs,
            trackCount = trackDao.countForSource(entity.id),
        )
    }

    suspend fun sourceStatus(sourceInstanceId: String): RemoteSourceStatus? =
        sourceDao.getById(sourceInstanceId)?.let { entity ->
            RemoteSourceStatus(
                instance = entity.toRemoteSourceInstance(),
                configRevision = entity.configRevision,
                catalogRevision = entity.catalogRevision,
                catalogConfigRevision = entity.catalogConfigRevision,
                lastSyncAtMs = entity.lastSyncAtMs,
                trackCount = trackDao.countForSource(entity.id),
            )
        }
    suspend fun sourceSnapshot(sourceInstanceId: String): RemoteSourceSnapshot? = mutex.withLock {
        ownerForLocked(sourceInstanceId)?.snapshot()
    }

    suspend fun upsertSource(instance: RemoteSourceInstance): RemoteSourceSnapshot = mutex.withLock {
        val current = sourceDao.getById(instance.id)
        if (current == null) {
            val entity = instance.toEntity(configRevision = 1L)
            sourceDao.insert(entity)
            return@withLock RemoteSourceOwner(instance).also { owners[instance.id] = it }.snapshot()
        }

        require(current.type == instance.type.name) {
            "Remote source type cannot change for existing id=${instance.id}"
        }
        if (current.toRemoteSourceInstance() == instance) {
            return@withLock ownerForEntityLocked(current).snapshot()
        }

        val owner = ownerForEntityLocked(current)
        val snapshot = owner.replace(instance)
        try {
            val updated = sourceDao.update(
                instance.toEntity(
                    configRevision = snapshot.configRevision,
                    catalogRevision = current.catalogRevision,
                    catalogConfigRevision = current.catalogConfigRevision,
                    lastSyncAtMs = current.lastSyncAtMs,
                ),
            )
            check(updated == 1) { "Remote source disappeared during update" }
        } catch (failure: Throwable) {
            // Fail closed: restoring the previous public config also advances the in-memory revision,
            // so work from either side of the failed edit cannot later publish as current.
            owner.replace(current.toRemoteSourceInstance())
            throw failure
        }
        snapshot
    }

    suspend fun beginOperation(sourceInstanceId: String): RemoteOperationSnapshot? = mutex.withLock {
        ownerForLocked(sourceInstanceId)?.beginOperationSnapshot()
    }

    internal suspend fun sourceOwner(sourceInstanceId: String): RemoteSourceOwner? = mutex.withLock {
        ownerForLocked(sourceInstanceId)
    }

    suspend fun invalidateOperations(sourceInstanceId: String): RemoteSourceSnapshot? = mutex.withLock {
        ownerForLocked(sourceInstanceId)?.invalidateOperations()
    }

    suspend fun publishCatalogIfCurrent(
        token: RemoteOperationToken,
        tracks: List<RemoteTrackSummary>,
        syncedAtMs: Long = nowMs(),
    ): Boolean = mutex.withLock {
        val owner = ownerForLocked(token.sourceInstanceId) ?: return@withLock false
        if (!owner.isCurrent(token)) return@withLock false
        require(tracks.all { it.ref.sourceInstanceId == token.sourceInstanceId }) {
            "Catalog publication cannot mix source instances"
        }
        require(tracks.map { it.ref.opaqueTrackId }.toSet().size == tracks.size) {
            "Catalog publication contains duplicate opaque track ids"
        }

        database.withTransaction {
            val source = sourceDao.getById(token.sourceInstanceId) ?: return@withTransaction false
            if (source.configRevision != token.configRevision) return@withTransaction false
            if (!owner.isCurrent(token)) return@withTransaction false

            val entities = tracks.mapIndexed { index, track -> track.toEntity(index) }
            trackDao.replaceSourceCatalog(token.sourceInstanceId, entities)
            val nextCatalogRevision = source.catalogRevision + 1L
            val updated = sourceDao.updateCatalogRevisionIfConfigCurrent(
                sourceInstanceId = token.sourceInstanceId,
                configRevision = token.configRevision,
                catalogRevision = nextCatalogRevision,
                lastSyncAtMs = syncedAtMs.coerceAtLeast(0L),
            )
            check(updated == 1) { "Remote source changed during catalog publication" }
            true
        }
    }

    suspend fun tracksForSource(sourceInstanceId: String): List<RemoteTrackSummary> =
        trackDao.getForSource(sourceInstanceId).map { it.toRemoteTrackSummary() }

    /**
     * Returns the previously published catalog only when it belongs to the exact config revision
     * represented by [token]. A source edit leaves the old catalog visible but makes it ineligible
     * for metadata reuse until a new catalog is atomically published.
     */
    internal suspend fun reusableCatalogIfCurrent(
        token: RemoteOperationToken,
    ): Map<String, RemoteTrackSummary>? = mutex.withLock {
        val owner = ownerForLocked(token.sourceInstanceId) ?: return@withLock null
        if (!owner.isCurrent(token)) return@withLock null
        val source = sourceDao.getById(token.sourceInstanceId) ?: return@withLock null
        if (source.configRevision != token.configRevision || source.catalogConfigRevision != token.configRevision) {
            return@withLock null
        }
        trackDao.getForSource(token.sourceInstanceId)
            .map { it.toRemoteTrackSummary() }
            .associateBy { it.ref.opaqueTrackId }
    }

    /** Aggregate snapshot for browsing. Disabled source catalogs remain isolated and hidden. */
    suspend fun tracksForEnabledSources(): List<RemoteTrackSummary> =
        trackDao.getForEnabledSources().map { it.toRemoteTrackSummary() }

    override suspend fun find(refs: List<RemoteTrackRef>): Map<RemoteTrackRef, RemoteTrackSummary> {
        if (refs.isEmpty()) return emptyMap()
        val requested = refs.distinct()
        val found = LinkedHashMap<RemoteTrackRef, RemoteTrackSummary>(requested.size)
        requested.groupBy(RemoteTrackRef::sourceInstanceId).forEach { (sourceId, sourceRefs) ->
            val opaqueIds = sourceRefs.map(RemoteTrackRef::opaqueTrackId).distinct()
            trackDao.getByOpaqueIds(sourceId, opaqueIds).forEach { entity ->
                val summary = entity.toRemoteTrackSummary()
                found[summary.ref] = summary
            }
        }
        return found
    }

    private suspend fun ownerForLocked(sourceInstanceId: String): RemoteSourceOwner? {
        owners[sourceInstanceId]?.let { return it }
        val entity = sourceDao.getById(sourceInstanceId) ?: return null
        return ownerForEntityLocked(entity)
    }

    private fun ownerForEntityLocked(entity: RemoteSourceEntity): RemoteSourceOwner =
        owners.getOrPut(entity.id) {
            RemoteSourceOwner(
                initial = entity.toRemoteSourceInstance(),
                initialConfigRevision = entity.configRevision,
            )
        }
}


