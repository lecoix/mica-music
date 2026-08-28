package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceSnapshot
import com.mica.music.data.remote.RemoteSourceType
import java.net.URLEncoder
import java.security.MessageDigest

internal enum class NavidromeHttpMethod {
    GET,
}

internal class NavidromeRequest(
    val method: NavidromeHttpMethod,
    val url: String,
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
) {
    override fun toString(): String =
        "NavidromeRequest(method=$method, sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, url=<redacted>)"
}

internal class NavidromeRequestFactory(
    private val saltProvider: () -> String = ::randomSalt,
    private val clientName: String = DEFAULT_CLIENT_NAME,
    private val protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
) {
    fun ping(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
    ): NavidromeRequest = request(source, credential, operation = "ping")

    fun searchAllSongsPage(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        offset: Int,
        count: Int,
    ): NavidromeRequest = request(
        source = source,
        credential = credential,
        operation = "search3",
        operationParameters = linkedMapOf(
            "query" to "",
            "artistCount" to "0",
            "albumCount" to "0",
            "songCount" to count.coerceAtLeast(1).toString(),
            "songOffset" to offset.coerceAtLeast(0).toString(),
        ),
    )

    fun albumIdsPage(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        offset: Int,
        count: Int,
    ): NavidromeRequest = request(
        source = source,
        credential = credential,
        operation = "getAlbumList2",
        operationParameters = linkedMapOf(
            "type" to "alphabeticalByName",
            "size" to count.coerceAtLeast(1).toString(),
            "offset" to offset.coerceAtLeast(0).toString(),
        ),
    )

    fun album(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        albumId: String,
    ): NavidromeRequest = request(
        source = source,
        credential = credential,
        operation = "getAlbum",
        operationParameters = mapOf("id" to albumId),
    )

    fun stream(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        trackId: String,
        maxBitRateKbps: Int? = null,
        format: String? = null,
    ): NavidromeRequest = request(
        source = source,
        credential = credential,
        operation = "stream",
        operationParameters = buildMap {
            put("id", trackId)
            maxBitRateKbps?.takeIf { it > 0 }?.let { put("maxBitRate", it.toString()) }
            format?.takeIf(String::isNotBlank)?.let { put("format", it) }
        },
    )

    fun coverArt(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        artworkId: String,
        sizePx: Int? = null,
    ): NavidromeRequest = request(
        source = source,
        credential = credential,
        operation = "getCoverArt",
        operationParameters = buildMap {
            put("id", artworkId)
            sizePx?.takeIf { it > 0 }?.let { put("size", it.toString()) }
        },
    )

    private fun request(
        source: RemoteSourceSnapshot,
        credential: RemoteCredentialSnapshot,
        operation: String,
        operationParameters: Map<String, String> = emptyMap(),
    ): NavidromeRequest {
        require(source.instance.type == RemoteSourceType.NAVIDROME) {
            "Navidrome request requires NAVIDROME source"
        }
        require(credential.credentialRef == source.instance.credentialRef) {
            "Credential snapshot does not belong to source"
        }
        val userPassword = credential.material as? RemoteCredentialMaterial.UsernamePassword
            ?: error("Navidrome password authentication requires UsernamePassword credentials")
        val salt = saltProvider().also { require(it.isNotBlank()) { "salt must not be blank" } }
        val authParameters = linkedMapOf(
            "u" to userPassword.username,
            "t" to md5Hex(userPassword.password + salt),
            "s" to salt,
            "v" to protocolVersion,
            "c" to clientName,
            "f" to "json",
        )
        val url = buildUrl(
            baseEndpoint = source.instance.endpoint,
            operation = operation,
            parameters = LinkedHashMap<String, String>().apply {
                putAll(operationParameters)
                putAll(authParameters)
            },
        )
        return NavidromeRequest(
            method = NavidromeHttpMethod.GET,
            url = url,
            sourceInstanceId = source.instance.id,
            sourceConfigRevision = source.configRevision,
            credentialRevision = credential.revision,
        )
    }

    private fun buildUrl(
        baseEndpoint: String,
        operation: String,
        parameters: Map<String, String>,
    ): String {
        val base = baseEndpoint.trim().trimEnd('/')
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return "$base/rest/$operation?$query"
    }

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_CLIENT_NAME = "Mica"
        const val DEFAULT_PROTOCOL_VERSION = "1.16.1"

        private fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun randomSalt(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
