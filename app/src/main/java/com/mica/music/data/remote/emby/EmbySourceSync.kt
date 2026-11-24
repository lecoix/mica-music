package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteOperationToken
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.IOException

internal data class EmbySyncResult(val sourceInstanceId: String, val configRevision: Long, val trackCount: Int)

/**
 * Narrow publication seam: the production adapter keeps RemoteCatalogRepository as authority;
 * JVM tests can verify pagination/stale ownership without an unsupported SQLite native runtime.
 */
internal interface EmbyCatalogStore {
    suspend fun sourceOwner(sourceInstanceId: String): RemoteSourceOwner?
    suspend fun publishCatalogIfCurrent(token: RemoteOperationToken, tracks: List<RemoteTrackSummary>): Boolean
}

private class RepositoryEmbyCatalogStore(
    private val repository: RemoteCatalogRepository,
) : EmbyCatalogStore {
    override suspend fun sourceOwner(sourceInstanceId: String) = repository.sourceOwner(sourceInstanceId)
    override suspend fun publishCatalogIfCurrent(token: RemoteOperationToken, tracks: List<RemoteTrackSummary>) =
        repository.publishCatalogIfCurrent(token, tracks)
}

internal class EmbySourceSync(
    private val repository: EmbyCatalogStore,
    private val credentials: SecureRemoteCredentialStore,
    private val transport: EmbyJsonTransport = OkHttpEmbyJsonTransport(),
    private val pageSize: Int = EmbyProtocol.PAGE_SIZE,
) {
    constructor(
        repository: RemoteCatalogRepository,
        credentials: SecureRemoteCredentialStore,
        transport: EmbyJsonTransport = OkHttpEmbyJsonTransport(),
        pageSize: Int = EmbyProtocol.PAGE_SIZE,
    ) : this(RepositoryEmbyCatalogStore(repository), credentials, transport, pageSize)

    init { require(pageSize in 1..EmbyProtocol.PAGE_SIZE) }

    suspend fun sync(sourceInstanceId: String, limit: Int = MAX_TRACKS): EmbySyncResult {
        val owner = repository.sourceOwner(sourceInstanceId) ?: throw IOException("Unknown Emby source")
        val session = EmbyCatalogClient(owner, credentials, transport).beginSession()
        val hardCap = limit.coerceIn(0, MAX_TRACKS)
        val tracks = ArrayList<RemoteTrackSummary>(minOf(hardCap, pageSize))
        val seen = HashSet<String>()
        var offset = 0
        while (tracks.size < hardCap) {
            val requested = minOf(pageSize, hardCap - tracks.size)
            val page = session.page(offset, requested)
            val before = tracks.size
            for (track in page.tracks) {
                if (seen.add(track.ref.opaqueTrackId)) tracks += track
                if (tracks.size == hardCap) break
            }
            // Offset must advance by raw server rows rather than filtered Audio rows.
            if (page.rawCount == 0 || offset + page.rawCount >= page.totalCount) break
            offset += page.rawCount
            // Refuse unbounded paging when a broken server repeats the same page.
            if (tracks.size == before || offset >= MAX_TRACKS * 2) break
        }
        if (!repository.publishCatalogIfCurrent(session.operation.token, tracks)) {
            throw IOException("Emby source changed before catalog publication")
        }
        return EmbySyncResult(sourceInstanceId, session.operation.source.configRevision, tracks.size)
    }

    companion object { const val MAX_TRACKS = EmbyProtocol.MAX_TRACKS }
}
