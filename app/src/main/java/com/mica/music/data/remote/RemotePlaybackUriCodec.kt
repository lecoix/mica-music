package com.mica.music.data.remote

object RemotePlaybackUriCodec {
    const val SCHEME = "mica-remote"
    private const val PREFIX = "$SCHEME://track/"

    fun encode(mediaId: String): String {
        require(RemoteMediaIdCodec.isRemoteId(mediaId)) { "Remote playback URI requires a remote media id" }
        return PREFIX + mediaId
    }

    fun decode(uri: String): String? {
        if (!uri.startsWith(PREFIX)) return null
        val mediaId = uri.removePrefix(PREFIX)
        return mediaId.takeIf(RemoteMediaIdCodec::isRemoteId)
    }
}
