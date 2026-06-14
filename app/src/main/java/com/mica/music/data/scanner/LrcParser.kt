package com.mica.music.data.scanner

import com.mica.music.data.LyricLine

internal object LrcParser {

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val offsetTag = Regex("""(?i)\[offset:\s*([+-]?\d+)\s*\]""")
    private val tagLine = Regex("""\[[^:\]]+:[^\]]*\]""")

    fun parse(text: String): List<LyricLine> {
        val lines = text.lines()
        val offsetMs = lines.firstNotNullOfOrNull { line ->
            offsetTag.find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        val timed = mutableListOf<LyricLine>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val matches = timestamp.findAll(trimmed).toList()
            val hasLeadingTimestamps = matches.isNotEmpty() &&
                matches.first().range.first == 0 &&
                matches.zipWithNext().all { (left, right) -> right.range.first == left.range.last + 1 }
            if (hasLeadingTimestamps) {
                val body = trimmed.substring(matches.last().range.last + 1).trim()
                matches.forEach { match ->
                val min = match.groupValues[1].toIntOrNull() ?: 0
                val sec = match.groupValues[2].toIntOrNull() ?: 0
                val frac = match.groupValues[3]
                val fracMs = when (frac.length) {
                    3 -> frac.toIntOrNull() ?: 0
                    2 -> (frac.toIntOrNull() ?: 0) * 10
                    1 -> (frac.toIntOrNull() ?: 0) * 100
                    else -> 0
                }
                if (body.isNotEmpty() && !LyricsSanitizer.isPlaceholderLyric(body) &&
                    !LyricsSanitizer.isBinaryGarbage(body)
                ) {
                    timed += LyricLine(
                        timeMs = (min * 60_000 + sec * 1000 + fracMs + offsetMs).coerceAtLeast(0),
                        text = MetadataTextFix.normalize(body),
                    )
                }
                }
            }
            if (!hasLeadingTimestamps && !trimmed.startsWith("[") && !LyricsSanitizer.isPlaceholderLyric(trimmed) &&
                !LyricsSanitizer.isBinaryGarbage(trimmed)
            ) {
                timed += LyricLine(timeMs = 0, text = MetadataTextFix.normalize(trimmed))
            }
        }
        if (timed.isEmpty()) {
            val plain = text.lines()
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() && !tagLine.matches(it) && !LyricsSanitizer.isPlaceholderLyric(it) &&
                        !LyricsSanitizer.isBinaryGarbage(it)
                }
                .map { MetadataTextFix.normalize(it) }
            if (plain.isNotEmpty()) {
                return plain.map { LyricLine(timeMs = 0, it) }
            }
        }
        return timed.sortedBy { it.timeMs }
    }
}
