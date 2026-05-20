package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.mica.music.data.LyricLine
import com.mica.music.media.FfmpegRunner
import java.io.File
import java.nio.charset.Charset

/**
 * 读取内嵌歌词：优先直接扫描 LYRICS= / UNSYNCEDLYRICS= 等元数据字段，再解析 ID3/FLAC/APE/FFmpeg。
 */
internal object EmbeddedLyricsReader {

    /** 二进制路径已得到足够歌词时，不再复制整轨给 FFmpeg。 */
    private const val SKIP_FFMPEG_MIN_SCORE = 80

    private val latin1 = Charset.forName("ISO-8859-1")
    private val lyricFrameIds = setOf("USLT", "SYLT", "TXXX", "COMM", "LYR")

    private val rawLyricsTagPrefixes = listOf(
        "LYRICS=",
        "UNSYNCEDLYRICS=",
        "UNSYNCEDLYRIC=",
        "SYNCEDLYRICS=",
    )

    fun read(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
    ): List<LyricLine> {
        val bytes = readAudioBytes(context, uri)
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.orEmpty().lowercase()

        val candidates = mutableListOf<List<LyricLine>>()
        if (bytes != null) {
            scanRawLyricsTags(bytes)?.let { candidates += it }
            readFromBinary(bytes, mime, ext)?.let { candidates += it }
        }
        val bestBeforeFfmpeg = candidates.maxByOrNull { LyricsSanitizer.score(it) }
        val needFfmpeg = FfmpegRunner.hasEmbeddedBinary(context) &&
            (bestBeforeFfmpeg == null || LyricsSanitizer.score(bestBeforeFfmpeg) < SKIP_FFMPEG_MIN_SCORE)
        if (needFfmpeg) {
            readViaFfmpegMetadataBlock(context, uri, displayName)?.let { candidates += it }
            readViaFfmpegFfmetadata(context, uri, displayName)?.let { candidates += it }
        }
        return candidates.maxByOrNull { LyricsSanitizer.score(it) } ?: emptyList()
    }

    /**
     * 在文件字节中直接查找 `LYRICS=`、`UNSYNCEDLYRICS=` 等（FLAC Vorbis / 部分 M4A 最常见）。
     */
    private fun scanRawLyricsTags(bytes: ByteArray): List<LyricLine>? {
        var best: List<LyricLine>? = null
        var bestScore = 0
        for (prefix in rawLyricsTagPrefixes) {
            val patterns = listOf(
                prefix.toByteArray(Charsets.UTF_8),
                prefix.lowercase().toByteArray(Charsets.UTF_8),
            )
            for (pattern in patterns) {
                var from = 0
                while (from < bytes.size) {
                    val idx = indexOf(bytes, pattern, from)
                    if (idx < 0) break
                    val value = extractTextAfterMarker(bytes, idx + pattern.size)
                    parseLyricsText(value)?.let { parsed ->
                        val score = LyricsSanitizer.score(parsed)
                        if (score > bestScore) {
                            best = parsed
                            bestScore = score
                        }
                    }
                    from = idx + 1
                }
            }
        }
        return best
    }

    private fun extractTextAfterMarker(bytes: ByteArray, start: Int): String {
        val maxEnd = (start + 800 * 1024).coerceAtMost(bytes.size)
        var i = start
        while (i < maxEnd && bytes[i] == 0.toByte()) i++
        var end = i
        while (end < maxEnd) {
            if (bytes[end] == 0.toByte()) break
            end++
        }
        if (end <= i) return ""
        return String(bytes, i, end - i, Charsets.UTF_8).trim('\u0000')
    }

    private fun parseLyricsText(raw: String): List<LyricLine>? {
        if (raw.isBlank()) return null
        val normalized = MetadataTextFix.normalize(raw)
        LyricsSanitizer.parseFiltered(normalized).takeIf { it.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalize(LrcParser.parse(normalized)).takeIf { it.isNotEmpty() }
    }

    private fun readViaFfmpegFfmetadata(
        context: Context,
        uri: Uri,
        displayName: String?,
    ): List<LyricLine>? = withLocalFileForFfmpeg(context, uri, displayName) { local ->
        val metaOut = File.createTempFile("meta_", ".txt", ScanCacheManager.metaTempDir(context))
        try {
            val session = FfmpegRunner.executeWithArguments(
                context,
                arrayOf(
                    "-hide_banner", "-loglevel", "error",
                    "-i", local.absolutePath,
                    "-map_metadata", "0",
                    "-f", "ffmetadata",
                    "-y", metaOut.absolutePath,
                ),
            )
            if (!session.success || !metaOut.exists()) return@withLocalFileForFfmpeg null
            val body = extractLyricsFromFfmetadata(metaOut.readText()) ?: return@withLocalFileForFfmpeg null
            parseLyricsText(body)
        } finally {
            metaOut.delete()
        }
    }

    /**
     * 解析 FFmpeg 输出的 Metadata 块（支持多行歌词缩进续行）。
     *
     *     lyrics              : [00:00.00]第一行
     *                         : [00:01.00]第二行
     */
    private fun readViaFfmpegMetadataBlock(
        context: Context,
        uri: Uri,
        displayName: String?,
    ): List<LyricLine>? = withLocalFileForFfmpeg(context, uri, displayName) { local ->
        val log = FfmpegRunner.executeWithArguments(
            context,
            arrayOf(
                "-hide_banner", "-loglevel", "info",
                "-i", local.absolutePath,
                "-f", "null", "-",
            ),
        ).logs
        val sb = StringBuilder()
        var inLyrics = false
        val startRx = Regex(
            """(?i)^\s*(lyrics|unsyncedlyrics|unsyncedlyric|syncedlyrics)\s*:\s*(.*)$""",
        )
        val continueRx = Regex("""^\s+:\s*(.+)$""")
        val otherKeyRx = Regex("""^\s+[A-Za-z][\w./-]+\s+:\s*.+""")

        for (line in log.lines()) {
            val startMatch = startRx.find(line)
            if (startMatch != null) {
                inLyrics = true
                val value = startMatch.groupValues[2].trim()
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(value)
                }
                continue
            }
            if (!inLyrics) continue
            when {
                line.isBlank() -> inLyrics = false
                continueRx.matches(line) -> {
                    val v = continueRx.find(line)!!.groupValues[1].trim()
                    if (v.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(v)
                    }
                }
                otherKeyRx.matches(line) && !continueRx.matches(line) -> inLyrics = false
            }
        }
        parseLyricsText(sb.toString())
    }

    private fun readFromBinary(bytes: ByteArray, mime: String, ext: String): List<LyricLine>? {
        val candidates = mutableListOf<List<LyricLine>>()
        parseId3(bytes)?.let { candidates += it }
        parseFlac(bytes)?.let { candidates += it }
        parseApe(bytes)?.let { candidates += it }
        if (mime.contains("mpeg") || ext == "mp3") parseId3(bytes)?.let { candidates += it }
        if (mime.contains("flac") || ext == "flac") parseFlac(bytes)?.let { candidates += it }
        return candidates.maxByOrNull { LyricsSanitizer.score(it) }
    }

    private fun parseId3(bytes: ByteArray): List<LyricLine>? {
        var searchFrom = 0
        while (searchFrom < bytes.size - 10) {
            val idx = indexOf(bytes, "ID3".toByteArray(), searchFrom)
            if (idx < 0) break
            parseId3TagAt(bytes, idx)?.let { return it }
            searchFrom = idx + 3
        }
        return null
    }

    private fun parseId3TagAt(bytes: ByteArray, start: Int): List<LyricLine>? {
        if (start + 10 > bytes.size) return null
        if (bytes[start] != 'I'.code.toByte() || bytes[start + 1] != 'D'.code.toByte() ||
            bytes[start + 2] != '3'.code.toByte()
        ) {
            return null
        }
        val versionMajor = bytes[start + 3].toInt()
        val flags = bytes[start + 5].toInt()
        val tagSize = synchsafeSize(bytes, start + 6)
        var offset = start + 10
        if (versionMajor == 4 && flags and 0x40 != 0) {
            val extSize = synchsafeSize(bytes, offset)
            offset += 4 + extSize
        }
        val end = (start + 10 + tagSize).coerceAtMost(bytes.size)
        val frameIdLen = if (versionMajor == 2) 3 else 4

        while (offset + frameIdLen + 6 <= end) {
            val frameId = String(bytes, offset, frameIdLen, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            val sizeOffset = offset + frameIdLen
            val frameSize = if (versionMajor == 4) {
                synchsafeSize(bytes, sizeOffset)
            } else {
                readUInt32Be(bytes, sizeOffset).toInt()
            }
            val frameStart = sizeOffset + 4 + 2
            val frameEnd = (frameStart + frameSize).coerceAtMost(end)
            if (frameEnd <= frameStart) break
            if (frameId in lyricFrameIds) {
                val payload = bytes.copyOfRange(frameStart, frameEnd)
                extractLyricsPayload(frameId, payload)?.let { text ->
                    parseLyricsText(text)?.let { return it }
                }
            }
            offset = frameEnd
        }
        return null
    }

    private fun extractLyricsPayload(frameId: String, payload: ByteArray): String? = when (frameId) {
        "USLT", "LYR" -> parseUslt(payload)
        "SYLT" -> parseSylt(payload)
        "TXXX" -> parseTxxx(payload)
        "COMM" -> parseComm(payload)
        else -> null
    }

    private fun parseUslt(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt()
        var i = 1
        while (i < payload.size && payload[i] != 0.toByte()) i++
        if (i < payload.size) i++
        while (i < payload.size && payload[i] != 0.toByte()) i++
        if (i < payload.size) i++
        return decodeLyricsBytes(payload, i, encoding)
    }

    private fun parseComm(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt()
        var i = 1
        while (i < payload.size && payload[i] != 0.toByte()) i++
        if (i < payload.size) i++
        while (i < payload.size && payload[i] != 0.toByte()) i++
        if (i < payload.size) i++
        return decodeLyricsBytes(payload, i, encoding)
    }

    private fun parseSylt(payload: ByteArray): String? {
        if (payload.size < 10) return null
        val encoding = payload[0].toInt()
        var i = 5
        while (i < payload.size && payload[i] != 0.toByte()) i++
        if (i < payload.size) i++
        val sb = StringBuilder()
        while (i + 5 < payload.size) {
            val textEnd = indexOfByte(payload, 0.toByte(), i)
            if (textEnd < 0) break
            val text = decodeLyricsBytes(payload, i, encoding)?.trim().orEmpty()
            val timeStart = textEnd + 1
            if (timeStart + 4 > payload.size) break
            i = timeStart + 4
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun parseTxxx(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt()
        var i = 1
        val descEnd = indexOfByte(payload, 0.toByte(), i)
        if (descEnd < 0) return null
        val desc = decodeLyricsBytes(payload, i, encoding)?.uppercase().orEmpty()
        i = descEnd + 1
        if (!desc.contains("LYRIC") && !desc.contains("UNSYNCED")) return null
        return decodeLyricsBytes(payload, i, encoding)
    }

    private fun decodeLyricsBytes(bytes: ByteArray, offset: Int, encoding: Int): String? {
        if (offset >= bytes.size) return null
        val end = indexOfByte(bytes, 0.toByte(), offset).let { if (it < 0) bytes.size else it }
        if (end <= offset) return null
        val slice = bytes.copyOfRange(offset, end)
        val encodings = listOf(encoding, 3, 1, 0)
        for (enc in encodings.distinct()) {
            decodeWithEncoding(slice, enc)?.let { if (it.isNotBlank()) return it }
        }
        return String(slice, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeWithEncoding(slice: ByteArray, encoding: Int): String? =
        when (encoding) {
            1 -> String(slice, Charsets.UTF_16LE)
            2 -> String(slice, Charset.forName("UTF-16BE"))
            3 -> String(slice, Charsets.UTF_8)
            else -> String(slice, latin1)
        }.trim().takeIf { it.isNotEmpty() }

    private fun parseFlac(bytes: ByteArray): List<LyricLine>? {
        val start = indexOf(bytes, "fLaC".toByteArray(), 0)
        if (start < 0) return null
        var offset = start + 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val blockLen = readUInt24(bytes, offset + 1)
            val blockStart = offset + 4
            val blockEnd = (blockStart + blockLen).coerceAtMost(bytes.size)
            if (blockType == 4 && blockEnd > blockStart) {
                parseVorbisComment(bytes, blockStart, blockEnd)?.let { return it }
            }
            offset = blockEnd
            if (isLast) break
        }
        return null
    }

    private fun parseVorbisComment(bytes: ByteArray, start: Int, end: Int): List<LyricLine>? {
        if (start + 8 > end) return null
        val vendorLen = readUInt32Le(bytes, start).toInt()
        var pos = start + 4 + vendorLen
        if (pos + 4 > end) return null
        val count = readUInt32Le(bytes, pos).toInt()
        pos += 4
        for (i in 0 until count) {
            if (pos + 4 > end) return null
            val len = readUInt32Le(bytes, pos).toInt()
            pos += 4
            if (pos + len > end) return null
            val entry = decodeUtf8(bytes, pos, len)
            pos += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase()
            if (key.contains("LYRIC")) {
                val body = entry.substring(eq + 1)
                parseLyricsText(body)?.let { return it }
            }
        }
        return null
    }

    private fun parseApe(bytes: ByteArray): List<LyricLine>? {
        val marker = "APETAGEX".toByteArray()
        var search = bytes.size - marker.size
        while (search >= 0) {
            val idx = indexOf(bytes, marker, search.coerceAtLeast(0))
            if (idx < 0) break
            parseApeAt(bytes, idx)?.let { return it }
            search = idx - 1
        }
        return null
    }

    private fun parseApeAt(bytes: ByteArray, start: Int): List<LyricLine>? {
        if (start + 32 > bytes.size) return null
        val tagSize = readUInt32Le(bytes, start + 12).toInt()
        val itemCount = readUInt32Le(bytes, start + 16).toInt()
        var pos = start + 32
        val end = (start + tagSize).coerceAtMost(bytes.size)
        repeat(itemCount) {
            if (pos + 8 > end) return null
            val valueLen = readUInt32Le(bytes, pos + 4).toInt()
            pos += 8
            val keyEnd = indexOfByte(bytes, 0.toByte(), pos)
            if (keyEnd < 0 || keyEnd >= end) return null
            val key = String(bytes, pos, keyEnd - pos, Charsets.UTF_8).uppercase()
            pos = keyEnd + 1
            if (pos + valueLen > end) return null
            if (key.contains("LYRIC")) {
                val value = decodeUtf8(bytes, pos, valueLen)
                parseLyricsText(value)?.let { return it }
            }
            pos += valueLen
        }
        return null
    }

    private fun extractLyricsFromFfmetadata(text: String): String? {
        val sb = StringBuilder()
        var inLyrics = false
        val keyRegex = Regex(
            """^(lyrics|unsyncedlyrics|syncedlyrics)\s*=\s*(.*)$""",
            RegexOption.IGNORE_CASE,
        )
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(";")) continue
            val keyMatch = keyRegex.find(trimmed)
            if (keyMatch != null) {
                inLyrics = true
                val value = keyMatch.groupValues[2].trim()
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(value)
                }
                continue
            }
            if (inLyrics) {
                if (trimmed.isEmpty()) {
                    inLyrics = false
                    continue
                }
                if (trimmed.contains('=') && !Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(trimmed)) {
                    inLyrics = false
                    continue
                }
                if (!LyricsSanitizer.isNoiseLine(trimmed)) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(trimmed)
                }
            }
        }
        return sb.toString().trim().takeIf { it.length >= 2 }
    }

    private fun <T> withLocalFileForFfmpeg(
        context: Context,
        uri: Uri,
        displayName: String?,
        block: (File) -> T,
    ): T? {
        val (local, ephemeral) = resolveLocalFile(context, uri, displayName) ?: return null
        return try {
            block(local)
        } finally {
            if (ephemeral) local.delete()
        }
    }

    /** @return Pair(本地文件, 是否为本轮探测创建的临时副本，需在 finally 中删除) */
    private fun resolveLocalFile(
        context: Context,
        uri: Uri,
        displayName: String?,
    ): Pair<File, Boolean>? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            return if (file.exists()) file to false else null
        }
        val ext = displayName?.substringAfterLast('.', "audio")?.take(12) ?: "audio"
        val dest = runCatching {
            File.createTempFile("probe_", ".$ext", ScanCacheManager.probeTempDir(context))
        }.getOrNull() ?: return null
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0L
        }.getOrDefault(false)
        return if (ok) dest to true else {
            dest.delete()
            null
        }
    }

    private fun readAudioBytes(context: Context, uri: Uri): ByteArray? {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        return when {
            size in 0..25_000_000 -> readAllBytes(context, uri)
            else -> readHeadAndTail(context, uri, headBytes = 2 * 1024 * 1024, tailBytes = 8 * 1024 * 1024)
        }
    }

    private fun readAllBytes(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

    private fun readHeadAndTail(
        context: Context,
        uri: Uri,
        headBytes: Int,
        tailBytes: Int,
    ): ByteArray? = runCatching {
        val head = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readNBytes(headBytes)
        } ?: return@runCatching null
        val tail = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val tailLen = tailBytes.toLong().coerceAtMost(size).toInt()
            java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                if (size > tailLen) fis.skip(size - tailLen)
                fis.readNBytes(tailLen)
            }
        } ?: return@runCatching null
        head + tail
    }.getOrNull()

    private fun readUInt32Be(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun synchsafeSize(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len.coerceAtMost(bytes.size - offset), Charsets.UTF_8)

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from >= bytes.size) return -1
        outer@ for (i in from..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOfByte(bytes: ByteArray, byte: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == byte) return i
        return -1
    }
}
