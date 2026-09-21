package com.mica.music.data.remote.smb

import com.mica.music.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class SmbBrowseEntry(val path: String, val name: String, val directory: Boolean, val track: RemoteTrackSummary?)
internal data class SmbDirectorySnapshot(
    val token: RemoteOperationToken,
    val path: String,
    val entries: List<SmbBrowseEntry>,
    val complete: Boolean,
)
internal data class SmbBrowseState(
    val snapshot: SmbDirectorySnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One visible directory, with request fencing independent of the source/playback generation. */
internal class SmbBrowseOwner(
    private val publish: suspend (SmbDirectorySnapshot, () -> Unit) -> Boolean = { _, action -> action(); true },
    private val fetch: suspend (String, String) -> SmbDirectorySnapshot,
) {
    private val gate = Any()
    private var request = 0L
    private var closed = false
    private val mutableState = MutableStateFlow(SmbBrowseState())
    val state = mutableState.asStateFlow()

    suspend fun load(source: String, path: String) {
        val id = synchronized(gate) {
            if (closed) return
            request++
            val kept = mutableState.value.snapshot?.takeIf { it.token.sourceInstanceId == source && it.path == path }
            mutableState.value = SmbBrowseState(kept, loading = true)
            request
        }
        try {
            val result = fetch(source, path)
            currentCoroutineContext().ensureActive()
            val accepted = publish(result) {
                synchronized(gate) {
                    if (!closed && id == request) mutableState.value = SmbBrowseState(result)
                }
            }
            if (!accepted) throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed before publication")
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            synchronized(gate) {
                if (!closed && id == request) mutableState.value = mutableState.value.copy(
                    loading = false, error = when ((failure as? SmbException)?.kind) {
                        SmbFailureKind.AUTH -> "登录失败，请检查账号或匿名访问权限"
                        SmbFailureKind.STALE_OPERATION -> "连接配置已改变，请刷新"
                        SmbFailureKind.PROTOCOL -> "共享或目录不可用"
                        else -> "读取失败，请检查网络后重试"
                    },
                )
            }
        }
    }

    fun isCurrent(snapshot: SmbDirectorySnapshot): Boolean = synchronized(gate) {
        !closed && !mutableState.value.loading && mutableState.value.snapshot === snapshot
    }

    fun close() = synchronized(gate) {
        closed = true
        request++
        mutableState.value = SmbBrowseState()
    }
}

internal class SmbDirectoryBrowser(
    private val repository: RemoteCatalogRepository,
    private val credentials: SecureRemoteCredentialStore,
    private val sessions: SmbSessionFactory = SmbjSessionFactory(),
) {
    suspend fun list(sourceId: String, directory: String): SmbDirectorySnapshot {
        val owner = repository.sourceOwner(sourceId) ?: error("Unknown SMB source")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        require(source.type == RemoteSourceType.SMB && source.enabled)
        val path = SmbPathCodec.normalizeRelativePath(directory)
        val endpoint = SmbPathCodec.parse(source.endpoint)
        val credential = credentials.resolve(source.credentialRef)
            ?: throw SmbException(SmbFailureKind.AUTH, "Credential unavailable")
        val login = SmbLogin.from(credential.material) ?: throw SmbException(SmbFailureKind.AUTH, "Invalid credential")
        fun checkCurrent() {
            if (!owner.isCurrent(operation.token)) throw SmbException(SmbFailureKind.STALE_OPERATION, "Source changed")
        }
        checkCurrent()
        val result = withContext(Dispatchers.IO) {
            val startedNanos = System.nanoTime()
            val context = currentCoroutineContext()
            val entries = ArrayList<SmbBrowseEntry>()
            val artworks = ArrayList<RemoteSidecarArtworkCandidate>()
            var estimatedBytes = 0L
            var visited = 0
            var complete = true
            sessions.open(endpoint, login).use { session ->
                checkCurrent()
                session.visit(endpoint.serverPath(path)) { entry ->
                    context.ensureActive()
                    checkCurrent()
                    if (System.nanoTime() - startedNanos >= MAX_BROWSE_NANOS) { complete = false; return@visit false }
                    visited++
                    if (visited > MAX_VISITED_ENTRIES) { complete = false; return@visit false }
                    if (entry.name == "." || entry.name == "..") return@visit true
                    val suffix = entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    val isArtwork = !entry.isDirectory && isRemoteSidecarArtworkFile(entry.name)
                    if (!entry.isDirectory && suffix !in AUDIO_MIME && !isArtwork) return@visit true
                    val child = SmbPathCodec.appendChild(path, entry.name)
                    // Conservative descriptor allowance, independent of lyrics/artwork/file sizes.
                    estimatedBytes += 1024L + 8L * (child.length + entry.name.length + entry.contentRevision.length)
                    if (entries.size + artworks.size >= MAX_ENTRIES || estimatedBytes > MAX_DESCRIPTOR_BYTES) {
                        complete = false
                        return@visit false
                    }
                    if (isArtwork) {
                        artworks += RemoteSidecarArtworkCandidate(entry.name, child,
                            remoteArtworkRevisionKey(entry.contentRevision, entry.sizeBytes), entry.sizeBytes)
                        return@visit true
                    }
                    val track = if (entry.isDirectory) null else RemoteTrackSummary(
                        ref = RemoteTrackRef(sourceId, child),
                        title = entry.name.substringBeforeLast('.').ifBlank { entry.name },
                        fileName = entry.name, suffix = suffix, mimeTypeHint = AUDIO_MIME[suffix].orEmpty(),
                        sizeBytes = entry.sizeBytes, contentRevision = entry.contentRevision,
                    )
                    entries += SmbBrowseEntry(child, entry.name, entry.isDirectory, track)
                    true
                }
            }
            context.ensureActive()
            checkCurrent()
            val artworkByStem = artworks.groupBy { it.fileName.substringBeforeLast('.').lowercase(java.util.Locale.ROOT) }
            entries.indices.forEach { index ->
                val entry = entries[index]
                val track = entry.track ?: return@forEach
                val art = selectRemoteTrackSidecarArtwork(entry.name,
                    artworkByStem[entry.name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)].orEmpty())
                if (art != null) entries[index] = entry.copy(track = track.copy(
                    artworkOpaqueId = RemoteFileArtworkIdCodec.encode(art.resourceId, art.contentRevision)))
            }
            entries.sortWith { a, b ->
                if (a.directory != b.directory) { if (a.directory) -1 else 1 }
                else naturalFileCompare(a.name, b.name).takeIf { it != 0 } ?: a.path.compareTo(b.path)
            }
            com.mica.music.util.DiagnosticLog.event("SmbBrowse",
                "source=$sourceId visited=$visited visible=${entries.size} complete=$complete payloadOpens=0 elapsedMs=${(System.nanoTime() - startedNanos) / 1_000_000}")
            SmbDirectorySnapshot(operation.token, path, entries, complete)
        }
        checkCurrent()
        return result
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_VISITED_ENTRIES = 100_000
        const val MAX_DESCRIPTOR_BYTES = 32L * 1024L * 1024L
        private const val MAX_BROWSE_NANOS = 30_000_000_000L
        private val AUDIO_MIME = mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4",
            "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav",
            "ape" to "audio/ape", "wma" to "audio/x-ms-wma", "alac" to "audio/alac", "aiff" to "audio/aiff", "aif" to "audio/aiff")
    }
}

/** Numeric runs compare without integer overflow or allocating BigIntegers. */
internal fun naturalFileCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i] in '0'..'9' && b[j] in '0'..'9') {
            var x = i
            var y = j
            while (x < a.length && a[x] in '0'..'9') x++
            while (y < b.length && b[y] in '0'..'9') y++
            while (i < x - 1 && a[i] == '0') i++
            while (j < y - 1 && b[j] == '0') j++
            if (x - i != y - j) return (x - i).compareTo(y - j)
            while (i < x) { if (a[i] != b[j]) return a[i].compareTo(b[j]); i++; j++ }
        } else {
            val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}
