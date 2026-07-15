package com.mica.music.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.mica.music.util.DiagnosticLog

/**
 * 从文件头读取无损/特殊格式的技术参数（位深、容器修正）。
 * TagLib 负责标签；本模块负责 FLAC STREAMINFO、WAV fmt、MP4 ALAC 等。
 */
internal object AudioTechnicalProbe {

    data class Result(
        val containerName: String? = null,
        val bitsPerSample: Int? = null,
    )

    fun probe(
        context: Context,
        uri: Uri,
        detectedContainer: String,
        mimeType: String,
        displayName: String?,
    ): ProbeResult<Result> {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return runCatching {
            when {
                detectedContainer == "FLAC" || ext == "flac" -> Result(
                    containerName = "FLAC",
                    bitsPerSample = readFlacBits(context, uri),
                )

                detectedContainer == "WAV" || ext in setOf("wav", "wave") -> Result(
                    containerName = "WAV",
                    bitsPerSample = readWavBits(context, uri),
                )

                shouldProbeAlac(detectedContainer, mimeType, displayName) -> readAlacInfo(
                    context,
                    uri,
                    mimeType,
                    displayName,
                )

                else -> Result()
            }
        }.fold(
            onSuccess = { ProbeResult.Ok(it) },
            onFailure = { error ->
                DiagnosticLog.event(
                    "AudioTechnicalProbe",
                    "probe-failed detected=$detectedContainer mime=$mimeType ext=$ext name=${displayName.orEmpty().take(96)}",
                    error,
                )
                ProbeResult.Failed("technical")
            },
        )
    }

    private fun readAlacInfo(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String?,
    ): Result {
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = mimeType.ifBlank { "audio/mp4" },
            displayName = displayName ?: "a.m4a",
        )
        val isAlac = bytes?.let { containsAlacSampleEntry(it) } == true
        val bits = bytes?.let { readAlacBitDepth(it) }
            ?: readRetrieverBitDepth(context, uri)
        return Result(
            containerName = if (isAlac) "ALAC" else null,
            bitsPerSample = bits,
        )
    }

    private fun readFlacBits(context: Context, uri: Uri): Int? =
        readHeadCompat(context, uri, 64 * 1024)?.let { readFlacBitDepthFromHead(it) }

    private fun readWavBits(context: Context, uri: Uri): Int? =
        readHeadCompat(context, uri, 16 * 1024)?.let { readWavBitDepthFromHead(it) }

    internal fun readFlacBitDepthFromHead(head: ByteArray): Int? {
        val start = Id3Binary.indexOf(head, "fLaC".toByteArray(Charsets.US_ASCII), 0)
        if (start < 0) return null
        var offset = start + 4
        while (offset + 4 <= head.size) {
            val header = head[offset].toInt() and 0xFF
            val blockType = header and 0x7F
            val blockLen = Id3Binary.readUInt24(head, offset + 1)
            val body = offset + 4
            if (blockType == 0) {
                // STREAMINFO：bits-per-sample 为 5 bit，跨 body[12] 最低位与 body[13] 高 4 位
                if (body + 14 > head.size) return null
                val b12 = head[body + 12].toInt() and 0xFF
                val b13 = head[body + 13].toInt() and 0xFF
                val bits = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                return bits.takeIf { it in 4..32 }
            }
            if (header and 0x80 != 0) break
            offset = body + blockLen
        }
        return null
    }

    internal fun readWavBitDepthFromHead(head: ByteArray): Int? {
        if (head.size < 12) return null
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        while (offset + 8 <= head.size) {
            val id = String(head, offset, 4, Charsets.US_ASCII)
            val len = Id3Binary.readUInt32Le(head, offset + 4).toInt()
            if (len < 0) return null
            if (id == "fmt ") {
                if (offset + 8 + 16 > head.size) return null
                val bits = (head[offset + 22].toInt() and 0xFF) or
                    ((head[offset + 23].toInt() and 0xFF) shl 8)
                return bits.takeIf { it in 4..64 }
            }
            offset += 8 + len + (len and 1)
        }
        return null
    }

    internal fun containsAlacSampleEntry(bytes: ByteArray): Boolean {
        val needle = "alac".toByteArray(Charsets.US_ASCII)
        var from = 0
        while (true) {
            val idx = Id3Binary.indexOf(bytes, needle, from)
            if (idx < 0) return false
            if (idx >= 4) {
                val boxSize = Id3Binary.readUInt32Be(bytes, idx - 4)
                if (boxSize in 24..256 && idx + 13 < bytes.size) return true
            }
            from = idx + 4
        }
    }

    internal fun readAlacBitDepth(bytes: ByteArray): Int? {
        val needle = "alac".toByteArray(Charsets.US_ASCII)
        var from = 0
        while (true) {
            val idx = Id3Binary.indexOf(bytes, needle, from)
            if (idx < 0) return null
            if (idx >= 4) {
                val boxSize = Id3Binary.readUInt32Be(bytes, idx - 4)
                val depthIdx = idx + 13
                if (boxSize in 24..256 && depthIdx < bytes.size) {
                    val depth = bytes[depthIdx].toInt() and 0xFF
                    if (depth == 16 || depth == 20 || depth == 24 || depth == 32) return depth
                }
            }
            from = idx + 4
        }
    }

    internal fun shouldProbeAlac(
        containerName: String,
        mimeType: String,
        displayName: String?,
    ): Boolean {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.lowercase()
        return containerName.equals("ALAC", ignoreCase = true) ||
            ext in setOf("m4a", "m4b", "mp4", "alac") ||
            "alac" in mime || "mp4" in mime || "m4a" in mime
    }

    private fun readRetrieverBitDepth(context: Context, uri: Uri): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()
                ?.takeIf { it in setOf(16, 20, 24, 32) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** API 26+ 兼容读文件头；避免 [java.io.InputStream.readNBytes]（API 33+）在旧系统静默失败。 */
internal fun readHeadCompat(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readUpToCompat(maxBytes).takeIf(ByteArray::isNotEmpty)
        }
    }.getOrNull()

/** Equivalent to InputStream.readNBytes(limit), but available on every supported Android API. */
internal fun java.io.InputStream.readUpToCompat(limit: Int): ByteArray {
    require(limit >= 0) { "limit must be non-negative" }
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(buffer, offset, limit - offset)
        when {
            count > 0 -> offset += count
            count < 0 -> break
            else -> {
                val next = read()
                if (next < 0) break
                buffer[offset++] = next.toByte()
            }
        }
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
}
