package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal class NavidromeStreamRequestResolver(
    private val sourceOwnerById: (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) {
    suspend fun resolve(
        mediaId: String,
        maxBitRateKbps: Int? = null,
        format: String? = null,
    ): NavidromeRequest? {
        val trackRef = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(trackRef.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.NAVIDROME || !operation.source.instance.enabled) {
            return null
        }
        val credential = credentialStore.resolve(operation.source.instance.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        return requestFactory.stream(
            source = operation.source,
            credential = credential,
            trackId = trackRef.opaqueTrackId,
            maxBitRateKbps = maxBitRateKbps,
            format = format,
        )
    }
}
