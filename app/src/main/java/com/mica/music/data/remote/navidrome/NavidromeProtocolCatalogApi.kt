package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal class NavidromeProtocolCatalogApi(
    private val sourceOwner: RemoteSourceOwner,
    private val credentialStore: SecureRemoteCredentialStore,
    private val executor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) : NavidromeCatalogApi {
    suspend fun ping() {
        executeCurrent(
            request = requestFactory::ping,
            parse = NavidromeJsonParser::validateResponse,
        )
    }

    override suspend fun searchAllSongsPage(offset: Int, count: Int): NavidromeSongPage =
        executeCurrent(
            request = { source, credential ->
                requestFactory.searchAllSongsPage(source, credential, offset, count)
            },
            parse = NavidromeJsonParser::searchSongsPage,
        )

    override suspend fun albumIdsPage(offset: Int, count: Int): List<String> =
        executeCurrent(
            request = { source, credential ->
                requestFactory.albumIdsPage(source, credential, offset, count)
            },
            parse = NavidromeJsonParser::albumIds,
        )

    override suspend fun albumSongs(albumId: String): List<NavidromeTrack> =
        executeCurrent(
            request = { source, credential ->
                requestFactory.album(source, credential, albumId)
            },
            parse = NavidromeJsonParser::albumSongs,
        )

    private suspend fun <T> executeCurrent(
        request: (
            com.mica.music.data.remote.RemoteSourceSnapshot,
            com.mica.music.data.remote.RemoteCredentialSnapshot,
        ) -> NavidromeRequest,
        parse: (String) -> T,
    ): T {
        val operation = sourceOwner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.NAVIDROME || !operation.source.instance.enabled) {
            throw NavidromeException(
                kind = NavidromeFailureKind.PROTOCOL,
                message = "Navidrome source is disabled or has the wrong type",
            )
        }
        val credential = credentialStore.resolve(operation.source.instance.credentialRef)
            ?: throw NavidromeException(
                kind = NavidromeFailureKind.AUTH,
                message = "Missing Navidrome credentials",
            )
        if (!sourceOwner.isCurrent(operation.token)) throw staleOperation()
        val body = executor.execute(request(operation.source, credential))
        if (!sourceOwner.isCurrent(operation.token)) throw staleOperation()
        return parse(body)
    }

    private fun staleOperation() = NavidromeException(
        kind = NavidromeFailureKind.STALE_OPERATION,
        message = "Navidrome operation became stale before publication",
    )
}
