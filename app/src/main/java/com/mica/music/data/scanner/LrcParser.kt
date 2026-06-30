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
    /** NetEase/QQ embedded word-lyric line prefix, e.g. `v1: `. */
    private val leadingVersionMarkerCue = Regex("""(?i)^v\d+:\s*$""")
    private val leadingVersionMarkerText = Regex("""(?i)^v\d+:\s*""")

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
            parseInlineBracketLine(trimmed, matches, offsetMs)?.let { parsed ->
                if (shouldKeepParsedLine(parsed)) {
                    timed += parsed
                    continue
                }
            }
            val hasLeadingTimestamps = matches.isNotEmpty() &&
                matches.first().range.first == 0 &&
                matches.zipWithNext().all { (left, right) -> right.range.first == left.range.last + 1 }
            if (hasLeadingTimestamps) {
                val body = trimmed.substring(matches.last().range.last + 1)
                matches.forEach { match ->
                    val lineTimeMs = timestampMs(match) + offsetMs
                    val parsedBody = parseEnhancedBody(body, lineTimeMs, offsetMs)
                    if (parsedBody.text.isNotEmpty() && shouldKeepParsedLine(parsedBody.toLyricLine(lineTimeMs))) {
                        timed += LyricLine(
                            timeMs = lineTimeMs.coerceAtLeast(0),
                            text = parsedBody.text,
                            cues = parsedBody.cues,
                        )
                    }
                }
                continue
            }
            if (!trimmed.startsWith("[") && !LyricsSanitizer.isPlaceholderLyric(trimmed) &&
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
        return mergeSameTimestampWordTranslationLines(timed)
    }

    private data class ParsedBody(val text: String, val cues: List<LyricCue>) {
        fun toLyricLine(lineTimeMs: Int): LyricLine = LyricLine(lineTimeMs, text, cues)
    }

    private fun mergeSameTimestampWordTranslationLines(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines.sortedBy { it.timeMs }
        val sorted = lines.sortedBy { it.timeMs }
        return buildList {
            var index = 0
            while (index < sorted.size) {
                val group = sorted.drop(index).takeWhile { it.timeMs == sorted[index].timeMs }
                if (group.size == 2) {
                    val wordLine = group.singleOrNull { it.cues.isNotEmpty() }
                    val translationLine = group.singleOrNull { it.cues.isEmpty() }
                    if (wordLine != null && translationLine != null) {
                        add(
                            wordLine.copy(
                                text = "${wordLine.text}\n${translationLine.text}",
                            ),
                        )
                        index += group.size
                        continue
                    }
                }
                addAll(group)
                index += group.size
            }
        }
    }

    private fun shouldKeepParsedLine(line: LyricLine): Boolean {
        if (LyricsSanitizer.isPlaceholderLyric(line.text)) return false
        if (line.cues.isNotEmpty()) return true
        return !LyricsSanitizer.isBinaryGarbage(line.text)
    }

    /** [00:00.000]字[00:00.022]词 — common in NetEase/QQ embedded word lyrics. */
    private fun parseInlineBracketLine(
        trimmed: String,
        matches: List<MatchResult>,
        offsetMs: Int,
    ): LyricLine? {
        if (matches.size < 2 || matches.first().range.first != 0) return null
        if (matches.zipWithNext().all { (left, right) -> right.range.first == left.range.last + 1 }) return null

        val lineTimeMs = (timestampMs(matches.first()) + offsetMs).coerceAtLeast(0)
        val textBuilder = StringBuilder()
        val cues = mutableListOf<LyricCue>()
        matches.forEachIndexed { index, match ->
            val fragmentStart = match.range.last + 1
            val fragmentEnd = matches.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val cueText = MetadataTextFix.normalizeFragment(trimmed.substring(fragmentStart, fragmentEnd))
            if (cueText.isEmpty()) return@forEachIndexed
            textBuilder.append(cueText)
            cues += LyricCue((timestampMs(match) + offsetMs).coerceAtLeast(0), cueText)
        }
        val normalizedText = MetadataTextFix.normalize(textBuilder.toString()).trim()
        if (normalizedText.isEmpty()) return null
        return finalizeTimedLine(
            lineTimeMs = lineTimeMs,
            text = normalizedText,
            cues = validateCues(cues, lineTimeMs, normalizedText),
        )
    }

    private fun finalizeTimedLine(lineTimeMs: Int, text: String, cues: List<LyricCue>): LyricLine {
        val stripped = stripLeadingVersionMarker(text, cues, lineTimeMs)
        return LyricLine(
            timeMs = lineTimeMs,
            text = stripped.text,
            cues = stripped.cues,
        )
    }

    private fun stripLeadingVersionMarker(
        text: String,
        cues: List<LyricCue>,
        lineTimeMs: Int,
    ): ParsedBody {
        var workingCues = cues.dropWhile { leadingVersionMarkerCue.matches(it.text.trim()) }.toMutableList()
        if (workingCues.isNotEmpty()) {
            val first = workingCues.first()
            val strippedFirst = leadingVersionMarkerText.replaceFirst(first.text, "")
            if (strippedFirst.isEmpty()) {
                workingCues.removeAt(0)
            } else if (strippedFirst != first.text) {
                workingCues[0] = first.copy(text = strippedFirst)
            }
        }
        val resolvedText = if (workingCues.isNotEmpty()) {
            MetadataTextFix.normalize(workingCues.joinToString(separator = "") { it.text }).trim()
        } else {
            leadingVersionMarkerText.replaceFirst(text, "").trim()
        }
        if (resolvedText.isEmpty()) return ParsedBody(text, cues)
        val resolvedCues = if (workingCues.isNotEmpty()) {
            validateCues(workingCues, lineTimeMs, resolvedText)
        } else {
            emptyList()
        }
        return ParsedBody(resolvedText, resolvedCues)
    }

    private fun parseEnhancedBody(body: String, lineTimeMs: Int, offsetMs: Int): ParsedBody {
        val matches = cueTimestamp.findAll(body).toList()
        if (matches.isEmpty()) {
            val normalized = MetadataTextFix.normalize(body).trim()
            return stripLeadingVersionMarker(normalized, emptyList(), lineTimeMs)
        }

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
        return stripLeadingVersionMarker(
            normalizedText,
            validateCues(cues, lineTimeMs, normalizedText),
            lineTimeMs,
        )
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
            finalizeTimedLine(lineStart, it, validateCues(cues, lineStart, text))
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
