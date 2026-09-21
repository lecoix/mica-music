package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSidecarArtworkCandidate
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.remote.isRemoteSidecarArtworkFile
import com.mica.music.data.remote.remoteArtworkRevisionKey
import com.mica.music.data.remote.selectRemoteTrackSidecarArtwork
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SmbFolderIndexResult(
    val complete: Boolean,
    val trackCount: Int,
    val published: Boolean,
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
 * Explicit folder-to-library indexing. Discovery is lightweight: directory entries only.
 * An incomplete traversal is never published, so a timeout/budget stop cannot become deletion evidence.
 */
internal class SmbFolderLibraryIndexer(
    private val repository: RemoteCatalogRepository,
    private val credentials: SecureRemoteCredentialStore,
    private val sessions: SmbSessionFactory = SmbjSessionFactory(),
) {
    suspend fun index(
        sourceId: String,
        directory: String,
        includeSubdirectories: Boolean,
    ): SmbFolderIndexResult {
        val owner = repository.sourceOwner(sourceId)
            ?: throw SmbException(SmbFailureKind.PROTOCOL, "Unknown SMB source")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        require(source.type == RemoteSourceType.SMB && source.enabled)
        val root = SmbPathCodec.normalizeRelativePath(directory)
        val endpoint = SmbPathCodec.parse(source.endpoint)
        val credential = credentials.resolve(source.credentialRef)
            ?: throw SmbException(SmbFailureKind.AUTH, "Credential unavailable")
        val login = SmbLogin.from(credential.material)
            ?: throw SmbException(SmbFailureKind.AUTH, "Invalid credential")

        fun ensureCurrent() {
            if (!owner.isCurrent(operation.token)) {
                throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed during folder index")
            }
        }

        ensureCurrent()
        val discovery = withContext(Dispatchers.IO) {
            discover(
                owner = owner,
                operation = operation,
                endpoint = endpoint,
                login = login,
                root = root,
                includeSubdirectories = includeSubdirectories,
            )
        }
        ensureCurrent()
        if (!discovery.complete) {
            return SmbFolderIndexResult(complete = false, trackCount = discovery.tracks.size, published = false)
        }
        val published = repository.replaceSmbFolderScopeIfCurrent(
            token = operation.token,
            relativeDirectory = root,
            includeSubdirectories = includeSubdirectories,
            tracks = discovery.tracks,
        )
        return SmbFolderIndexResult(discovery.complete, discovery.tracks.size, published)
    }

    private suspend fun discover(
        owner: RemoteSourceOwner,
        operation: RemoteOperationSnapshot,
        endpoint: SmbEndpoint,
        login: SmbLogin,
        root: String,
        includeSubdirectories: Boolean,
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

        sessions.open(endpoint, login).use { session ->
            while (pending.isNotEmpty() && complete) {
                context.ensureActive()
                ensureCurrent(owner, operation)
                val directory = pending.removeFirst()
                if (!visitedDirectories.add(directory)) continue
                val localTracks = ArrayList<RemoteTrackSummary>()
                val artworks = ArrayList<RemoteSidecarArtworkCandidate>()
                session.visit(endpoint.serverPath(directory)) { entry ->
                    context.ensureActive()
                    ensureCurrent(owner, operation)
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
                localTracks.forEachIndexed { index, track ->
                    val artwork = selectRemoteTrackSidecarArtwork(track.fileName, artworks)
                    if (artwork != null) {
                        localTracks[index] = track.copy(
                            artworkOpaqueId = RemoteFileArtworkIdCodec.encode(
                                artwork.resourceId,
                                artwork.contentRevision,
                            ),
                        )
                    }
                }
                tracks += localTracks
            }
        }
        context.ensureActive()
        ensureCurrent(owner, operation)
        tracks.sortWith(compareBy<RemoteTrackSummary>({ it.ref.opaqueTrackId.lowercase(Locale.ROOT) }, { it.ref.opaqueTrackId }))
        return Discovery(tracks, complete)
    }

    private fun ensureCurrent(owner: RemoteSourceOwner, operation: RemoteOperationSnapshot) {
        if (!owner.isCurrent(operation.token)) {
            throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed during folder index")
        }
    }

    private data class Discovery(val tracks: List<RemoteTrackSummary>, val complete: Boolean)

    companion object {
        const val MAX_TRACKS = 50_000
        const val MAX_VISITED_ENTRIES = 100_000
        const val MAX_DESCRIPTOR_BYTES = 64L * 1024L * 1024L
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
