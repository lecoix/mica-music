package com.mica.music.data.remote

import java.nio.charset.StandardCharsets
import java.util.Base64

object RemoteMediaIdCodec {
    private const val PREFIX = "mica.remote.v1."
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun isRemoteId(mediaId: String): Boolean = mediaId.startsWith(PREFIX)

    fun encode(ref: RemoteTrackRef): String = buildString {
        append(PREFIX)
        append(encodePart(ref.sourceInstanceId))
        append('.')
        append(encodePart(ref.opaqueTrackId))
    }

    fun decode(mediaId: String): RemoteTrackRef? {
        if (!isRemoteId(mediaId)) return null
        val payload = mediaId.removePrefix(PREFIX)
        val separator = payload.indexOf('.')
        if (separator <= 0 || separator == payload.lastIndex) return null
        if (payload.indexOf('.', separator + 1) >= 0) return null
        return runCatching {
            RemoteTrackRef(
                sourceInstanceId = decodePart(payload.substring(0, separator)),
                opaqueTrackId = decodePart(payload.substring(separator + 1)),
            )
        }.getOrNull()
    }

    private fun encodePart(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)
}
