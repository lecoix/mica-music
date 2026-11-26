package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.REMOTE_METADATA_IO_CONCURRENCY
import com.mica.music.data.remote.REMOTE_METADATA_PROBE_REVISION
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteLyricsSidecarCandidate
import com.mica.music.data.remote.RemoteLyricsSidecarIndex
import com.mica.music.data.remote.RemoteSidecarArtworkCandidate
import com.mica.music.data.remote.RemoteSidecarArtworkIndex
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackMetadataProbe
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.isRemoteSidecarArtworkFile
import com.mica.music.data.remote.isRemoteLyricsSidecarFile
import com.mica.music.data.remote.remoteArtworkRevisionKey
import com.mica.music.data.remote.remoteTrackLyricsRevision
import com.mica.music.util.DiagnosticLog
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Validity is mutated only by RemoteCatalogRepository under its publication mutex. */
internal class SmbFolderIndexRequest(val operation: RemoteOperationSnapshot, val id: Long) {
    @Volatile var valid: Boolean = true
        internal set
}

internal data class SmbFolderIndexResult(
    val complete: Boolean,
    val trackCount: Int,
    val published: Boolean,
    val metadataProbedCount: Int = 0,
    val metadataReusedCount: Int = 0,
    val metadataFailedCount: Int = 0,
)

internal data class SmbFolderScope(
    val id: String,
    val sourceInstanceId: String,
    val relativeDirectory: String,
    val includeSubdirectories: Boolean,
    val legacySnapshot: Boolean,
    val observedConfigRevision: Long,
    val lastCompletedAtMs: Long,
)

/**
 * Explicit folder-to-library indexing. Directory discovery stays lightweight; only after the
 * selected scope has been completely discovered may its audio files be opened for metadata.
 * An incomplete traversal is never probed or published, so a timeout/budget stop cannot become
 * deletion evidence or unexpected payload I/O.
 */
internal class SmbFolderLibraryIndexer(
    private val repository: RemoteCatalogRepository,
    private val credentials: SecureRemoteCredentialStore,
    private val sessions: SmbSessionFactory = SmbjSessionFactory(),
    private val metadataProbe: RemoteTrackMetadataProbe? = null,
    private val probeCheckpointBatchSize: Int = DEFAULT_PROBE_CHECKPOINT_BATCH_SIZE,
) {
    suspend fun index(
        sourceId: String,
        directory: String,
        includeSubdirectories: Boolean,
    ): SmbFolderIndexResult {
        val request = repository.beginSmbFolderIndex(sourceId)
            ?: throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source unavailable")
        return index(request, directory, includeSubdirectories)
    }

    suspend fun indexStaged(
        sourceId: String,
        directory: String,
        includeSubdirectories: Boolean,
        onProbeProgress: (suspend (probed: Int, total: Int) -> Unit)? = null,
        onEntriesPublished: suspend (Int) -> Unit,
    ): SmbFolderIndexResult {
        val request = repository.beginSmbFolderIndex(sourceId)
            ?: throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source unavailable")
        return index(request, directory, includeSubdirectories, onProbeProgress, onEntriesPublished)
    }

    internal suspend fun index(
        request: SmbFolderIndexRequest,
        directory: String,
        includeSubdirectories: Boolean,
        onProbeProgress: (suspend (probed: Int, total: Int) -> Unit)? = null,
        onEntriesPublished: (suspend (Int) -> Unit)? = null,
    ): SmbFolderIndexResult {
        val operation = request.operation
        val sourceId = operation.token.sourceInstanceId
        val owner = repository.sourceOwner(sourceId)
            ?: throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source unavailable")
        val source = operation.source.instance
        require(source.type == RemoteSourceType.SMB && source.enabled)
        val root = SmbPathCodec.normalizeRelativePath(directory)
        val endpoint = SmbPathCodec.parse(source.endpoint)
        val credential = credentials.resolve(source.credentialRef)
            ?: throw SmbException(SmbFailureKind.AUTH, "Credential unavailable")
        val login = SmbLogin.from(credential.material)
            ?: throw SmbException(SmbFailureKind.AUTH, "Invalid credential")

        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!request.valid || !owner.isCurrent(operation.token)) {
                throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed during folder index")
            }
        }

        ensureCurrent()
        var entriesPublished = false
        val discovery = withContext(Dispatchers.IO) {
            discover(
                owner = owner,
                operation = operation,
                endpoint = endpoint,
                login = login,
                root = root,
                includeSubdirectories = includeSubdirectories,
                request = request,
                publishEntries = if (onEntriesPublished == null) null else { tracks ->
                    ensureCurrent()
                    if (!repository.replaceSmbFolderScopeIfCurrent(operation.token, root, includeSubdirectories, tracks, request)) {
                        throw SmbException(SmbFailureKind.STALE_OPERATION, "Stale folder entries")
                    }
                    entriesPublished = true
                    ensureCurrent()
                    onEntriesPublished(tracks.size)
                    ensureCurrent()
                },
                publishCheckpoint = if (onEntriesPublished == null || probeCheckpointBatchSize <= 0) null else { tracks, probed, total ->
                    ensureCurrent()
                    if (!repository.replaceSmbFolderScopeIfCurrent(operation.token, root, includeSubdirectories, tracks, request)) {
                        throw SmbException(SmbFailureKind.STALE_OPERATION, "Stale folder checkpoint")
                    }
                    ensureCurrent()
                    onProbeProgress?.invoke(probed, total)
                    ensureCurrent()
                },
            )
        }
        ensureCurrent()
        if (!discovery.complete) {
            return SmbFolderIndexResult(
                complete = false,
                trackCount = discovery.tracks.size,
                published = false,
                metadataProbedCount = 0,
                metadataReusedCount = 0,
            )
        }
        val published = if (entriesPublished && discovery.metadataProbedCount == 0) {
            ensureCurrent()
            true
        } else {
            repository.replaceSmbFolderScopeIfCurrent(
                token = operation.token,
                relativeDirectory = root,
                includeSubdirectories = includeSubdirectories,
                tracks = discovery.tracks,
                request = request,
            )
        }
        ensureCurrent()
        if (published && !entriesPublished) onEntriesPublished?.invoke(discovery.tracks.size)
        return SmbFolderIndexResult(
            complete = discovery.complete,
            trackCount = discovery.tracks.size,
            published = published,
            metadataProbedCount = discovery.metadataProbedCount,
            metadataReusedCount = discovery.metadataReusedCount,
            metadataFailedCount = discovery.metadataFailedCount,
        )
    }

    private suspend fun discover(
        owner: RemoteSourceOwner,
        operation: RemoteOperationSnapshot,
        endpoint: SmbEndpoint,
        login: SmbLogin,
        root: String,
        includeSubdirectories: Boolean,
        request: SmbFolderIndexRequest,
        publishEntries: (suspend (List<RemoteTrackSummary>) -> Unit)?,
        publishCheckpoint: (suspend (List<RemoteTrackSummary>, Int, Int) -> Unit)?,
    ): Discovery {
        val context = currentCoroutineContext()
        val startedNanos = System.nanoTime()
        val pending = ArrayDeque<String>()
        val visitedDirectories = HashSet<String>()
        val tracks = ArrayList<RemoteTrackSummary>()
        var visitedEntries = 0
        var descriptorBytes = 0L
        var complete = true
        pending.add(root)

        sessions.open(endpoint, login).useReadSession(
            onCloseFailure = { failure ->
                com.mica.music.util.DiagnosticLog.important(
                    "SmbCleanup",
                    "folder index session close failed after successful discovery",
                    failure,
                )
            },
        ) { session ->
            while (pending.isNotEmpty() && complete) {
                context.ensureActive()
                ensureCurrent(owner, operation, request)
                val directory = pending.removeFirst()
                if (!visitedDirectories.add(directory)) continue
                val localTracks = ArrayList<RemoteTrackSummary>()
                val artworks = ArrayList<RemoteSidecarArtworkCandidate>()
                val lyrics = ArrayList<RemoteLyricsSidecarCandidate>()
                session.visit(endpoint.serverPath(directory)) { entry ->
                    context.ensureActive()
                    ensureCurrent(owner, operation, request)
                    if (System.nanoTime() - startedNanos >= MAX_INDEX_NANOS) {
                        complete = false
                        return@visit false
                    }
                    visitedEntries++
                    if (visitedEntries > MAX_VISITED_ENTRIES) {
                        complete = false
                        return@visit false
                    }
                    if (entry.name == "." || entry.name == "..") return@visit true
                    val child = try {
                        SmbPathCodec.appendChild(directory, entry.name)
                    } catch (failure: IllegalArgumentException) {
                        throw SmbException(SmbFailureKind.PROTOCOL, "SMB server returned an invalid path", failure)
                    }
                    descriptorBytes += 1024L + 8L * (child.length + entry.name.length + entry.contentRevision.length)
                    if (descriptorBytes > MAX_DESCRIPTOR_BYTES || tracks.size + localTracks.size >= MAX_TRACKS) {
                        complete = false
                        return@visit false
                    }
                    if (entry.isDirectory) {
                        if (includeSubdirectories) pending.addLast(child)
                        return@visit true
                    }
                    val suffix = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (isRemoteSidecarArtworkFile(entry.name)) {
                        artworks += RemoteSidecarArtworkCandidate(
                            fileName = entry.name,
                            resourceId = child,
                            contentRevision = remoteArtworkRevisionKey(entry.contentRevision, entry.sizeBytes),
                            sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                        )
                        return@visit true
                    }
                    if (isRemoteLyricsSidecarFile(entry.name)) {
                        lyrics += RemoteLyricsSidecarCandidate(
                            fileName = entry.name,
                            resourceId = child,
                            contentRevision = entry.contentRevision,
                            sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                        )
                        return@visit true
                    }
                    if (suffix !in AUDIO_MIME) return@visit true
                    localTracks += RemoteTrackSummary(
                        ref = RemoteTrackRef(operation.token.sourceInstanceId, child),
                        title = entry.name.substringBeforeLast('.', entry.name).ifBlank { entry.name },
                        mimeTypeHint = AUDIO_MIME[suffix].orEmpty(),
                        fileName = entry.name,
                        suffix = suffix,
                        sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                        contentRevision = entry.contentRevision,
                    )
                    true
                }
                if (!complete) break
                val artworkIndex = RemoteSidecarArtworkIndex.from(artworks)
                val lyricsIndex = RemoteLyricsSidecarIndex.from(lyrics)
                localTracks.forEachIndexed { index, track ->
                    val artwork = artworkIndex.selectForTrack(track.fileName)
                    localTracks[index] = track.copy(
                        artworkOpaqueId = artwork?.let {
                            RemoteFileArtworkIdCodec.encode(it.resourceId, it.contentRevision)
                        }.orEmpty(),
                        lyricsRevision = remoteTrackLyricsRevision(
                            fileName = track.fileName,
                            resourceId = track.ref.opaqueTrackId,
                            contentRevision = track.contentRevision,
                            sizeBytes = track.sizeBytes,
                            index = lyricsIndex,
                        ),
                    )
                }
                tracks += localTracks
            }

            if (complete && metadataProbe != null && tracks.isNotEmpty()) {
                tracks.sortWith(compareBy<RemoteTrackSummary>({ it.ref.opaqueTrackId.lowercase(Locale.ROOT) }, { it.ref.opaqueTrackId }))
                val reusable = repository.reusableSmbTracksIfCurrent(
                    token = operation.token,
                    refs = tracks.map(RemoteTrackSummary::ref),
                ).orEmpty()
                val probeCandidates = ArrayList<IndexedValue<RemoteTrackSummary>>()
                var reusedCount = 0
                tracks.forEachIndexed { index, base ->
                    val previous = reusable[base.ref.opaqueTrackId]
                    if (canReuseMetadata(base, previous)) {
                        tracks[index] = reuseMetadata(base, checkNotNull(previous))
                        reusedCount++
                    } else {
                        probeCandidates += IndexedValue(index, base)
                    }
                }
                context.ensureActive()
                ensureCurrent(owner, operation, request)
                publishEntries?.invoke(tracks)
                context.ensureActive()
                ensureCurrent(owner, operation, request)

                if (probeCandidates.isNotEmpty()) {
                    val trackSlots = AtomicReferenceArray<RemoteTrackSummary>(tracks.size)
                    tracks.forEachIndexed { index, track -> trackSlots.set(index, track) }
                    val cursor = AtomicInteger(0)
                    val completedTotal = AtomicInteger(0)
                    val completedSinceCheckpoint = AtomicInteger(0)
                    val checkpointMutex = Mutex()
                    val workerCount = minOf(REMOTE_METADATA_IO_CONCURRENCY, probeCandidates.size)
                    coroutineScope {
                        List(workerCount) {
                            async(Dispatchers.IO) {
                                while (true) {
                                    context.ensureActive()
                                    ensureCurrent(owner, operation, request)
                                    val next = cursor.getAndIncrement()
                                    if (next >= probeCandidates.size) break
                                    val indexed = probeCandidates[next]
                                    val track = enrichMetadata(
                                        session = session,
                                        endpoint = endpoint,
                                        relativePath = indexed.value.ref.opaqueTrackId,
                                        base = indexed.value,
                                    )
                                    context.ensureActive()
                                    ensureCurrent(owner, operation, request)
                                    trackSlots.set(indexed.index, track)
                                    val doneCount = completedTotal.incrementAndGet()
                                    val sinceCheckpoint = completedSinceCheckpoint.incrementAndGet()
                                    if (
                                        publishCheckpoint != null &&
                                        probeCheckpointBatchSize > 0 &&
                                        sinceCheckpoint >= probeCheckpointBatchSize &&
                                        doneCount < probeCandidates.size
                                    ) {
                                        checkpointMutex.withLock {
                                            if (completedSinceCheckpoint.get() >= probeCheckpointBatchSize) {
                                                completedSinceCheckpoint.set(0)
                                                context.ensureActive()
                                                ensureCurrent(owner, operation, request)
                                                val snapshot = List(trackSlots.length()) { i -> trackSlots.get(i) }
                                                publishCheckpoint(snapshot, completedTotal.get(), probeCandidates.size)
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    context.ensureActive()
                    ensureCurrent(owner, operation, request)
                    for (i in 0 until trackSlots.length()) {
                        tracks[i] = trackSlots.get(i)
                    }
                }
                val failedCount = probeCandidates.count { tracks[it.index].metadataProbeRevision != REMOTE_METADATA_PROBE_REVISION }
                return Discovery(
                    tracks = tracks,
                    complete = true,
                    metadataProbedCount = probeCandidates.size,
                    metadataReusedCount = reusedCount,
                    metadataFailedCount = failedCount,
                )
            }
        }
        context.ensureActive()
        ensureCurrent(owner, operation, request)
        tracks.sortWith(compareBy<RemoteTrackSummary>({ it.ref.opaqueTrackId.lowercase(Locale.ROOT) }, { it.ref.opaqueTrackId }))
        return Discovery(tracks, complete, metadataProbedCount = 0, metadataReusedCount = 0)
    }

    private fun canReuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary?): Boolean =
        previous != null &&
            base.contentRevision.isNotBlank() &&
            previous.contentRevision == base.contentRevision &&
            previous.sizeBytes == base.sizeBytes &&
            previous.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION

    private fun reuseMetadata(base: RemoteTrackSummary, previous: RemoteTrackSummary): RemoteTrackSummary {
        val artworkId = base.artworkOpaqueId.ifBlank {
            previous.artworkOpaqueId.takeIf { RemoteEmbeddedArtworkIdCodec.decode(it) != null }.orEmpty()
        }
        return previous.copy(
            ref = base.ref,
            mimeTypeHint = base.mimeTypeHint,
            fileName = base.fileName,
            suffix = base.suffix,
            sizeBytes = base.sizeBytes,
            contentRevision = base.contentRevision,
            artworkOpaqueId = artworkId,
            lyricsRevision = base.lyricsRevision,
        )
    }

    private fun enrichMetadata(
        session: SmbSessionHandle,
        endpoint: SmbEndpoint,
        relativePath: String,
        base: RemoteTrackSummary,
    ): RemoteTrackSummary {
        val probe = metadataProbe ?: return base
        val metadata = runCatching {
            SmbSeekableByteSource(session.openFile(endpoint.serverPath(relativePath))).use { source ->
                probe.probe(base.fileName, source)
            }
        }.onFailure { failure ->
            DiagnosticLog.event(
                "RemoteMetadata",
                "smb-folder-probe fallback song=${base.mediaId.takeLast(12)} error=${failure.javaClass.simpleName}",
            )
        }.getOrNull()
        return metadata?.let { tags ->
            base.copy(
                title = tags.title.ifBlank { base.title },
                artist = tags.artist,
                album = tags.album,
                albumArtist = tags.albumArtist,
                durationSec = tags.durationSec,
                sampleRateHz = tags.sampleRateHz,
                bitsPerSample = tags.bitsPerSample,
                bitrateKbps = tags.bitrateKbps,
                channelCount = tags.channelCount,
                year = tags.year,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
                metadataProbeRevision = REMOTE_METADATA_PROBE_REVISION,
                artworkOpaqueId = base.artworkOpaqueId.ifBlank {
                    if (tags.hasEmbeddedArtwork) {
                        RemoteEmbeddedArtworkIdCodec.encode(relativePath, base.contentRevision, base.sizeBytes)
                    } else {
                        ""
                    }
                },
            )
        } ?: base
    }

    private fun ensureCurrent(owner: RemoteSourceOwner, operation: RemoteOperationSnapshot, request: SmbFolderIndexRequest) {
        if (!request.valid || !owner.isCurrent(operation.token)) {
            throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed during folder index")
        }
    }

    private data class Discovery(
        val tracks: List<RemoteTrackSummary>,
        val complete: Boolean,
        val metadataProbedCount: Int,
        val metadataReusedCount: Int,
        val metadataFailedCount: Int = 0,
    )

    companion object {
        const val MAX_TRACKS = 50_000
        const val MAX_VISITED_ENTRIES = 100_000
        const val MAX_DESCRIPTOR_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_PROBE_CHECKPOINT_BATCH_SIZE = 64
        private const val MAX_INDEX_NANOS = 30_000_000_000L
        private val AUDIO_MIME = mapOf(
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
