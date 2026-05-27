package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.LyricLine
import com.mica.music.media.FfmpegRunner
import java.io.File
/**
 * 读取内嵌歌词：外挂 .lrc → ID3v2（USLT 等）/ FLAC Vorbis / M4A(©ly) / APE；
 * 质量不足时用 FFmpeg 兜底。不在整文件字节中裸扫 `LYRICS=`，避免音频区误匹配。
 */
internal object EmbeddedLyricsReader {

    /** 低于此分数时尝试 FFmpeg 兜底（M4A/部分 FLAC 依赖容器元数据）。 */
    private const val SKIP_FFMPEG_MIN_SCORE = 24

    /** 非同步歌词帧优先；COMM 为评论帧，不在此列表（避免把 comment 当歌词）。 */
    private val lyricFrameIds = setOf("USLT", "ULT", "SYLT", "TXXX", "LYR")

    /** 同一标签内多帧时按此顺序取歌词。 */
    private val lyricFramePriority = listOf("USLT", "ULT", "LYR", "SYLT", "TXXX")

    fun readExternalOnly(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUri: String? = null,
    ): List<LyricLine> =
        ExternalLyricsReader.read(context, uri, displayName, filePath, externalLyricsParent, externalLyricsUri)

    fun read(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUri: String? = null,
    ): List<LyricLine> {
        val bytes = readAudioBytes(context, uri)
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.orEmpty().lowercase()

        val candidates = mutableListOf<List<LyricLine>>()
        ExternalLyricsReader.read(context, uri, displayName, filePath, externalLyricsParent, externalLyricsUri)
            .takeIf { it.isNotEmpty() }
            ?.let { candidates += it }
        if (bytes != null) {
            readFromBinary(bytes, mime, ext)?.let { candidates += it }
        }
        var best = pickBestLyricsCandidate(candidates)
        val needFfmpeg = FfmpegRunner.hasEmbeddedBinary(context) &&
            (best == null || LyricsSanitizer.score(best) < SKIP_FFMPEG_MIN_SCORE)
        if (needFfmpeg) {
            readViaFfmpegMetadataBlock(context, uri, displayName)?.let { candidates += it }
            readViaFfmpegFfmetadata(context, uri, displayName)?.let { candidates += it }
            best = pickBestLyricsCandidate(candidates)
        }
        return best ?: emptyList()
    }

    fun readFastEmbeddedOnly(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
    ): List<LyricLine> {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.orEmpty().lowercase()
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = mime,
            displayName = displayName,
        ) ?: return emptyList()
        return readFromBinary(bytes, mime, ext).orEmpty()
    }

    private fun pickBestLyricsCandidate(candidates: List<List<LyricLine>>): List<LyricLine>? =
        LyricsSanitizer.pickBest(candidates)

    private fun parseLyricsText(raw: String): List<LyricLine>? {
        if (raw.isBlank()) return null
        val normalized = MetadataTextFix.normalize(raw)
        LyricsSanitizer.parseFiltered(normalized).takeIf { it.isNotEmpty() }?.let { return it }
        LyricsSanitizer.finalize(LrcParser.parse(normalized)).takeIf { it.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalizeRelaxed(normalized)
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
            val body = extractLyricsFromFfmetadata(
                LyricsEncoding.decodeBytes(metaOut.readBytes()),
            ) ?: return@withLocalFileForFfmpeg null
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
            """(?i)^\s*(lyrics|unsynced\s*lyrics?|synced\s*lyrics?)\s*:\s*(.*)$""",
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
                line.isBlank() -> Unit
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
        if (mime.contains("mp4") || mime.contains("alac") || ext in setOf("m4a", "m4b", "mp4", "aac", "alac")) {
            Mp4LyricsReader.read(bytes)?.let { parseLyricsText(it) }?.let { candidates += it }
        }
        return LyricsSanitizer.pickBest(candidates)
    }

    private fun parseId3(bytes: ByteArray): List<LyricLine>? {
        var searchFrom = 0
        var best: List<LyricLine>? = null
        var bestScore = 0
        while (searchFrom < bytes.size - 10) {
            val idx = indexOf(bytes, "ID3".toByteArray(), searchFrom)
            if (idx < 0) break
            parseId3TagAt(bytes, idx)?.let { parsed ->
                val score = LyricsSanitizer.score(parsed)
                if (score > bestScore) {
                    best = parsed
                    bestScore = score
                }
            }
            searchFrom = idx + 3
        }
        return best
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
        val tagUnsync = flags and 0x80 != 0
        val tagSize = synchsafeSize(bytes, start + 6)
        var offset = start + 10
        when {
            versionMajor == 4 && flags and 0x40 != 0 -> {
                val extSize = synchsafeSize(bytes, offset)
                offset += 4 + extSize
            }
            versionMajor == 3 && flags and 0x40 != 0 -> {
                // ID3v2.3 扩展头：4 字节大端长度（含此 4 字节）
                val extSize = readUInt32Be(bytes, offset).toInt()
                if (extSize >= 4) offset += extSize
            }
        }
        val end = (start + 10 + tagSize).coerceAtMost(bytes.size)
        val frameIdLen = if (versionMajor == 2) 3 else 4
        val byFrame = linkedMapOf<String, List<LyricLine>>()

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
                var payload = bytes.copyOfRange(frameStart, frameEnd)
                if (tagUnsync) payload = deunsynchronizeId3(payload)
                extractLyricsPayload(frameId, payload)?.let { text ->
                    parseLyricsText(text)?.takeIf { it.isNotEmpty() }?.let { lines ->
                        val prev = byFrame[frameId]
                        if (prev == null || LyricsSanitizer.score(lines) > LyricsSanitizer.score(prev)) {
                            byFrame[frameId] = lines
                        }
                    }
                }
            }
            offset = frameEnd
        }
        for (id in lyricFramePriority) {
            byFrame[id]?.let { return it }
        }
        return byFrame.values.maxByOrNull { LyricsSanitizer.score(it) }
    }

    private fun extractLyricsPayload(frameId: String, payload: ByteArray): String? = when (frameId) {
        "USLT", "ULT", "LYR" -> parseUslt(payload)
        "SYLT" -> parseSylt(payload)
        "TXXX" -> parseTxxx(payload)
        else -> null
    }

    /**
     * USLT/ULT：encoding(1) + language(3 固定 ISO-639-2，无分隔符) + 描述符(以 0/00 00 结尾) + 歌词正文。
     */
    private fun parseUslt(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 0xFF
        var i = 4
        i = skipId3TextField(payload, i, encoding)
        if (i >= payload.size) return null
        return decodeId3LyricsSlice(payload, i, encoding)
    }

    private fun parseSylt(payload: ByteArray): String? {
        if (payload.size < 10) return null
        val encoding = payload[0].toInt() and 0xFF
        var i = 6
        i = skipId3TextField(payload, i, encoding)
        val sb = StringBuilder()
        while (i + 5 < payload.size) {
            val textEnd = id3LyricsEnd(payload, i, encoding)
            val text = decodeId3LyricsSlice(payload, i, encoding)?.trim().orEmpty()
            val timeStart = if (encoding == 1 || encoding == 2) {
                (textEnd + 2).coerceAtMost(payload.size)
            } else {
                (textEnd + 1).coerceAtMost(payload.size)
            }
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
        val encoding = payload[0].toInt() and 0xFF
        val descEnd = id3LyricsEnd(payload, 1, encoding)
        if (descEnd <= 1) return null
        val desc = decodeId3LyricsSlice(payload, 1, encoding)?.uppercase().orEmpty()
        val valueStart = if (encoding == 1 || encoding == 2) descEnd + 2 else descEnd + 1
        if (!desc.contains("LYRIC") && !desc.contains("UNSYNCED")) return null
        return decodeId3LyricsSlice(payload, valueStart, encoding)
    }

    /** 跳过 ID3 文本字段（语言 / 描述）；UTF-16 以 `00 00` 结尾。 */
    private fun skipId3TextField(payload: ByteArray, start: Int, encoding: Int): Int {
        if (start >= payload.size) return start
        return when (encoding) {
            1, 2 -> {
                var i = start
                while (i + 1 < payload.size) {
                    if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte()) return i + 2
                    i++
                }
                payload.size
            }
            else -> {
                var i = start
                while (i < payload.size && payload[i] != 0.toByte()) i++
                if (i < payload.size) i + 1 else i
            }
        }
    }

    private fun id3LyricsEnd(bytes: ByteArray, offset: Int, encoding: Int): Int {
        return when (encoding) {
            1, 2 -> {
                var i = offset
                while (i + 1 < bytes.size) {
                    if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                    i++
                }
                bytes.size
            }
            else -> indexOfByte(bytes, 0.toByte(), offset).let { if (it < 0) bytes.size else it }
        }
    }

    private fun decodeId3LyricsSlice(bytes: ByteArray, offset: Int, encoding: Int): String? {
        if (offset >= bytes.size) return null
        val end = when (encoding) {
            // UTF-16 歌词字节里常见 0x00，不能按 00 00 截断，取帧内剩余全部字节
            1, 2 -> bytes.size
            else -> id3LyricsEnd(bytes, offset, encoding).let { if (it <= offset) bytes.size else it }
        }
        if (end <= offset) return null
        val slice = bytes.copyOfRange(offset, end)
        return LyricsEncoding.decodeId3Bytes(slice, encoding).takeIf { it.isNotEmpty() }
    }

    private fun parseFlac(bytes: ByteArray): List<LyricLine>? {
        val start = indexOf(bytes, "fLaC".toByteArray(), 0)
        if (start < 0) return null
        var offset = start + 4
        var best: List<LyricLine>? = null
        var bestScore = 0
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val blockLen = readUInt24(bytes, offset + 1)
            val blockStart = offset + 4
            val blockEnd = (blockStart + blockLen).coerceAtMost(bytes.size)
            if (blockType == 4 && blockEnd > blockStart) {
                parseVorbisComment(bytes, blockStart, blockEnd)?.let { parsed ->
                    val score = LyricsSanitizer.score(parsed)
                    if (score > bestScore) {
                        best = parsed
                        bestScore = score
                    }
                }
            }
            offset = blockEnd
            if (isLast) break
        }
        return best
    }

    private fun parseVorbisComment(bytes: ByteArray, start: Int, end: Int): List<LyricLine>? {
        if (start + 8 > end) return null
        val vendorLen = readUInt32Le(bytes, start).toInt()
        var pos = start + 4 + vendorLen
        if (pos + 4 > end) return null
        val count = readUInt32Le(bytes, pos).toInt()
        pos += 4
        var best: List<LyricLine>? = null
        var bestScore = 0
        for (i in 0 until count) {
            if (pos + 4 > end) return best
            val len = readUInt32Le(bytes, pos).toInt()
            pos += 4
            if (pos + len > end) return best
            val entry = LyricsEncoding.decodeBytes(bytes.copyOfRange(pos, pos + len))
            pos += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase().replace(" ", "")
            if (key.contains("LYRIC") || key.contains("UNSYNCED")) {
                val body = entry.substring(eq + 1)
                parseLyricsText(body)?.let { parsed ->
                    val score = LyricsSanitizer.score(parsed)
                    if (score > bestScore) {
                        best = parsed
                        bestScore = score
                    }
                }
            }
        }
        return best
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
                val value = LyricsEncoding.decodeBytes(bytes.copyOfRange(pos, pos + valueLen))
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
            """(?i)^(lyrics|unsynced\s*lyrics?|synced\s*lyrics?)\s*=\s*(.*)$""",
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
                if (trimmed.isEmpty()) continue
                if (trimmed.contains('=') && !Regex("""\[\d{1,2}:\d{2}""").containsMatchIn(trimmed)) {
                    inLyrics = false
                    continue
                }
                if (!LyricsSanitizer.isNoiseLine(trimmed) && !LyricsSanitizer.isPlaceholderLyric(trimmed)) {
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

    private fun readAudioBytes(context: Context, uri: Uri): ByteArray? =
        AudioProbeBytes.readForLyrics(context, uri)

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

    /** ID3 标签级 Unsynchronisation：帧内 `FF 00` 表示数据中的 `00` 字节。 */
    private fun deunsynchronizeId3(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                out.add(0.toByte())
                i += 2
            } else {
                out.add(data[i])
                i++
            }
        }
        return out.toByteArray()
    }
}
