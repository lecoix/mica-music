package com.mica.music.data.remote

import android.content.Context
import androidx.room.withTransaction
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.local.RemoteSourceEntity
import com.mica.music.data.local.RemoteSelectedTrackEntity
import com.mica.music.data.local.RemoteSmbScopeEntity
import com.mica.music.data.local.RemoteSmbScopeTrackEntity
import com.mica.music.data.local.toEntity
import com.mica.music.data.local.toRemoteSourceInstance
import com.mica.music.data.local.toRemoteTrackSummary
import com.mica.music.data.remote.smb.SmbFolderScope
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private val selectedDao = database.remoteSelectedTrackDao()
    private val smbScopeDao = database.remoteSmbScopeDao()
    private val mutex = Mutex()
    private val owners = LinkedHashMap<String, RemoteSourceOwner>()
    private var metadataRequest = 0L

    fun observeSources(): Flow<List<RemoteSourceInstance>> = sourceDao.observe().map { rows ->
        rows.map { it.toRemoteSourceInstance() }
    }

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

    suspend fun deleteSource(sourceInstanceId: String): Boolean = mutex.withLock {
        val persisted = sourceDao.getById(sourceInstanceId) ?: run {
            owners.remove(sourceInstanceId)
            return@withLock false
        }
        ownerForEntityLocked(persisted).invalidateOperations()
        val deleted = database.withTransaction {
            sourceDao.deleteById(sourceInstanceId)
        }
        check(deleted == 1) { "Remote source disappeared during deletion" }
        owners.remove(sourceInstanceId)
        true
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

    internal suspend fun publishIfCurrent(token: RemoteOperationToken, publish: () -> Unit): Boolean = mutex.withLock {
        val owner = ownerForLocked(token.sourceInstanceId) ?: return@withLock false
        if (!owner.isCurrent(token) || !owner.snapshot().instance.enabled) return@withLock false
        publish()
        true
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

    internal suspend fun smbFolderScopes(sourceInstanceId: String): List<SmbFolderScope> =
        smbScopeDao.managedForSource(sourceInstanceId).map { row ->
            SmbFolderScope(
                id = row.id,
                sourceInstanceId = row.sourceInstanceId,
                relativeDirectory = row.relativeDirectory,
                includeSubdirectories = row.includeSubdirectories,
                legacySnapshot = row.kind == "LEGACY",
                observedConfigRevision = row.observedConfigRevision,
                lastCompletedAtMs = row.lastCompletedAtMs,
            )
        }

    /**
     * Atomically replaces one explicit SMB folder scope. This is a partial-library publication,
     * not a source catalog sync: it deliberately does not touch lastSyncAt/catalogConfigRevision.
     */
    internal suspend fun replaceSmbFolderScopeIfCurrent(
        token: RemoteOperationToken,
        relativeDirectory: String,
        includeSubdirectories: Boolean,
        tracks: List<RemoteTrackSummary>,
    ): Boolean = mutex.withLock {
        val owner = ownerForLocked(token.sourceInstanceId) ?: return@withLock false
        val snapshot = owner.snapshot()
        if (!owner.isCurrent(token) || !snapshot.instance.enabled || snapshot.instance.type != RemoteSourceType.SMB) {
            return@withLock false
        }
        require(tracks.all { it.ref.sourceInstanceId == token.sourceInstanceId }) {
            "SMB folder scope cannot mix source instances"
        }
        require(tracks.map { it.ref.opaqueTrackId }.toSet().size == tracks.size) {
            "SMB folder scope contains duplicate opaque track ids"
        }
        withContext(NonCancellable) {
            database.withTransaction {
                val source = sourceDao.getById(token.sourceInstanceId) ?: return@withTransaction false
                if (source.configRevision != token.configRevision || !owner.isCurrent(token)) {
                    return@withTransaction false
                }

                // Current-schema tests and newly upgraded installs can both have a historical SMB
                // catalog. Pin it once as LEGACY before the first managed scope so scoped cleanup
                // can never reinterpret that old snapshot as deletion evidence.
                if (smbScopeDao.countForSource(source.id) == 0) {
                    val historical = trackDao.getForSource(source.id)
                    if (historical.isNotEmpty()) {
                        val legacyId = "legacy:" + source.id
                        smbScopeDao.putScope(
                            RemoteSmbScopeEntity(
                                id = legacyId,
                                sourceInstanceId = source.id,
                                relativeDirectory = "",
                                includeSubdirectories = true,
                                kind = "LEGACY",
                                observedConfigRevision = source.configRevision,
                                lastCompletedAtMs = source.lastSyncAtMs.coerceAtLeast(0L),
                            ),
                        )
                        historical.chunked(500).forEach { batch ->
                            smbScopeDao.putMembership(
                                batch.map { RemoteSmbScopeTrackEntity(legacyId, it.opaqueTrackId) },
                            )
                        }
                    }
                }

                val existing = smbScopeDao.findManaged(
                    token.sourceInstanceId,
                    relativeDirectory,
                    includeSubdirectories,
                )
                val scopeId = existing?.id ?: ("smb-scope:" + UUID.randomUUID())
                val scope = RemoteSmbScopeEntity(
                    id = scopeId,
                    sourceInstanceId = token.sourceInstanceId,
                    relativeDirectory = relativeDirectory,
                    includeSubdirectories = includeSubdirectories,
                    kind = "MANAGED",
                    observedConfigRevision = token.configRevision,
                    lastCompletedAtMs = nowMs().coerceAtLeast(0L),
                )

                val oldRows = LinkedHashMap<String, com.mica.music.data.local.RemoteTrackEntity>()
                tracks.map { it.ref.opaqueTrackId }.chunked(200).forEach { ids ->
                    trackDao.getByOpaqueIds(source.id, ids).forEach { oldRows[it.opaqueTrackId] = it }
                }
                var nextPosition = trackDao.maxCatalogPosition(source.id) + 1
                val entities = tracks.map { track ->
                    val old = oldRows[track.ref.opaqueTrackId]
                    val oldSummary = old?.toRemoteTrackSummary()
                    val stored = if (
                        oldSummary != null &&
                        track.contentRevision.isNotBlank() &&
                        oldSummary.contentRevision == track.contentRevision &&
                        oldSummary.sizeBytes == track.sizeBytes
                    ) {
                        oldSummary.copy(
                            ref = track.ref,
                            mimeTypeHint = track.mimeTypeHint,
                            fileName = track.fileName,
                            suffix = track.suffix,
                            sizeBytes = track.sizeBytes,
                            contentRevision = track.contentRevision,
                            artworkOpaqueId = track.artworkOpaqueId.ifBlank { oldSummary.artworkOpaqueId },
                        )
                    } else {
                        track
                    }
                    stored.toEntity(old?.catalogPosition ?: nextPosition++)
                }
                entities.chunked(500).forEach { trackDao.insertAll(it) }
                smbScopeDao.putScope(scope)
                smbScopeDao.clearMembership(scopeId)
                tracks.chunked(500).forEach { batch ->
                    smbScopeDao.putMembership(
                        batch.map { RemoteSmbScopeTrackEntity(scopeId, it.ref.opaqueTrackId) },
                    )
                }
                trackDao.deleteUnscopedSmbTracks(source.id)
                check(owner.isCurrent(token)) { "Source changed before SMB folder-scope commit" }
                true
            }
        }
    }

    internal suspend fun removeSmbFolderScope(sourceInstanceId: String, scopeId: String): Boolean = mutex.withLock {
        val owner = ownerForLocked(sourceInstanceId) ?: return@withLock false
        val operation = owner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.SMB || !owner.isCurrent(operation.token)) {
            return@withLock false
        }
        withContext(NonCancellable) {
            database.withTransaction {
                val source = sourceDao.getById(sourceInstanceId) ?: return@withTransaction false
                if (
                    source.configRevision != operation.token.configRevision ||
                    !owner.isCurrent(operation.token)
                ) {
                    return@withTransaction false
                }
                val deleted = smbScopeDao.deleteManaged(scopeId, sourceInstanceId)
                if (deleted != 1) return@withTransaction false
                trackDao.deleteUnscopedSmbTracks(sourceInstanceId)
                check(owner.isCurrent(operation.token)) { "Source changed before SMB folder-scope removal commit" }
                true
            }
        }
    }

    /** Local descriptions for playlist/queue resolution, never published as catalog membership. */
    fun observeSelectedTracks(): Flow<List<RemoteTrackSummary>> = selectedDao.observe().map { rows ->
        rows.map { it.track.toRemoteTrackSummary() }
    }

    suspend fun registerSelectedTracks(token: RemoteOperationToken, tracks: List<RemoteTrackSummary>): Boolean =
        commitSelectedTracks(token, tracks) { true } ?: false

    /** Lock order: playlist mutation (if any) -> remote gate -> Room. No network inside this gate. */
    internal suspend fun <T : Any> commitSelectedTracks(
        token: RemoteOperationToken,
        tracks: List<RemoteTrackSummary>,
        commit: suspend () -> T,
    ): T? = mutex.withLock {
        val owner = ownerForLocked(token.sourceInstanceId) ?: return@withLock null
        if (!owner.isCurrent(token) || !owner.snapshot().instance.enabled) return@withLock null
        require(tracks.all { it.ref.sourceInstanceId == token.sourceInstanceId })
        withContext(NonCancellable) {
            database.withTransaction {
                val source = sourceDao.getById(token.sourceInstanceId) ?: return@withTransaction null
                if (source.configRevision != token.configRevision || !owner.isCurrent(token)) return@withTransaction null
                tracks.chunked(200).forEach { batch ->
                    val ids = batch.map { it.ref.opaqueTrackId }
                    val previous = mutableMapOf<String, RemoteTrackSummary>()
                    if (source.catalogConfigRevision == token.configRevision) {
                        trackDao.getByOpaqueIds(source.id, ids).forEach { previous[it.opaqueTrackId] = it.toRemoteTrackSummary() }
                    }
                    selectedDao.find(source.id, ids).filter { it.observedConfigRevision == token.configRevision }
                        .forEach { previous[it.track.opaqueTrackId] = it.track.toRemoteTrackSummary() }
                    check(owner.isCurrent(token)) { "Source changed before selected-track write" }
                    selectedDao.put(batch.map { track ->
                        val old = previous[track.ref.opaqueTrackId]
                        val kept = old?.takeIf { track.contentRevision.isNotEmpty() &&
                            it.contentRevision == track.contentRevision && it.sizeBytes == track.sizeBytes }
                            ?.copy(artworkOpaqueId = track.artworkOpaqueId.ifBlank { old.artworkOpaqueId }) ?: track
                        RemoteSelectedTrackEntity(kept.toEntity(0), token.configRevision)
                    })
                }
                check(owner.isCurrent(token)) { "Source changed before selection commit" }
                commit()
            }
        }
    }

    internal data class MetadataRequest(val token: RemoteOperationToken, val track: RemoteTrackSummary, val requestId: Long)

    internal suspend fun beginSelectedMetadata(ref: RemoteTrackRef): MetadataRequest? = mutex.withLock {
        val owner = ownerForLocked(ref.sourceInstanceId) ?: return@withLock null
        val operation = owner.beginOperationSnapshot()
        if (!operation.source.instance.enabled || operation.source.instance.type != RemoteSourceType.SMB) return@withLock null
        val row = selectedDao.find(ref.sourceInstanceId, listOf(ref.opaqueTrackId)).singleOrNull() ?: return@withLock null
        if (row.observedConfigRevision != operation.token.configRevision) return@withLock null
        MetadataRequest(operation.token, row.track.toRemoteTrackSummary(), ++metadataRequest)
    }

    internal suspend fun updateSelectedMetadata(request: MetadataRequest, track: RemoteTrackSummary): Boolean = mutex.withLock {
        val owner = ownerForLocked(request.token.sourceInstanceId) ?: return@withLock false
        if (metadataRequest != request.requestId || !owner.isCurrent(request.token)) return@withLock false
        require(track.ref == request.track.ref && track.contentRevision == request.track.contentRevision)
        withContext(NonCancellable) {
            database.withTransaction {
                val row = selectedDao.find(track.ref.sourceInstanceId, listOf(track.ref.opaqueTrackId)).singleOrNull()
                    ?: return@withTransaction false
                if (row.observedConfigRevision != request.token.configRevision ||
                    row.track.contentRevision != request.track.contentRevision || row.track.sizeBytes != request.track.sizeBytes) return@withTransaction false
                if (!owner.isCurrent(request.token)) return@withTransaction false
                selectedDao.put(listOf(RemoteSelectedTrackEntity(track.toEntity(0), request.token.configRevision)))
                true
            }
        }
    }

    /**
     * Artwork provider authorization boundary. A public content URI is readable only while the
     * exact artwork id is referenced by the catalog published for the source's current config.
     */
    internal suspend fun artworkCatalogRevisionIfPublishedForConfig(
        ref: RemoteArtworkRef,
        sourceConfigRevision: Long,
    ): Long? = mutex.withLock {
        val source = sourceDao.getById(ref.sourceInstanceId) ?: return@withLock null
        if (
            !source.enabled ||
            source.configRevision != sourceConfigRevision
        ) {
            return@withLock null
        }
        source.catalogRevision.takeIf {
            (source.catalogConfigRevision == sourceConfigRevision && trackDao.hasArtworkRef(ref.sourceInstanceId, ref.opaqueArtworkId)) ||
                selectedDao.hasArtwork(ref.sourceInstanceId, sourceConfigRevision, ref.opaqueArtworkId)
        }
    }

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

    /** Live aggregate snapshot used by UI surfaces that must follow catalog publication automatically. */
    fun observeTracksForEnabledSources(): Flow<List<RemoteTrackSummary>> =
        trackDao.observeForEnabledSources().map { entities ->
            entities.map { it.toRemoteTrackSummary() }
        }

    override suspend fun find(refs: List<RemoteTrackRef>): Map<RemoteTrackRef, RemoteTrackSummary> {
        if (refs.isEmpty()) return emptyMap()
        val requested = refs.distinct()
        val found = LinkedHashMap<RemoteTrackRef, RemoteTrackSummary>(requested.size)
        requested.groupBy(RemoteTrackRef::sourceInstanceId).forEach { (sourceId, sourceRefs) ->
            val opaqueIds = sourceRefs.map(RemoteTrackRef::opaqueTrackId).distinct()
            opaqueIds.chunked(200).forEach { batch ->
                trackDao.getByOpaqueIds(sourceId, batch).forEach { entity ->
                    val summary = entity.toRemoteTrackSummary()
                    found[summary.ref] = summary
                }
                selectedDao.find(sourceId, batch).forEach { entity ->
                    val summary = entity.track.toRemoteTrackSummary()
                    found[summary.ref] = summary
                }
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
