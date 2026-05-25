package com.mica.music.data.scanner

import com.mica.music.data.LyricLine

internal object LrcParser {

    private val timedLine = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    private val tagLine = Regex("""\[[^:\]]+:[^\]]*\]""")

    fun parse(text: String): List<LyricLine> {
        val lines = text.lines()
        val timed = mutableListOf<LyricLine>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var matchedAny = false
            timedLine.findAll(trimmed).forEach { match ->
                matchedAny = true
                val min = match.groupValues[1].toIntOrNull() ?: 0
                val sec = match.groupValues[2].toIntOrNull() ?: 0
                val frac = match.groupValues[3]
                val fracMs = when (frac.length) {
                    3 -> frac.toIntOrNull() ?: 0
                    2 -> (frac.toIntOrNull() ?: 0) * 10
                    1 -> (frac.toIntOrNull() ?: 0) * 100
                    else -> 0
                }
                val body = match.groupValues[4].trim()
                if (body.isNotEmpty() && !LyricsSanitizer.isPlaceholderLyric(body) &&
                    !LyricsSanitizer.isBinaryGarbage(body)
                ) {
                    timed += LyricLine(
                        timeMs = min * 60_000 + sec * 1000 + fracMs,
                        text = MetadataTextFix.normalize(body),
                    )
                }
            }
            if (!matchedAny && !trimmed.startsWith("[") && !LyricsSanitizer.isPlaceholderLyric(trimmed) &&
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
