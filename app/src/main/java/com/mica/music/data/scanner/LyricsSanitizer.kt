package com.mica.music.data.scanner

import com.mica.music.data.LyricLine

/**
 * 歌词文本清理：严格过滤 FFmpeg 元数据噪声；仅拒绝明显二进制乱码。
 */
internal object LyricsSanitizer {

    private val timedLrc = Regex("""\[\d{1,2}:\d{2}""")

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

    fun isNoiseLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        return noiseLinePatterns.any { it.containsMatchIn(t) }
    }

    /** 仅拒绝明显二进制/乱码，避免误杀正常歌词（含标点、日文、英文）。 */
    fun isBinaryGarbage(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        if (isNoiseLine(t)) return true
        if (timedLrc.containsMatchIn(t)) return false

        var letters = 0
        var cjk = 0
        var badSymbols = 0
        var control = 0
        for (c in t) {
            when {
                c.isLetter() -> letters++
                c.code in 0x3040..0x9FFF || c.code in 0xAC00..0xD7AF -> cjk++
                c.isWhitespace() -> {}
                c.isDigit() -> letters++
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
            .filter { it.isNotEmpty() && !isNoiseLine(it) }
            .joinToString("\n")

    fun parseFiltered(raw: String): List<LyricLine> {
        val body = filterNoise(raw.trim())
        if (body.isBlank()) return emptyList()
        return finalize(LrcParser.parse(body))
    }

    fun finalize(lines: List<LyricLine>): List<LyricLine> {
        val cleaned = lines.mapNotNull { line ->
            val text = MetadataTextFix.normalize(line.text.trim())
            if (text.isEmpty() || isBinaryGarbage(text)) null
            else line.copy(text = text)
        }
        return cleaned.takeIf { it.isNotEmpty() && it.any { it.text.length >= 1 } } ?: emptyList()
    }

    fun score(lines: List<LyricLine>): Int {
        if (lines.isEmpty()) return 0
        val chars = lines.sumOf { it.text.length }
        val timed = if (lines.any { it.timeMs > 0 }) 500 else 0
        return chars + timed
    }
}
