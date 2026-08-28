package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal data class NavidromeSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
)

/** Full source-scoped Navidrome catalog refresh with one coherent operation/credential snapshot. */
internal class NavidromeSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: SecureRemoteCredentialStore,
    private val executor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
    private val pageSize: Int = NavidromeCatalogPager.DEFAULT_PAGE_SIZE,
) {
    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): NavidromeSyncResult {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw NavidromeException(
                kind = NavidromeFailureKind.PROTOCOL,
                message = "Unknown Navidrome source",
            )
        val session = NavidromeProtocolCatalogApi(
            sourceOwner = owner,
            credentialStore = credentialStore,
            executor = executor,
            requestFactory = requestFactory,
        ).beginSession()
        val tracks = NavidromeCatalogPager(session, pageSize).listSongs(limit)
        val summaries = tracks.map { it.toRemoteTrackSummary(sourceInstanceId) }
        val published = catalogRepository.publishCatalogIfCurrent(
            token = session.operation.token,
            tracks = summaries,
        )
        if (!published) {
            throw NavidromeException(
                kind = NavidromeFailureKind.STALE_OPERATION,
                message = "Navidrome catalog became stale before storage publication",
            )
        }
        return NavidromeSyncResult(
            sourceInstanceId = sourceInstanceId,
            configRevision = session.operation.source.configRevision,
            trackCount = summaries.size,
        )
    }
}
