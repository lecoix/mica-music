package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal class NavidromeStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) : RemoteHttpPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): RemoteHttpPlaybackRequest? =
        resolve(mediaId, maxBitRateKbps = null, format = null)

    suspend fun resolve(
        mediaId: String,
        maxBitRateKbps: Int? = null,
        format: String? = null,
    ): RemoteHttpPlaybackRequest? {
        val trackRef = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(trackRef.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.NAVIDROME || !operation.source.instance.enabled) {
            return null
        }
        val credential = credentialStore.resolve(operation.source.instance.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val request = requestFactory.stream(
            source = operation.source,
            credential = credential,
            trackId = trackRef.opaqueTrackId,
            maxBitRateKbps = maxBitRateKbps,
            format = format,
        )
        if (!owner.isCurrent(operation.token)) return null
        return RemoteHttpPlaybackRequest(
            url = request.url,
            sourceInstanceId = request.sourceInstanceId,
            sourceConfigRevision = request.sourceConfigRevision,
            credentialRevision = request.credentialRevision,
        )
    }
}
