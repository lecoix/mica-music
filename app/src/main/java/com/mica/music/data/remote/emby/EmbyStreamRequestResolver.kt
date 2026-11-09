package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

/** The queue's mediaId never carries an Emby token or stream URL. */
internal class EmbyStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentials: SecureRemoteCredentialStore,
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? {
        val track = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(track.sourceInstanceId) ?: return null
        val snapshot = owner.beginOperationSnapshot()
        if (snapshot.source.instance.type != RemoteSourceType.EMBY || !snapshot.source.instance.enabled) return null
        val key = credentials.resolve(snapshot.source.instance.credentialRef) ?: return null
        val token = EmbyProtocol.apiKey(key)
        if (!owner.isCurrent(snapshot.token)) return null
        val request = RemoteHttpPlaybackRequest(
            url = EmbyProtocol.stream(snapshot.source, track.opaqueTrackId),
            sourceInstanceId = track.sourceInstanceId,
            sourceConfigRevision = snapshot.source.configRevision,
            credentialRevision = key.revision,
            requestHeaders = mapOf(EmbyProtocol.TOKEN_HEADER to token),
        )
        return request.takeIf { owner.isCurrent(snapshot.token) }
    }
}
