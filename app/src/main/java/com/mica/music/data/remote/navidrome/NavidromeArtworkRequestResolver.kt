package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteHttpArtworkRequest
import com.mica.music.data.remote.RemoteHttpArtworkRequestResolver
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore

internal class NavidromeArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
    private val requestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) : RemoteHttpArtworkRequestResolver {
    override suspend fun resolve(ref: RemoteArtworkRef): RemoteHttpArtworkRequest? {
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        if (operation.source.instance.type != RemoteSourceType.NAVIDROME || !operation.source.instance.enabled) {
            return null
        }
        val credential = credentialStore.resolve(operation.source.instance.credentialRef) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val request = requestFactory.coverArt(
            source = operation.source,
            credential = credential,
            artworkId = ref.opaqueArtworkId,
        )
        if (!owner.isCurrent(operation.token)) return null
        return RemoteHttpArtworkRequest(
            url = request.url,
            sourceInstanceId = request.sourceInstanceId,
            sourceConfigRevision = request.sourceConfigRevision,
            credentialRevision = request.credentialRevision,
        )
    }
}
