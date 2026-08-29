package com.mica.music.data.remote.smb

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class SmbEndpoint(
    val host: String,
    val port: Int,
    val share: String,
    val rootPath: String,
) {
    fun serverPath(relativePath: String = ""): String = buildList {
        if (rootPath.isNotEmpty()) add(rootPath)
        val relative = SmbPathCodec.normalizeRelativePath(relativePath)
        if (relative.isNotEmpty()) add(relative.replace('/', '\\'))
    }.joinToString("\\")
}

internal object SmbPathCodec {
    const val DEFAULT_PORT: Int = 445

    fun normalizeSourceEndpoint(raw: String): String {
        val endpoint = parse(raw)
        val host = if (endpoint.host.contains(':')) "[${endpoint.host}]" else endpoint.host.lowercase(Locale.US)
        val port = if (endpoint.port == DEFAULT_PORT) "" else ":${endpoint.port}"
        val segments = buildList {
            add(endpoint.share)
            if (endpoint.rootPath.isNotEmpty()) addAll(endpoint.rootPath.split('\\'))
        }
        return "smb://$host$port/${segments.joinToString("/") { encodeSegment(it) }}"
    }

    fun parse(raw: String): SmbEndpoint {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "SMB address must not be blank" }
        val uri = runCatching { URI(trimmed.replace(" ", "%20")) }
            .getOrElse { throw IllegalArgumentException("Invalid SMB address", it) }
        require(uri.scheme.equals("smb", ignoreCase = true)) {
            "SMB address must start with smb://"
        }
        require(uri.userInfo == null && !uri.rawAuthority.orEmpty().contains('@')) {
            "SMB address must not contain username/password"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "SMB address must not contain query or fragment"
        }
        val host = uri.host?.trim().orEmpty()
        require(host.isNotBlank()) { "SMB address must include a host" }
        val port = uri.port.takeIf { it >= 0 } ?: DEFAULT_PORT
        require(port in 1..65535) { "SMB port must be between 1 and 65535" }

        val segments = uri.path.orEmpty()
            .split('/')
            .filter(String::isNotEmpty)
            .map(::validateSegment)
        require(segments.isNotEmpty()) { "SMB address must include a share name" }

        return SmbEndpoint(
            host = host,
            port = port,
            share = segments.first(),
            rootPath = segments.drop(1).joinToString("\\"),
        )
    }

    fun normalizeRelativePath(raw: String): String {
        if (raw.isBlank()) return ""
        require(!raw.startsWith('/') && !raw.startsWith('\\')) {
            "SMB track path must be relative to the configured root"
        }
        return raw.replace('\\', '/')
            .split('/')
            .filter(String::isNotEmpty)
            .map(::validateSegment)
            .joinToString("/")
    }

    fun appendChild(parent: String, childName: String): String {
        val child = validateSegment(childName)
        val normalizedParent = normalizeRelativePath(parent)
        return if (normalizedParent.isEmpty()) child else "$normalizedParent/$child"
    }

    private fun validateSegment(value: String): String {
        val segment = value.trimEnd('\u0000')
        require(segment.isNotEmpty()) { "SMB path segment must not be empty" }
        require(segment != "." && segment != "..") { "SMB path traversal is not allowed" }
        require('/' !in segment && '\\' !in segment && '\u0000' !in segment) {
            "SMB path segment contains a separator or NUL"
        }
        return segment
    }

    private fun encodeSegment(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val c = unsigned.toChar()
            if (
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~'
            ) {
                append(c)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}