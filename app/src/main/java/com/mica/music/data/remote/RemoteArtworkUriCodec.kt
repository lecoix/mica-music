package com.mica.music.data.remote

import android.net.Uri
import com.mica.music.BuildConfig

data class RemoteArtworkRef(
    val sourceInstanceId: String,
    val opaqueArtworkId: String,
) {
    init {
        require(sourceInstanceId.isNotBlank()) { "Remote artwork source id must not be blank" }
        require(opaqueArtworkId.isNotBlank()) { "Remote artwork id must not be blank" }
    }
}

/** Stable, non-authenticated content URI used by UI, MediaSession and process snapshots. */
object RemoteArtworkUriCodec {
    private const val SOURCE_SEGMENT = "source"
    private const val ART_SEGMENT = "art"

    val authority: String get() = "${BuildConfig.APPLICATION_ID}.remoteart"

    fun encode(ref: RemoteArtworkRef): String = Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath(SOURCE_SEGMENT)
        .appendPath(ref.sourceInstanceId)
        .appendPath(ART_SEGMENT)
        .appendPath(ref.opaqueArtworkId)
        .build()
        .toString()

    fun decode(value: String?): RemoteArtworkRef? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority != authority) return null
        val segments = uri.pathSegments
        if (segments.size != 4 || segments[0] != SOURCE_SEGMENT || segments[2] != ART_SEGMENT) return null
        return runCatching { RemoteArtworkRef(segments[1], segments[3]) }.getOrNull()
    }

    fun decodeForSource(value: String?, sourceInstanceId: String): RemoteArtworkRef? =
        decode(value)?.takeIf { it.sourceInstanceId == sourceInstanceId }
}
