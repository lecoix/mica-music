package com.mica.music.data.remote.smb

import com.mica.music.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal class SmbOptionalIoDeferredException(message: String) : IOException(message)

/** A single optional SMB read across metadata, lyrics and artwork. Audio transport never takes this lock. */
internal object SmbOptionalIo {
    private val mutex = Mutex()
    @Volatile var bufferingSourceId: String? = null
    @Volatile var currentMediaId: String? = null
    suspend fun <T> run(sourceId: String, block: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (bufferingSourceId == sourceId) throw SmbOptionalIoDeferredException("SMB playback has priority")
        block()
    }

    fun requireCurrent(mediaId: String) {
        if (currentMediaId != null && currentMediaId != mediaId) {
            throw SmbOptionalIoDeferredException("SMB optional read is not for the current song")
        }
    }

    suspend fun <T> runWhenPlaybackReady(
        sourceId: String,
        mediaId: String,
        retryDelayMs: Long = 100L,
        maxAttempts: Int = 150,
        block: suspend () -> T,
    ): T {
        require(retryDelayMs >= 0L) { "retryDelayMs must not be negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        var lastDeferred: SmbOptionalIoDeferredException? = null
        repeat(maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return run(sourceId) {
                    requireCurrent(mediaId)
                    block()
                }
            } catch (deferred: SmbOptionalIoDeferredException) {
                lastDeferred = deferred
                if (attempt + 1 < maxAttempts) delay(retryDelayMs)
            }
        }
        throw checkNotNull(lastDeferred)
    }

    suspend fun <T> runWhenSourceReady(
        sourceId: String,
        retryDelayMs: Long = 100L,
        maxAttempts: Int = 150,
        block: suspend () -> T,
    ): T {
        require(retryDelayMs >= 0L) { "retryDelayMs must not be negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        var lastDeferred: SmbOptionalIoDeferredException? = null
        repeat(maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return run(sourceId, block)
            } catch (deferred: SmbOptionalIoDeferredException) {
                lastDeferred = deferred
                if (attempt + 1 < maxAttempts) delay(retryDelayMs)
            }
        }
        throw checkNotNull(lastDeferred)
    }
}

internal class SmbNowPlayingMetadata(
    private val repository: RemoteCatalogRepository,
    private val credentials: SecureRemoteCredentialStore,
    private val probe: RemoteTrackMetadataProbe,
    private val sessions: SmbSessionFactory = SmbjSessionFactory(),
) {
    suspend fun load(mediaId: String): RemoteTrackSummary? {
        val ref = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val request = repository.beginSelectedMetadata(ref) ?: return null
        val base = request.track
        if (base.metadataProbeRevision == REMOTE_METADATA_PROBE_REVISION && base.contentRevision.isNotBlank()) return base
        val owner = repository.sourceOwner(ref.sourceInstanceId) ?: return null
        val source = owner.snapshot().instance
        val credential = credentials.resolve(source.credentialRef) ?: return null
        val login = SmbLogin.from(credential.material) ?: return null
        val endpoint = SmbPathCodec.parse(source.endpoint)
        if (!owner.isCurrent(request.token)) return null
        val metadata = SmbOptionalIo.run(source.id) {
            SmbOptionalIo.requireCurrent(mediaId)
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                sessions.open(endpoint, login).useReadSession(
                    onCloseFailure = { failure ->
                        com.mica.music.util.DiagnosticLog.important(
                            "SmbCleanup",
                            "metadata session close failed after successful read",
                            failure,
                        )
                    },
                ) { session ->
                    context.ensureActive()
                    if (!owner.isCurrent(request.token)) return@withContext null
                    session.openFile(endpoint.serverPath(ref.opaqueTrackId)).use { file ->
                        context.ensureActive()
                        if (!owner.isCurrent(request.token) || file.length != base.sizeBytes) return@withContext null
                        probe.probe(base.fileName, SmbSeekableByteSource(file))
                    }
                }
            }
        } ?: return null
        currentCoroutineContext().ensureActive()
        val enriched = base.copy(
            title = metadata.title.ifBlank { base.title }, artist = metadata.artist,
            album = metadata.album, albumArtist = metadata.albumArtist, durationSec = metadata.durationSec,
            sampleRateHz = metadata.sampleRateHz, bitsPerSample = metadata.bitsPerSample,
            bitrateKbps = metadata.bitrateKbps, channelCount = metadata.channelCount,
            year = metadata.year, trackNumber = metadata.trackNumber, discNumber = metadata.discNumber,
            metadataProbeRevision = REMOTE_METADATA_PROBE_REVISION,
            artworkOpaqueId = base.artworkOpaqueId.ifBlank {
                if (metadata.hasEmbeddedArtwork) RemoteEmbeddedArtworkIdCodec.encode(ref.opaqueTrackId, base.contentRevision, base.sizeBytes) else ""
            },
        )
        return enriched.takeIf { repository.updateSelectedMetadata(request, it) }
    }
}
