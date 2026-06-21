package com.mica.music.data.scanner

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricCue

internal object LrcParser {

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val cueTimestamp = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""(?i)\[offset:\s*([+-]?\d+)\s*\]""")
    private val tagLine = Regex("""\[[^:\]]+:[^\]]*\]""")
    private val kugouLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val kugouCue = Regex("""<(\d+),(\d+),(\d+)>([^<]*)""")

    fun parse(text: String): List<LyricLine> {
        if (TtmlLyricsParser.looksLikeTtml(text)) return TtmlLyricsParser.parse(text)
        val lines = text.lines()
        val offsetMs = lines.firstNotNullOfOrNull { line ->
            offsetTag.find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        val timed = mutableListOf<LyricLine>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            parseKugouLine(trimmed, offsetMs)?.let {
                timed += it
                continue
            }
            val matches = timestamp.findAll(trimmed).toList()
            val hasLeadingTimestamps = matches.isNotEmpty() &&
                matches.first().range.first == 0 &&
                matches.zipWithNext().all { (left, right) -> right.range.first == left.range.last + 1 }
            if (hasLeadingTimestamps) {
                val body = trimmed.substring(matches.last().range.last + 1)
                matches.forEach { match ->
                val lineTimeMs = timestampMs(match) + offsetMs
                val parsedBody = parseEnhancedBody(body, lineTimeMs, offsetMs)
                if (parsedBody.text.isNotEmpty() && !LyricsSanitizer.isPlaceholderLyric(parsedBody.text) &&
                    !LyricsSanitizer.isBinaryGarbage(parsedBody.text)
                ) {
                    timed += LyricLine(
                        timeMs = lineTimeMs.coerceAtLeast(0),
                        text = parsedBody.text,
                        cues = parsedBody.cues,
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

    private data class ParsedBody(val text: String, val cues: List<LyricCue>)

    private fun parseEnhancedBody(body: String, lineTimeMs: Int, offsetMs: Int): ParsedBody {
        val matches = cueTimestamp.findAll(body).toList()
        if (matches.isEmpty()) return ParsedBody(MetadataTextFix.normalize(body).trim(), emptyList())

        val plain = buildString {
            var cursor = 0
            matches.forEach { match ->
                append(body.substring(cursor, match.range.first))
                cursor = match.range.last + 1
            }
            append(body.substring(cursor))
        }
        val cues = matches.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val cueText = MetadataTextFix.normalizeFragment(body.substring(start, end))
            cueText.takeIf { it.isNotEmpty() }?.let {
                LyricCue((timestampMs(match) + offsetMs).coerceAtLeast(0), it)
            }
        }
        val normalizedText = MetadataTextFix.normalize(plain).trim()
        return ParsedBody(normalizedText, validateCues(cues, lineTimeMs, normalizedText))
    }

    private fun parseKugouLine(line: String, offsetMs: Int): LyricLine? {
        val match = kugouLine.matchEntire(line) ?: return null
        val sourceLineStart = match.groupValues[1].toLongOrNull() ?: return null
        val lineStart = (sourceLineStart + offsetMs).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val body = match.groupValues[3]
        val cueMatches = kugouCue.findAll(body).toList()
        if (cueMatches.isEmpty()) return null
        val text = MetadataTextFix.normalize(
            cueMatches.joinToString(separator = "") { it.groupValues[4] },
        ).trim()
        val cues = cueMatches.mapNotNull { cue ->
            val relative = cue.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val cueTime = (sourceLineStart + relative + offsetMs)
                .coerceAtLeast(0)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val cueText = MetadataTextFix.normalizeFragment(cue.groupValues[4])
            cueText.takeIf { it.isNotEmpty() }?.let { LyricCue(cueTime, it) }
        }
        return text.takeIf { it.isNotEmpty() }?.let {
            LyricLine(lineStart, it, validateCues(cues, lineStart, text))
        }
    }

    private fun validateCues(cues: List<LyricCue>, lineTimeMs: Int, lineText: String): List<LyricCue> {
        if (cues.isEmpty() || cues.first().timeMs < lineTimeMs) return emptyList()
        if (cues.zipWithNext().any { (left, right) -> right.timeMs < left.timeMs }) return emptyList()
        val visibleCueText = cues.joinToString(separator = "") { it.text }.trim()
        return cues.takeIf { visibleCueText.isNotEmpty() && lineText.contains(visibleCueText, ignoreCase = false) }
            ?: cues.takeIf { visibleCueText == lineText }
            ?: emptyList()
    }

    private fun timestampMs(match: MatchResult): Int {
        val min = match.groupValues[1].toIntOrNull() ?: 0
        val sec = match.groupValues[2].toIntOrNull() ?: 0
        val frac = match.groupValues[3]
        val fractionMs = when (frac.length) {
            3 -> frac.toIntOrNull() ?: 0
            2 -> (frac.toIntOrNull() ?: 0) * 10
            1 -> (frac.toIntOrNull() ?: 0) * 100
            else -> 0
        }
        return min * 60_000 + sec * 1_000 + fractionMs
    }
}
