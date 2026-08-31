package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteHttpArtworkRequest
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal class NavidromeArtworkHttpStreamer(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 4,
    private val maxBytes: Long = 32L * 1024L * 1024L,
) {
    fun stream(request: RemoteHttpArtworkRequest, output: OutputStream) {
        var currentUrl = request.url
        val originalOrigin = originOf(currentUrl)
        repeat(maxRedirects + 1) { redirectIndex ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", "Mica")
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    if (redirectIndex >= maxRedirects) {
                        throw NavidromeException(
                            kind = NavidromeFailureKind.HTTP,
                            message = "Too many Navidrome artwork redirects",
                            httpStatus = status,
                        )
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw NavidromeException(
                            kind = NavidromeFailureKind.HTTP,
                            message = "Artwork redirect without Location",
                            httpStatus = status,
                        )
                    val resolved = URI(currentUrl).resolve(location)
                    if (originOf(resolved.toString()) != originalOrigin) {
                        throw NavidromeException(
                            kind = NavidromeFailureKind.REDIRECT_ORIGIN,
                            message = "Refusing cross-origin Navidrome artwork redirect",
                            httpStatus = status,
                        )
                    }
                    currentUrl = if (resolved.rawQuery.isNullOrBlank()) {
                        URI(
                            resolved.scheme,
                            resolved.rawAuthority,
                            resolved.rawPath,
                            URI(request.url).rawQuery,
                            resolved.rawFragment,
                        ).toASCIIString()
                    } else {
                        resolved.toASCIIString()
                    }
                    return@repeat
                }
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw NavidromeException(
                        kind = NavidromeFailureKind.AUTH,
                        message = "Navidrome artwork authentication failed",
                        httpStatus = status,
                    )
                }
                if (status !in 200..299) {
                    throw NavidromeException(
                        kind = NavidromeFailureKind.HTTP,
                        message = "Navidrome artwork HTTP $status",
                        httpStatus = status,
                    )
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > maxBytes) {
                    throw NavidromeException(
                        kind = NavidromeFailureKind.INVALID_RESPONSE,
                        message = "Navidrome artwork exceeds size limit",
                        httpStatus = status,
                    )
                }
                connection.inputStream.use { input -> copyBounded(input, output) }
                output.flush()
                return
            } finally {
                connection.disconnect()
            }
        }
        throw NavidromeException(
            kind = NavidromeFailureKind.HTTP,
            message = "Navidrome artwork redirect resolution failed",
        )
    }

    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > maxBytes) {
                throw NavidromeException(
                    kind = NavidromeFailureKind.INVALID_RESPONSE,
                    message = "Navidrome artwork exceeds size limit",
                )
            }
            output.write(buffer, 0, read)
        }
    }

    private fun originOf(url: String): Origin {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = if (uri.port >= 0) uri.port else when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        return Origin(scheme, host, port)
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
