package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.data.toLyricsDocumentCompat
/**
 * 读取内嵌歌词：外挂 .lrc → ID3v2（USLT 等）/ FLAC Vorbis / M4A(©ly) / APE。
 * 主扫描路径由 TagLib 提供候选；此处做二进制帧解析，不再使用 FFmpeg 兜底。
 */
internal object EmbeddedLyricsReader {

    /** 非同步歌词帧优先；COMM 为评论帧，不在此列表（避免把 comment 当歌词）。 */
    private val lyricFrameIds = setOf("USLT", "ULT", "SYLT", "TXXX", "LYR")
    private const val MAX_SYLT_ENTRIES = 50_000

    /** 同一标签内多帧时按此顺序取歌词。 */
    fun readExternalOnly(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUris: List<String> = emptyList(),
    ): List<LyricLine> =
        ExternalLyricsReader.read(context, uri, displayName, filePath, externalLyricsParent, externalLyricsUris)

    fun readExternalDocumentOnly(
        context: Context,
        uri: Uri,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUris: List<String> = emptyList(),
    ): LyricsDocument = ExternalLyricsReader.readDocument(
        context, uri, displayName, filePath, externalLyricsParent, externalLyricsUris,
    )

    fun read(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUris: List<String> = emptyList(),
    ): List<LyricLine> = readDocument(
        context, uri, mimeType, displayName, filePath, externalLyricsParent, externalLyricsUris,
    ).toLegacyLyricLines()

    fun readDocument(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        filePath: String = "",
        externalLyricsParent: DocumentFile? = null,
        externalLyricsUris: List<String> = emptyList(),
    ): LyricsDocument {
        val candidates = mutableListOf<LyricsDocument>()
        ExternalLyricsReader.readDocument(
            context, uri, displayName, filePath, externalLyricsParent, externalLyricsUris,
        )
            .takeIf { it.lines.isNotEmpty() }
            ?.let { candidates += it }
        readFastEmbeddedDocument(context, uri, mimeType, displayName)
            .takeIf { it.lines.isNotEmpty() }
            ?.let { candidates += it }
        return LyricsSanitizer.pickBestDocument(candidates) ?: LyricsDocument()
    }

    fun readFastEmbeddedOnly(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
    ): List<LyricLine> = readFastEmbeddedDocument(context, uri, mimeType, displayName).toLegacyLyricLines()

    fun readFastEmbeddedDocument(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
    ): LyricsDocument {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.orEmpty().lowercase()
        val bytes = AudioProbeBytes.readFastForLyrics(
            context = context,
            uri = uri,
            mimeType = mime,
            displayName = displayName,
        ) ?: return LyricsDocument(origin = LyricsOrigin.EMBEDDED)
        return readFromBinary(bytes, mime, ext) ?: LyricsDocument(origin = LyricsOrigin.EMBEDDED)
    }

    fun probeFastEmbeddedDocument(
        context: Context,
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        profiler: ScanProfiler? = null,
    ): ProbeResult<LyricsDocument?> {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType.orEmpty().lowercase()
        val format = embeddedFormatKey(ext, mime)
        if (format == "other") return ProbeResult.Ok(null)
        return try {
            val bytes = profiler.measureOptional("lyrics.embedded.read.$format") {
                AudioProbeBytes.readFastForLyricsOrThrow(context, uri, mime, displayName)
            }
            profiler?.recordBytes("lyrics.embedded.bytes.$format", bytes?.size?.toLong() ?: 0L)
            ProbeResult.Ok(bytes?.let {
                profiler.measureOptional("lyrics.embedded.parse.$format") {
                    readFromBinary(it, mime, ext, profiler)
                }
            })
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            ProbeResult.Failed("embeddedLyricsRead")
        }
    }

    private fun parseLyricsText(raw: String): LyricsDocument? {
        if (raw.isBlank()) return null
        val normalized = MetadataTextFix.normalize(raw)
        LyricsSanitizer.parseFilteredDocument(normalized, LyricsOrigin.EMBEDDED)
            .takeIf { it.lines.isNotEmpty() }
            ?.let { return it }
        LyricsSanitizer.finalizeDocument(
            LrcParser.parseDocument(normalized).copy(origin = LyricsOrigin.EMBEDDED),
        ).takeIf { it.lines.isNotEmpty() }?.let { return it }
        return LyricsSanitizer.finalizeRelaxedDocument(normalized, LyricsOrigin.EMBEDDED)
    }

    /** Parses one trusted TagLib text property using the same embedded-lyrics rules as local scan. */
    internal fun parseTagLibTextDocument(raw: String): LyricsDocument? = parseLyricsText(raw)

    private fun readFromBinary(
        bytes: ByteArray,
        mime: String,
        ext: String,
        profiler: ScanProfiler? = null,
    ): LyricsDocument? {
        val candidates = mutableListOf<LyricsDocument>()
        for (parser in binaryParsersFor(ext, mime)) {
            when (parser) {
                BinaryLyricsParser.ID3 -> profiler.measureOptional("lyrics.embedded.parser.id3") {
                    readId3Document(bytes)
                }
                BinaryLyricsParser.FLAC -> profiler.measureOptional("lyrics.embedded.parser.flac") {
                    parseFlac(bytes)
                }
                BinaryLyricsParser.APE -> profiler.measureOptional("lyrics.embedded.parser.ape") {
                    parseApe(bytes)
                }
                BinaryLyricsParser.MP4 -> profiler.measureOptional("lyrics.embedded.parser.mp4") {
                    val raw = Mp4LyricsReader.read(bytes, profiler)
                    profiler.measureOptional("lyrics.embedded.mp4.parseText") {
                        raw?.let { parseLyricsText(it) }
                    }
                }
            }?.let { candidates += it }
        }
        return LyricsSanitizer.pickBestDocument(candidates)
    }

    internal enum class BinaryLyricsParser { ID3, FLAC, APE, MP4 }

    internal fun binaryParsersFor(ext: String, mime: String): Set<BinaryLyricsParser> {
        val normalizedExt = ext.lowercase()
        val normalizedMime = mime.lowercase()
        return when {
            normalizedExt in setOf("m4a", "m4b", "m4p", "mp4", "alac") ||
                normalizedMime.contains("mp4") || normalizedMime.contains("alac") -> setOf(BinaryLyricsParser.MP4)
            normalizedExt == "flac" || normalizedMime.contains("flac") ->
                setOf(BinaryLyricsParser.ID3, BinaryLyricsParser.FLAC)
            normalizedExt == "ape" || normalizedMime.contains("ape") ->
                setOf(BinaryLyricsParser.ID3, BinaryLyricsParser.APE)
            normalizedExt in setOf("mp3", "aac", "wav", "wave", "aiff", "aif") ||
                normalizedMime.contains("mpeg") || normalizedMime.contains("aac") ||
                normalizedMime.contains("wav") || normalizedMime.contains("aiff") -> setOf(BinaryLyricsParser.ID3)
            else -> emptySet()
        }
    }

    private fun embeddedFormatKey(ext: String, mime: String): String = when {
        ext in setOf("m4a", "m4b", "m4p", "mp4", "alac") ||
            mime.contains("mp4") || mime.contains("alac") -> "mp4"
        ext == "flac" || mime.contains("flac") -> "flac"
        ext == "mp3" || mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        ext == "aac" || mime.contains("aac") -> "aac"
        ext in setOf("wav", "wave") || mime.contains("wav") -> "wav"
        ext in setOf("aiff", "aif") || mime.contains("aiff") -> "aiff"
        ext == "ape" || mime.contains("ape") -> "ape"
        else -> "other"
    }

    private fun <T> ScanProfiler?.measureOptional(stage: String, block: () -> T): T =
        this?.measure(stage, block) ?: block()

    internal fun readFromBinaryForTest(bytes: ByteArray, mime: String = "audio/wav", ext: String = "wav"): List<LyricLine>? =
        readFromBinary(bytes, mime, ext)?.toLegacyLyricLines()

    internal fun readDocumentFromBinaryForTest(
        bytes: ByteArray,
        mime: String = "audio/wav",
        ext: String = "wav",
    ): LyricsDocument? = readFromBinary(bytes, mime, ext)

    internal fun readId3Document(bytes: ByteArray): LyricsDocument? {
        var searchFrom = 0
        var best: LyricsDocument? = null
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

    private fun parseId3TagAt(bytes: ByteArray, start: Int): LyricsDocument? {
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
        val byFrame = linkedMapOf<String, LyricsDocument>()

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
                val parsed = when (frameId) {
                    "SYLT" -> parseSylt(payload)
                    else -> extractLyricsTextPayload(frameId, payload)?.let(::parseLyricsText)
                }
                parsed?.takeIf { it.lines.isNotEmpty() }?.let { document ->
                    val prev = byFrame[frameId]
                    if (prev == null || LyricsSanitizer.score(document) > LyricsSanitizer.score(prev)) {
                        byFrame[frameId] = document
                    }
                }
            }
            offset = frameEnd
        }
        return LyricsSanitizer.pickBestDocument(byFrame.values.toList())
    }

    private fun extractLyricsTextPayload(frameId: String, payload: ByteArray): String? = when (frameId) {
        "USLT", "ULT", "LYR" -> parseUslt(payload)
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

    private data class SyltEntry(val timeMs: Int, val text: String)

    private fun parseSylt(payload: ByteArray): LyricsDocument? {
        if (payload.size < 10) return null
        val encoding = payload[0].toInt() and 0xFF
        if (encoding !in 0..3) return null
        val timestampFormat = payload[4].toInt() and 0xFF
        if (timestampFormat != 2) return null

        var offset = skipId3TextField(payload, 6, encoding)
        if (offset >= payload.size) return null
        val entries = mutableListOf<SyltEntry>()
        var previousTimeMs = -1
        while (offset + 4 <= payload.size && entries.size < MAX_SYLT_ENTRIES) {
            val field = readTerminatedId3Text(payload, offset, encoding) ?: return null
            offset = field.nextOffset
            if (offset + 4 > payload.size) return null
            val timestamp = readUInt32Be(payload, offset)
            offset += 4
            if (timestamp > Int.MAX_VALUE.toLong()) return null
            val timeMs = timestamp.toInt()
            if (timeMs < previousTimeMs) return null
            previousTimeMs = timeMs
            if (field.text.isNotEmpty()) entries += SyltEntry(timeMs, field.text)
        }
        if (entries.isEmpty() || entries.size >= MAX_SYLT_ENTRIES) return null
        return groupSyltEntries(entries)?.toLyricsDocumentCompat(
            format = LyricsFormat.SYLT,
            origin = LyricsOrigin.EMBEDDED,
        )
    }

    private data class DecodedId3Field(val text: String, val nextOffset: Int)

    private fun readTerminatedId3Text(bytes: ByteArray, offset: Int, encoding: Int): DecodedId3Field? {
        if (offset >= bytes.size) return null
        val end = id3LyricsEnd(bytes, offset, encoding)
        if (end < offset || end >= bytes.size) return null
        val terminatorSize = if (encoding == 1 || encoding == 2) 2 else 1
        val text = if (end == offset) {
            ""
        } else {
            decodeSyltText(bytes.copyOfRange(offset, end), encoding) ?: return null
        }
        return DecodedId3Field(text, (end + terminatorSize).coerceAtMost(bytes.size))
    }

    private fun decodeSyltText(bytes: ByteArray, encoding: Int): String? = runCatching {
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        String(bytes, charset)
            .trim('\u0000')
            .trimStart('\uFEFF', '\uFFFE')
    }.getOrNull()

    private fun groupSyltEntries(entries: List<SyltEntry>): List<LyricLine>? {
        val lines = mutableListOf<LyricLine>()
        var lineStartMs = -1
        val lineText = StringBuilder()
        val lineCues = mutableListOf<com.mica.music.data.LyricCue>()

        fun flushLine() {
            val normalized = MetadataTextFix.normalize(lineText.toString()).trim()
            if (lineStartMs >= 0 && normalized.isNotEmpty()) {
                lines += LyricLine(lineStartMs, normalized, lineCues.toList())
            }
            lineStartMs = -1
            lineText.clear()
            lineCues.clear()
        }

        fun appendFragment(timeMs: Int, raw: String) {
            val fragment = MetadataTextFix.normalizeFragment(raw)
            if (fragment.isEmpty()) return
            if (fragment.isBlank()) {
                if (lineText.isNotEmpty()) {
                    lineText.append(fragment)
                    if (lineCues.isNotEmpty()) {
                        lineCues[lineCues.lastIndex] = lineCues.last().copy(
                            text = lineCues.last().text + fragment,
                        )
                    }
                }
                return
            }
            if (lineStartMs < 0) lineStartMs = timeMs
            lineText.append(fragment)
            lineCues += com.mica.music.data.LyricCue(timeMs, fragment)
        }

        entries.forEach { entry ->
            val normalized = entry.text.replace("\r\n", "\n").replace('\r', '\n')
            var segmentStart = 0
            normalized.forEachIndexed { index, char ->
                if (char == '\n') {
                    appendFragment(entry.timeMs, normalized.substring(segmentStart, index))
                    flushLine()
                    segmentStart = index + 1
                }
            }
            appendFragment(entry.timeMs, normalized.substring(segmentStart))
        }
        flushLine()
        return LyricsSanitizer.finalize(lines).takeIf { it.isNotEmpty() }
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
        return readTerminatedId3Text(payload, start, encoding)?.nextOffset ?: payload.size
    }

    private fun id3LyricsEnd(bytes: ByteArray, offset: Int, encoding: Int): Int {
        return when (encoding) {
            1, 2 -> {
                var i = offset
                while (i + 1 < bytes.size) {
                    if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i
                    i += 2
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

    private fun parseFlac(bytes: ByteArray): LyricsDocument? {
        val start = indexOf(bytes, "fLaC".toByteArray(), 0)
        if (start < 0) return null
        var offset = start + 4
        var best: LyricsDocument? = null
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

    private fun parseVorbisComment(bytes: ByteArray, start: Int, end: Int): LyricsDocument? {
        if (start + 8 > end) return null
        val vendorLen = readUInt32Le(bytes, start).toInt()
        var pos = start + 4 + vendorLen
        if (pos + 4 > end) return null
        val count = readUInt32Le(bytes, pos).toInt()
        pos += 4
        var best: LyricsDocument? = null
        var bestScore = 0
        for (i in 0 until count) {
            if (pos + 4 > end) return best
            val len = readUInt32Le(bytes, pos).toInt()
            pos += 4
            if (pos + len > end) return best
            val entry = LyricsEncoding.decodeUtf8Bytes(bytes.copyOfRange(pos, pos + len))
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

    private fun parseApe(bytes: ByteArray): LyricsDocument? {
        val marker = "APETAGEX".toByteArray()
        var search = 0
        while (search <= bytes.size - marker.size) {
            val idx = indexOf(bytes, marker, search)
            if (idx < 0) return null
            parseApeFooterAt(bytes, idx)?.let { return it }
            parseApeItems(bytes, idx + 32, bytes.size, readUInt32Le(bytes, idx + 16))?.let { return it }
            search = idx + marker.size
        }
        return null
    }

    private fun parseApeFooterAt(bytes: ByteArray, footerStart: Int): LyricsDocument? {
        if (footerStart + 32 > bytes.size) return null
        val tagSize = readUInt32Le(bytes, footerStart + 12)
        if (tagSize < 32L || tagSize > footerStart.toLong() + 32L) return null
        val itemsStart = footerStart - (tagSize - 32L).toInt()
        return parseApeItems(bytes, itemsStart, footerStart, readUInt32Le(bytes, footerStart + 16))
    }

    private fun parseApeItems(bytes: ByteArray, start: Int, end: Int, itemCountLong: Long): LyricsDocument? {
        if (start < 0 || start > end || end > bytes.size || itemCountLong > 100_000L) return null
        val itemCount = itemCountLong.toInt()
        var pos = start
        repeat(itemCount) {
            if (pos + 8 > end) return null
            val valueLenLong = readUInt32Le(bytes, pos)
            if (valueLenLong > Int.MAX_VALUE) return null
            val valueLen = valueLenLong.toInt()
            val flags = readUInt32Le(bytes, pos + 4).toInt()
            pos += 8
            val keyEnd = indexOfByte(bytes, 0.toByte(), pos)
            if (keyEnd < 0 || keyEnd >= end) return null
            val key = String(bytes, pos, keyEnd - pos, Charsets.UTF_8).uppercase()
            pos = keyEnd + 1
            if (valueLen > end - pos) return null
            val isTextItem = ((flags ushr 1) and 0x3) == 0
            if (isTextItem && key.contains("LYRIC")) {
                val value = LyricsEncoding.decodeUtf8Bytes(bytes.copyOfRange(pos, pos + valueLen))
                parseLyricsText(value)?.let { return it }
            }
            pos += valueLen
        }
        return null
    }

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
