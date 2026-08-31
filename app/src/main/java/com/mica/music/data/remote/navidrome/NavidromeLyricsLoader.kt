package com.mica.music.data.remote.navidrome

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceSnapshot
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import com.mica.music.data.scanner.LyricsSanitizer

internal class NavidromeLyricsLoader(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: SecureRemoteCredentialStore,
    private val executor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) {
    suspend fun load(song: Song): LyricsDocument {
        val ref = RemoteMediaIdCodec.decode(song.id) ?: return LyricsDocument()
        val owner = catalogRepository.sourceOwner(ref.sourceInstanceId) ?: return LyricsDocument()
        val operation = owner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.NAVIDROME || !operation.source.instance.enabled) {
            return LyricsDocument()
        }
        val credential = credentialStore.resolve(operation.source.instance.credentialRef)
            ?: throw NavidromeException(
                kind = NavidromeFailureKind.AUTH,
                message = "Missing Navidrome credentials",
            )
        ensureCurrent(owner, operation)

        val structured = try {
            executeBound(owner, operation, credential) { source, currentCredential ->
                requestFactory.lyricsBySongId(source, currentCredential, ref.opaqueTrackId)
            }?.let(NavidromeLyricsParser::structuredLyrics)
        } catch (failure: NavidromeException) {
            if (!failure.allowsLegacyLyricsFallback()) throw failure
            null
        }
        if (structured != null && structured.lines.isNotEmpty()) return structured

        val summary = catalogRepository.find(listOf(ref))[ref]
        val artist = summary?.artist?.ifBlank { song.artist } ?: song.artist
        val title = summary?.title?.ifBlank { song.title } ?: song.title
        if (artist.isBlank() && title.isBlank()) return LyricsDocument()

        val legacyBody = executeBound(owner, operation, credential) { source, currentCredential ->
            requestFactory.legacyLyrics(source, currentCredential, artist, title)
        } ?: return LyricsDocument()
        val raw = NavidromeLyricsParser.legacyLyricsValue(legacyBody) ?: return LyricsDocument()
        return LyricsSanitizer.parseFilteredDocument(raw, origin = LyricsOrigin.EXTERNAL)
    }

    private suspend fun executeBound(
        owner: RemoteSourceOwner,
        operation: RemoteOperationSnapshot,
        credential: RemoteCredentialSnapshot,
        request: (RemoteSourceSnapshot, RemoteCredentialSnapshot) -> NavidromeRequest,
    ): String? {
        ensureCurrent(owner, operation)
        val body = executor.execute(request(operation.source, credential))
        ensureCurrent(owner, operation)
        return body
    }

    private fun ensureCurrent(owner: RemoteSourceOwner, operation: RemoteOperationSnapshot) {
        if (!owner.isCurrent(operation.token)) {
            throw NavidromeException(
                kind = NavidromeFailureKind.STALE_OPERATION,
                message = "Navidrome lyrics operation became stale",
            )
        }
    }
}

private fun NavidromeException.allowsLegacyLyricsFallback(): Boolean = when (kind) {
    NavidromeFailureKind.AUTH,
    NavidromeFailureKind.REDIRECT_ORIGIN,
    NavidromeFailureKind.STALE_OPERATION,
    -> false

    NavidromeFailureKind.HTTP,
    NavidromeFailureKind.PROTOCOL,
    NavidromeFailureKind.INVALID_RESPONSE,
    -> true
}
