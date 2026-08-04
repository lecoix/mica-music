package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.data.toLyricsDocumentCompat

/**
 * 歌词文本清理。
 *
 * - LRC/SPL 逐行：只丢空、`//`、纯音符、元数据/容器噪声（对齐 Icey/Halcyon）。
 * - 内嵌字节解码的乱码/可渲染性门槛仍在 [LyricsEncoding]（如 ID3 encoding=0、MP4 候选）。
 */
internal object LyricsSanitizer {

    private val timedLrc = Regex("""\[\d{1,2}:\d{2}""")
    private val lrcTimestampPrefix = Regex("""^(\[[^\]]+\]\s*)+""")
    /** `[ti:…]` / `[offset:…]` 等非时间轴标签整行。 */
    private val lrcMetadataTagLine = Regex("""(?i)^\[[a-z][^:\]]*:[^\]]*]\s*$""")
    private val slashOnlyPlaceholder = Regex("""^[/／\\]+$""")
    private val musicSymbolOnly = Regex(
        """^[\s♪♫♬♩♭♯♮🎵🎶🎼]+$""",
    )

    internal val timedLrcHint: Regex get() = timedLrc

    private val singleCharPlaceholders = setOf('y', 'Y', 'n', 'N')

    /** 去掉 LRC 时间轴后的正文，用于识别 `[00:00.00]y` 等占位行。 */
    fun lyricTextWithoutTimestamps(line: String): String =
        lrcTimestampPrefix.replace(line.trim(), "").trim()

    /**
     * FFmpeg/容器常见的单字符占位，以及 `//` 类占位（含带时间轴的 LRC 行）。
     * 保留给旧调用方；新逻辑请优先用 [isIgnorableLyricText]。
     */
    fun isPlaceholderLyric(text: String): Boolean {
        val core = lyricTextWithoutTimestamps(text)
        if (core.isEmpty()) return true
        if (slashOnlyPlaceholder.matches(core)) return true
        return core.length == 1 && core[0] in singleCharPlaceholders
    }

    fun isMusicSymbolOnly(text: String): Boolean =
        musicSymbolOnly.matches(lyricTextWithoutTimestamps(text))

    fun isLrcMetadataTagLine(line: String): Boolean =
        lrcMetadataTagLine.matches(line.trim())

    /**
     * LRC/SPL 逐行丢弃：空、`//`、纯音符、元数据标签、FFmpeg/容器噪声行。
     * 不做 [LyricsEncoding.isRenderable] / binary 打分。
     */
    fun isIgnorableLyricText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (isLrcMetadataTagLine(t)) return true
        if (noiseLinePatterns.any { it.containsMatchIn(t) }) return true
        val core = lyricTextWithoutTimestamps(t)
        if (core.isEmpty()) return true
        if (slashOnlyPlaceholder.matches(core)) return true
        if (musicSymbolOnly.matches(core)) return true
        if (core.length == 1 && core[0] in singleCharPlaceholders) return true
        return false
    }

    fun pickBest(candidates: List<List<LyricLine>>): List<LyricLine>? =
        candidates.maxWithOrNull(
            compareBy<List<LyricLine>> { score(it) }
                .thenBy { lines -> lines.count { it.timeMs > 0 } }
                .thenBy { it.size },
        )

    fun pickBestDocument(candidates: List<LyricsDocument>): LyricsDocument? =
        candidates.maxWithOrNull(
            compareBy<LyricsDocument> { score(it.toLegacyLyricLines()) }
                .thenBy { document -> document.lines.count { it.startMs > 0 } }
                .thenBy { it.lines.size },
        )

    /** FFmpeg / 容器元数据行（绝不是歌词） */
    private val noiseLinePatterns = listOf(
        Regex("""(?i)^duration\s*[:=]"""),
        Regex("""(?i)^bitrate\s*[:=]"""),
        Regex("""(?i)^start\s*[:=]"""),
        Regex("""(?i)^encoder\s*[:=]"""),
        Regex("""(?i)^major_brand\s*[:=]"""),
        Regex("""(?i)^minor_version\s*[:=]"""),
        Regex("""(?i)^compatible_brands\s*[:=]"""),
        Regex("""(?i)^creation_time\s*[:=]"""),
        Regex("""(?i)^metadata\s*[:=]?\s*$"""),
        Regex("""(?i)^Stream\s+#"""),
        Regex("""(?i)^Input\s+#"""),
        Regex("""(?i)^Output\s+#"""),
        Regex("""(?i)^chapter\s*[:=]"""),
        Regex("""^\d+(\.\d+)?\s*(kb/s|kbps|kHz|Hz)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\.\d+\s*$"""),
    )

    /** @deprecated 语义等同 [isIgnorableLyricText]；保留旧名避免扩散改动。 */
    fun isNoiseLine(line: String): Boolean = isIgnorableLyricText(line)

    /**
     * 内嵌解码用：拒绝明显二进制/控制符垃圾。
     * LRC 逐行保活不要走这里；见 [isIgnorableLyricText]。
     */
    fun isBinaryGarbage(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        if (!LyricsEncoding.isRenderable(t)) return true
        if (isIgnorableLyricText(t)) return true
        if (timedLrc.containsMatchIn(t)) {
            if (isPlaceholderLyric(t)) return true
            return false
        }

        var letters = 0
        var cjk = 0
        var badSymbols = 0
        var control = 0
        for (c in t) {
            when {
                c.isLetter() -> letters++
                c.code in 0x3040..0x9FFF || c.code in 0xAC00..0xD7AF -> cjk++
                c.isWhitespace() || c.code in 0x2000..0x200B -> {}
                c.isDigit() -> letters++
                c in "?!，。、；：（）()[]" -> letters++
                c.code < 0x20 -> control++
                c in "@\$^`|~\\{}" -> badSymbols++
                c == '?' && letters + cjk == 0 -> badSymbols++
                c == '%' && letters + cjk == 0 -> badSymbols++
                else -> letters++ // 普通标点等视为正常字符
            }
        }
        val len = t.length
        if (control > 0) return true
        if (len >= 8 && badSymbols * 3 >= len && letters + cjk < 4) return true
        if (len >= 12 && badSymbols >= 6 && letters + cjk < len / 4) return true
        return false
    }

    fun filterNoise(text: String): String =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isIgnorableLyricText(it) }
            .joinToString("\n")

    fun parseFiltered(raw: String): List<LyricLine> =
        parseFilteredDocument(raw).toLegacyLyricLines()

    fun parseFilteredDocument(
        raw: String,
        origin: LyricsOrigin = LyricsOrigin.UNKNOWN,
    ): LyricsDocument {
        if (TtmlLyricsParser.looksLikeTtml(raw)) {
            return finalizeDocument(TtmlLyricsParser.parseDocument(raw).copy(origin = origin))
        }
        val body = filterNoise(raw.trim())
        if (body.isBlank()) return LyricsDocument(origin = origin)
        return finalizeDocument(LrcParser.parseDocument(body).copy(origin = origin))
    }

    fun finalize(lines: List<LyricLine>): List<LyricLine> {
        val cleaned = lines.mapNotNull { line ->
            val text = MetadataTextFix.normalize(line.text.trim())
            if (text.isEmpty() || isIgnorableLyricText(text)) null
            else line.copy(text = text)
        }
        return cleaned.takeIf { it.isNotEmpty() && it.any { it.text.length >= 1 } } ?: emptyList()
    }

    fun finalizeDocument(document: LyricsDocument): LyricsDocument {
        val cleaned = document.lines.mapNotNull { line ->
            val parts = line.parts.mapNotNull { part ->
                MetadataTextFix.normalize(part.text.trim())
                    .takeIf { it.isNotEmpty() && !isIgnorableLyricText(it) }
                    ?.let { part.copy(text = it) }
            }
            if (parts.isEmpty()) return@mapNotNull null
            line.copy(parts = parts)
        }
        return document.copy(lines = cleaned)
    }

    fun finalizeRelaxed(text: String): List<LyricLine>? {
        val lines = text.lines()
            .map { MetadataTextFix.normalize(it.trim()) }
            .filter {
                it.isNotEmpty() && !it.contains('\uFFFD') && !isIgnorableLyricText(it)
            }
            .filter { !LyricsEncoding.looksLikeMojibake(it) }
        return lines.map { LyricLine(timeMs = 0, it) }.takeIf { it.isNotEmpty() }
    }

    fun finalizeRelaxedDocument(
        text: String,
        origin: LyricsOrigin = LyricsOrigin.UNKNOWN,
    ): LyricsDocument? = finalizeRelaxed(text)
        ?.toLyricsDocumentCompat(format = LyricsFormat.PLAIN, origin = origin)

    fun score(lines: List<LyricLine>): Int {
        if (lines.isEmpty()) return 0
        val valid = lines.filter { line ->
            val t = line.text.trim()
            t.isNotEmpty() && !t.contains('\uFFFD') && !isIgnorableLyricText(t) &&
                !LyricsEncoding.looksLikeMojibake(t)
        }
        if (valid.isEmpty()) return 0
        val chars = valid.sumOf { it.text.length }
        val timed = if (valid.any { it.timeMs > 0 }) 500 else 0
        val cueBonus = valid.sumOf { it.cues.size } * 80
        val lineBonus = valid.size * 30
        return chars + timed + cueBonus + lineBonus
    }

    fun score(document: LyricsDocument): Int = score(document.toLegacyLyricLines())
}
