package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib

/**
 * 基于 TagLib（io.github.kyant0:taglib）的标签/封面/歌词/音频属性读取。
 * 任何失败（无法打开、native 异常、属性无效）返回 null，由调用方回退 MediaMetadataRetriever。
 */
internal object TagLibReader {

    class Result(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val copyright: String,
        val year: Int,
        val durationSec: Int,
        val sampleRateHz: Int,
        val bitrateKbps: Int,
        val channelCount: Int,
        val lyricsCandidates: List<String>,
        val frontCoverBytes: ByteArray?,
    )

    fun read(context: Context, uri: Uri): Result? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val metadata = TagLib.getMetadata(pfd.dup().detachFd(), readPictures = true)
                ?: return@use null
            val props = TagLib.getAudioProperties(pfd.dup().detachFd(), AudioPropertiesReadStyle.Average)
            if (props == null || props.sampleRate <= 0) return@use null
            val tags = metadata.propertyMap
            val frontCover = metadata.pictures.firstOrNull { it.pictureType == "Front Cover" }
                ?: metadata.pictures.firstOrNull()
            Result(
                title = tags.firstValue("TITLE"),
                artist = tags.firstValue("ARTIST", "ARTISTS", "PERFORMER"),
                album = tags.firstValue("ALBUM"),
                albumArtist = tags.firstValue("ALBUMARTIST", "ALBUM ARTIST"),
                copyright = tags.firstValue("COPYRIGHT"),
                year = parseYear(tags.firstValue("DATE", "YEAR", "ORIGINALDATE")),
                durationSec = props.length / 1000,
                sampleRateHz = props.sampleRate,
                bitrateKbps = props.bitrate,
                channelCount = props.channels,
                lyricsCandidates = lyricsCandidates(tags),
                frontCoverBytes = frontCover?.data?.takeIf { it.isNotEmpty() },
            )
        }
    }.getOrNull()

    /**
     * Kyant taglib 绑定未暴露位深；无损格式从文件头自行解析（FLAC STREAMINFO /
     * WAV fmt chunk / MP4 ALAC magic cookie），其余格式返回 null。
     */
    fun readBitsPerSample(
        context: Context,
        uri: Uri,
        containerName: String,
        mimeType: String,
        displayName: String?,
    ): Int? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return runCatching {
            when {
                containerName == "FLAC" || ext == "flac" -> readFlacBits(context, uri)
                containerName == "WAV" || ext == "wav" -> readWavBits(context, uri)
                containerName == "ALAC" -> readAlacBits(context, uri, mimeType, displayName)
                else -> null
            }
        }.getOrNull()
    }

    private fun Map<String, Array<String>>.firstValue(vararg keys: String): String {
        for (key in keys) {
            this[key]?.firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        }
        return ""
    }

    private fun lyricsCandidates(tags: Map<String, Array<String>>): List<String> =
        tags.entries
            .filter { (key, _) ->
                key == "LYRICS" || key.startsWith("LYRICS:") ||
                    key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS"
            }
            .flatMap { it.value.asList() }
            .filter { it.isNotBlank() }

    private val yearRegex = Regex("""\d{4}""")

    private fun parseYear(raw: String): Int =
        yearRegex.find(raw)?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun readHead(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(maxBytes) }
        }.getOrNull()

    private fun readFlacBits(context: Context, uri: Uri): Int? {
        val head = readHead(context, uri, 64 * 1024) ?: return null
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

    private fun readWavBits(context: Context, uri: Uri): Int? {
        val head = readHead(context, uri, 16 * 1024) ?: return null
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

    private fun readAlacBits(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String?,
    ): Int? {
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = mimeType.ifBlank { "audio/mp4" },
            displayName = displayName ?: "a.m4a",
        ) ?: return null
        val needle = "alac".toByteArray(Charsets.US_ASCII)
        var from = 0
        while (true) {
            val idx = Id3Binary.indexOf(bytes, needle, from)
            if (idx < 0) return null
            // ALACSpecificConfig 盒：size(4) type"alac"(4) verFlags(4) frameLength(4)
            // compatibleVersion(1) bitDepth(1)；盒大小通常为 36，借此排除 stsd 采样条目
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
}
