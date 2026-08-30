package com.mica.music.data.remote.webdav

import com.mica.music.data.remote.SeekableByteSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Finite-range WebDAV random reader used only behind the shared metadata read-ahead layer. */
internal class WebDavSeekableByteSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    override val sizeBytes: Long,
) : SeekableByteSource {
    private val closed = AtomicBoolean(false)

    init {
        require(sizeBytes >= 0L) { "WebDAV source size must not be negative" }
    }

    override fun readAt(
        fileOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        check(!closed.get()) { "WebDAV byte source is closed" }
        require(fileOffset >= 0L) { "fileOffset must not be negative" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset + length <= buffer.size) {
            "Invalid destination range"
        }
        if (length == 0) return 0
        if (fileOffset >= sizeBytes) return -1

        val requested = minOf(length.toLong(), sizeBytes - fileOffset).toInt()
        val endInclusive = fileOffset + requested - 1L
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$fileOffset-$endInclusive")
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw WebDavException(
                        WebDavFailureKind.AUTH,
                        "WebDAV metadata range request was not authorized",
                    )
                    206 -> Unit
                    else -> throw WebDavException(
                        WebDavFailureKind.PROTOCOL,
                        "WebDAV metadata range request requires HTTP 206, got ${response.code}",
                    )
                }
                val contentRange = response.header("Content-Range")
                    ?: throw WebDavException(
                        WebDavFailureKind.PROTOCOL,
                        "WebDAV metadata range response omitted Content-Range",
                    )
                val actual = parseContentRange(contentRange)
                    ?: throw WebDavException(
                        WebDavFailureKind.PROTOCOL,
                        "WebDAV metadata range response had invalid Content-Range",
                    )
                if (actual.first != fileOffset || actual.last > endInclusive) {
                    throw WebDavException(
                        WebDavFailureKind.PROTOCOL,
                        "WebDAV metadata range response did not match requested window",
                    )
                }
                val body = response.body
                    ?: throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV metadata response had no body")
                val expected = (actual.last - actual.first + 1L).toInt()
                if (expected <= 0 || expected > requested) {
                    throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV metadata response length was invalid")
                }
                val input = body.byteStream()
                var total = 0
                while (total < expected) {
                    val count = input.read(buffer, bufferOffset + total, expected - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total != expected) {
                    throw WebDavException(
                        WebDavFailureKind.HTTP,
                        "WebDAV metadata range response ended before the declared range",
                    )
                }
                return total
            }
        } catch (failure: WebDavException) {
            throw failure
        } catch (failure: WebDavRangeException) {
            throw WebDavException(WebDavFailureKind.PROTOCOL, "WebDAV metadata range contract failed", failure)
        } catch (failure: IOException) {
            throw WebDavException(WebDavFailureKind.HTTP, "WebDAV metadata range request failed", failure)
        }
    }

    override fun close() {
        closed.set(true)
    }

    private fun parseContentRange(value: String): LongRange? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("bytes ", ignoreCase = true)) return null
        val range = trimmed.substringAfter(' ').substringBefore('/').trim()
        val start = range.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = range.substringAfter('-', "").trim().toLongOrNull() ?: return null
        if (start < 0L || end < start) return null
        return start..end
    }
}
