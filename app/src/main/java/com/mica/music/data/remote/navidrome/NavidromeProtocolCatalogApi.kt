package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceSnapshot
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

/**
 * Creates source/credential-bound Navidrome sessions. A whole catalog enumeration should use one
 * [NavidromeCatalogSession] so every page observes the same endpoint and credential revision.
 */
internal class NavidromeProtocolCatalogApi(
    private val sourceOwner: RemoteSourceOwner,
    private val credentialStore: SecureRemoteCredentialStore,
    private val executor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) : NavidromeCatalogApi {
    suspend fun beginSession(): NavidromeCatalogSession {
        val operation = sourceOwner.beginOperationSnapshot()
        validateSource(operation.source)
        val credential = credentialStore.resolve(operation.source.instance.credentialRef)
            ?: throw NavidromeException(
                kind = NavidromeFailureKind.AUTH,
                message = "Missing Navidrome credentials",
            )
        if (!sourceOwner.isCurrent(operation.token)) throw staleOperation()
        return NavidromeCatalogSession(
            sourceOwner = sourceOwner,
            operation = operation,
            credential = credential,
            executor = executor,
            requestFactory = requestFactory,
        )
    }

    suspend fun ping() = beginSession().ping()

    override suspend fun searchAllSongsPage(offset: Int, count: Int): NavidromeSongPage =
        beginSession().searchAllSongsPage(offset, count)

    override suspend fun albumIdsPage(offset: Int, count: Int): List<String> =
        beginSession().albumIdsPage(offset, count)

    override suspend fun albumSongs(albumId: String): List<NavidromeTrack> =
        beginSession().albumSongs(albumId)

    private fun validateSource(source: RemoteSourceSnapshot) {
        if (source.instance.type != RemoteSourceType.NAVIDROME || !source.instance.enabled) {
            throw NavidromeException(
                kind = NavidromeFailureKind.PROTOCOL,
                message = "Navidrome source is disabled or has the wrong type",
            )
        }
    }
}

internal class NavidromeCatalogSession(
    private val sourceOwner: RemoteSourceOwner,
    val operation: RemoteOperationSnapshot,
    private val credential: RemoteCredentialSnapshot,
    private val executor: NavidromeHttpExecutor,
    private val requestFactory: NavidromeRequestFactory,
) : NavidromeCatalogApi {
    suspend fun ping() {
        executeBound(
            request = requestFactory::ping,
            parse = NavidromeJsonParser::validateResponse,
        )
    }

    override suspend fun searchAllSongsPage(offset: Int, count: Int): NavidromeSongPage =
        executeBound(
            request = { source, credential ->
                requestFactory.searchAllSongsPage(source, credential, offset, count)
            },
            parse = NavidromeJsonParser::searchSongsPage,
        )

    override suspend fun albumIdsPage(offset: Int, count: Int): List<String> =
        executeBound(
            request = { source, credential ->
                requestFactory.albumIdsPage(source, credential, offset, count)
            },
            parse = NavidromeJsonParser::albumIds,
        )

    override suspend fun albumSongs(albumId: String): List<NavidromeTrack> =
        executeBound(
            request = { source, credential ->
                requestFactory.album(source, credential, albumId)
            },
            parse = NavidromeJsonParser::albumSongs,
        )

    private suspend fun <T> executeBound(
        request: (RemoteSourceSnapshot, RemoteCredentialSnapshot) -> NavidromeRequest,
        parse: (String) -> T,
    ): T {
        if (!sourceOwner.isCurrent(operation.token)) throw staleOperation()
        val body = executor.execute(request(operation.source, credential))
        if (!sourceOwner.isCurrent(operation.token)) throw staleOperation()
        return parse(body)
    }
}

private fun staleOperation() = NavidromeException(
    kind = NavidromeFailureKind.STALE_OPERATION,
    message = "Navidrome operation became stale before publication",
)
