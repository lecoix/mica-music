package com.mica.music.data.remote.emby

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceSnapshot
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Only stable Emby item identifiers are persisted. API keys stay in JIT request headers. */
internal object EmbyProtocol {
    const val PAGE_SIZE = 500
    const val MAX_TRACKS = 10_000
    const val TOKEN_HEADER = "X-Emby-Token"

    fun apiKey(snapshot: RemoteCredentialSnapshot): String {
        val token = (snapshot.material as? RemoteCredentialMaterial.BearerToken)?.token
        require(!token.isNullOrBlank()) { "Emby API Key is unavailable" }
        return token
    }

    fun url(source: RemoteSourceSnapshot, route: String, parameters: Map<String, String> = emptyMap()): String {
        require(source.instance.type == com.mica.music.data.remote.RemoteSourceType.EMBY)
        val base = URI(source.instance.endpoint)
        require(base.scheme in listOf("https", "http") && base.host != null && base.userInfo == null)
        val prefix = base.toASCIIString().trimEnd('/')
        val path = if (base.path.trimEnd('/').endsWith("/emby", ignoreCase = true)) {
            route.removePrefix("/emby")
        } else route
        val query = parameters.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return prefix + path + if (query.isEmpty()) "" else "?$query"
    }

    fun stream(source: RemoteSourceSnapshot, itemId: String): String =
        url(source, "/emby/Audio/${encodePath(itemId)}/stream", mapOf("static" to "true"))

    fun artwork(source: RemoteSourceSnapshot, itemId: String): String =
        url(source, "/emby/Items/${encodePath(itemId)}/Images/Primary", mapOf("maxWidth" to "800"))

    /** Emby exports music lyrics through its subtitle endpoint, including sidecar LRC. */
    fun lyricsSubtitle(source: RemoteSourceSnapshot, itemId: String, mediaSourceId: String, index: Int): String {
        require(index in 0..255) { "Invalid Emby lyrics stream index" }
        return url(
            source,
            "/emby/Videos/" + encodePath(itemId) + "/" + encodePath(mediaSourceId) +
                "/Subtitles/" + index + "/Stream.lrc",
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun encodePath(value: String): String {
        require(value.isNotBlank() && value.length <= 256 && value.none { it == '/' || it == '\\' || it == '?' || it == '#' })
        return encode(value)
    }
}
