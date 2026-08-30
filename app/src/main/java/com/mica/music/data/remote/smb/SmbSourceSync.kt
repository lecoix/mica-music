package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_IO_CONCURRENCY
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSidecarArtworkCandidate
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.canUseRemoteFolderArtwork
import com.mica.music.data.remote.isRemoteSidecarArtworkFile
import com.mica.music.data.remote.remoteArtworkRevisionKey
import com.mica.music.data.remote.selectRemoteFolderSidecarArtwork
import com.mica.music.data.remote.selectRemoteTrackSidecarArtwork
import com.mica.music.util.DiagnosticLog
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class SmbSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
    val metadataProbedCount: Int = 0,
    val metadataReusedCount: Int = 0,
)

/**
 * Full source-scoped SMB2/3 refresh.
 *
 * SMBJ owns protocol negotiation/authentication. Mica owns source generation, credential lifetime,
 * bounded traversal and atomic catalog publication. Every blocking SMB operation runs on IO.
 */
internal class SmbSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: SecureRemoteCredentialStore,
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
    private val metadataProbe: RemoteTrackMetadataProbe? = null,
) {
    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        try {
            listDirectory(session, relativeDirectory = "")
            ensureCurrent(session)
        } finally {
            closeSession(session.handle)
        }
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
        allowMetadataReuse: Boolean = true,
    ): SmbSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        try {
            val reusableCatalog = if (allowMetadataReuse) {
                catalogRepository.reusableCatalogIfCurrent(session.operation.token).orEmpty()
            } else {
                emptyMap()
            }
            ensureCurrent(session)
            val scan = if (limit == 0) ScanResult(emptyList(), 0, 0) else scan(session, limit, reusableCatalog)
            ensureCurrent(session)
            val published = catalogRepository.publishCatalogIfCurrent(
                token = session.operation.token,
                tracks = scan.tracks,
            )
            if (!published) {
                throw SmbException(
                    SmbFailureKind.STALE_OPERATION,
                    "SMB catalog became stale before storage publication",
                )
            }
            return SmbSyncResult(
                sourceInstanceId = sourceInstanceId,
                configRevision = session.operation.source.configRevision,
                trackCount = scan.tracks.size,
                metadataProbedCount = scan.metadataProbedCount,
                metadataReusedCount = scan.metadataReusedCount,
            )
        } finally {
            closeSession(session.handle)
        }
    }

    private suspend fun beginSession(sourceInstanceId: String): Session {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw SmbException(SmbFailureKind.PROTOCOL, "Unknown SMB source")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.SMB) {
            throw SmbException(SmbFailureKind.PROTOCOL, "Remote source is not SMB")
        }
        if (!source.enabled) {
            throw SmbException(SmbFailureKind.PROTOCOL, "SMB source is disabled")
        }
        val credential = credentialStore.resolve(source.credentialRef)
            ?: throw SmbException(SmbFailureKind.AUTH, "SMB credential is unavailable")
        val material = credential.material as? RemoteCredentialMaterial.UsernamePassword
            ?: throw SmbException(SmbFailureKind.AUTH, "SMB requires username/password credentials")
        ensureCurrent(owner, operation)

        val endpoint = try {
            SmbPathCodec.parse(source.endpoint)
        } catch (failure: IllegalArgumentException) {
            throw SmbException(SmbFailureKind.PROTOCOL, failure.message ?: "Invalid SMB address", failure)
        }
        val login = SmbLogin.parse(material.username, material.password)
        val handle = withContext(Dispatchers.IO) {
            sessionFactory.open(endpoint, login)
        }
        if (!owner.isCurrent(operation.token)) {
            closeSession(handle)
            throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source changed while connecting")
        }
        return Session(owner, operation, endpoint, handle)
    }

    private suspend fun scan(
        session: Session,
        limit: Int,
        reusableCatalog: Map<String, RemoteTrackSummary>,
    ): ScanResult {
        val sourceId = session.operation.source.instance.id
        val pendingDirectories = ArrayDeque<String>()
        val visitedDirectories = linkedSetOf<String>()
        val candidates = ArrayList<TrackCandidate>()
        pendingDirectories.addLast("")

        // Keep directory traversal single-writer and deterministic. Only file metadata probing below
        // is parallelized, so catalog ordering and generation fencing remain unchanged.
        while (pendingDirectories.isNotEmpty() && candidates.size < limit) {
            ensureCurrent(session)
            val relativeDirectory = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(relativeDirectory)) continue
            val entries = listDirectory(session, relativeDirectory)
            ensureCurrent(session)
            val resolvedEntries = entries
                .asSequence()
                .filterNot { it.name == "." || it.name == ".." }
                .map { entry ->
                    val relativePath = try {
                        SmbPathCodec.appendChild(relativeDirectory, entry.name)
                    } catch (failure: IllegalArgumentException) {
                        throw SmbException(SmbFailureKind.PROTOCOL, "SMB server returned an invalid path", failure)
                    }
                    ResolvedEntry(entry, relativePath)
                }
                .toList()

            resolvedEntries
                .filter { it.entry.isDirectory }
                .forEach { resolved ->
                    if (resolved.relativePath !in visitedDirectories) pendingDirectories.addLast(resolved.relativePath)
                }
            val artworkCandidates = resolvedEntries.mapNotNull { resolved ->
                if (resolved.entry.isDirectory || !isRemoteSidecarArtworkFile(resolved.entry.name)) {
                    return@mapNotNull null
                }
                RemoteSidecarArtworkCandidate(
                    fileName = resolved.entry.name,
                    resourceId = resolved.relativePath,
                    contentRevision = remoteArtworkRevisionKey(
                        resolved.entry.contentRevision,
                        resolved.entry.sizeBytes,
                    ),
                    sizeBytes = resolved.entry.sizeBytes.coerceAtLeast(0L),
                )
            }
            val folderArtwork = selectRemoteFolderSidecarArtwork(artworkCandidates)
            val audioEntries = resolvedEntries.filter { resolved ->
                !resolved.entry.isDirectory && extension(resolved.entry.name) in AUDIO_EXTENSIONS
            }
            val directoryComplete = candidates.size + audioEntries.size <= limit

            for (resolved in audioEntries) {
                if (candidates.size >= limit) break
                val entry = resolved.entry
                val relativePath = resolved.relativePath
                val suffix = extension(entry.name)
                val artwork = selectRemoteTrackSidecarArtwork(entry.name, artworkCandidates)
                val base = RemoteTrackSummary(
                    ref = RemoteTrackRef(sourceId, relativePath),
                    title = entry.name.substringBeforeLast('.', entry.name).ifBlank { entry.name },
                    mimeTypeHint = MIME_BY_EXTENSION[suffix].orEmpty(),
                    fileName = entry.name,
                    suffix = suffix,
                    sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                    contentRevision = entry.contentRevision,
                    artworkOpaqueId = artwork?.let { candidate ->
                        RemoteFileArtworkIdCodec.encode(candidate.resourceId, candidate.contentRevision)
                    }.orEmpty(),
                )
                candidates += TrackCandidate(
                    relativeDirectory = relativeDirectory,
                    relativePath = relativePath,
                    base = base,
                    reusable = reusableCatalog[relativePath],
                    folderArtwork = folderArtwork,
                    directoryComplete = directoryComplete,
                )
            }
        }

        val tracks = candidates.map { it.base }.toMutableList()
        val probeCandidates = ArrayList<IndexedValue<TrackCandidate>>()
        var metadataReusedCount = 0
        candidates.forEachIndexed { index, candidate ->
            if (canReuseMetadata(candidate.base, candidate.reusable)) {
                tracks[index] = reuseMetadata(candidate.base, checkNotNull(candidate.reusable))
                metadataReusedCount++
            } else if (metadataProbe != null) {
                probeCandidates += IndexedValue(index, candidate)
            }
        }

        probeCandidates.chunked(REMOTE_METADATA_IO_CONCURRENCY).forEach { chunk ->
            ensureCurrent(session)
            val enriched = coroutineScope {
                chunk.map { indexed ->
                    async(Dispatchers.IO) {
                        indexed.index to enrichMetadata(
                            session = session,
                            relativePath = indexed.value.relativePath,
                            base = indexed.value.base,
                        )
                    }
                }.awaitAll()
            }
            enriched.forEach { (index, track) -> tracks[index] = track }
            ensureCurrent(session)
        }
        applySafeFolderArtwork(candidates, tracks)

        return ScanResult(
            tracks = tracks,
            metadataProbedCount = probeCandidates.size,
            metadataReusedCount = metadataReusedCount,
        )
    }

    private fun canReuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary?): Boolean =
        previous != null &&
            base.contentRevision.isNotBlank() &&
            previous.contentRevision == base.contentRevision &&
            previous.sizeBytes == base.sizeBytes

    private fun reuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary): RemoteTrackSummary =
        previous.copy(
            ref = base.ref,
            mimeTypeHint = base.mimeTypeHint,
            fileName = base.fileName,
            suffix = base.suffix,
            sizeBytes = base.sizeBytes,
            contentRevision = base.contentRevision,
            artworkOpaqueId = base.artworkOpaqueId,
        )

    private fun applySafeFolderArtwork(
        candidates: List<TrackCandidate>,
        tracks: MutableList<RemoteTrackSummary>,
    ) {
        candidates.indices
            .groupBy { index -> candidates[index].relativeDirectory }
            .values
            .forEach { indices ->
                val first = candidates[indices.first()]
                val artwork = first.folderArtwork ?: return@forEach
                if (!first.directoryComplete) return@forEach
                val directoryTracks = indices.map(tracks::get)
                if (!canUseRemoteFolderArtwork(directoryTracks)) return@forEach
                val artworkId = RemoteFileArtworkIdCodec.encode(artwork.resourceId, artwork.contentRevision)
                indices.forEach { index ->
                    if (tracks[index].artworkOpaqueId.isBlank()) {
                        tracks[index] = tracks[index].copy(artworkOpaqueId = artworkId)
                    }
                }
            }
    }

    private suspend fun enrichMetadata(
        session: Session,
        relativePath: String,
        base: RemoteTrackSummary,
    ): RemoteTrackSummary {
        val probe = metadataProbe ?: return base
        ensureCurrent(session)
        val metadata = withContext(Dispatchers.IO) {
            runCatching {
                val serverPath = session.endpoint.serverPath(relativePath)
                SmbSeekableByteSource(session.handle.openFile(serverPath)).use { source ->
                    probe.probe(base.fileName, source)
                }
            }.onFailure { failure ->
                DiagnosticLog.event(
                    "RemoteMetadata",
                    "smb-probe fallback song=${base.mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
                )
            }.getOrNull()
        }
        ensureCurrent(session)
        return metadata?.let { tags ->
            base.copy(
                title = tags.title.ifBlank { base.title },
                artist = tags.artist,
                album = tags.album,
                albumArtist = tags.albumArtist,
                durationSec = tags.durationSec,
                year = tags.year,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
            )
        } ?: base
    }

    private suspend fun listDirectory(session: Session, relativeDirectory: String): List<SmbDirectoryEntry> {
        ensureCurrent(session)
        val serverPath = session.endpoint.serverPath(relativeDirectory)
        return withContext(Dispatchers.IO) {
            session.handle.list(serverPath)
        }
    }

    private fun ensureCurrent(session: Session) = ensureCurrent(session.owner, session.operation)

    private fun ensureCurrent(owner: RemoteSourceOwner, operation: RemoteOperationSnapshot) {
        if (!owner.isCurrent(operation.token)) {
            throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source changed during operation")
        }
    }

    private suspend fun closeSession(handle: SmbSessionHandle) {
        withContext(Dispatchers.IO) {
            handle.close()
        }
    }

    private fun extension(value: String): String = value
        .substringAfterLast('.', "")
        .lowercase(Locale.US)

    private data class Session(
        val owner: RemoteSourceOwner,
        val operation: RemoteOperationSnapshot,
        val endpoint: SmbEndpoint,
        val handle: SmbSessionHandle,
    )

    private data class TrackCandidate(
        val relativeDirectory: String,
        val relativePath: String,
        val base: RemoteTrackSummary,
        val reusable: RemoteTrackSummary?,
        val folderArtwork: RemoteSidecarArtworkCandidate?,
        val directoryComplete: Boolean,
    )

    private data class ResolvedEntry(
        val entry: SmbDirectoryEntry,
        val relativePath: String,
    )

    private data class ScanResult(
        val tracks: List<RemoteTrackSummary>,
        val metadataProbedCount: Int,
        val metadataReusedCount: Int,
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
        )
        private val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "ape" to "audio/ape",
            "wma" to "audio/x-ms-wma",
            "alac" to "audio/alac",
            "aiff" to "audio/aiff",
            "aif" to "audio/aiff",
        )
    }
}
