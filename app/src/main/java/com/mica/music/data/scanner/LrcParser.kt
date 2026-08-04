package com.mica.music.data.scanner

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.toLegacyLyricLines

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
        return parseDocument(text).toLegacyLyricLines()
    }

    fun parseDocument(text: String): LyricsDocument {
        if (TtmlLyricsParser.looksLikeTtml(text)) return TtmlLyricsParser.parseDocument(text)
        val format = if (timestamp.containsMatchIn(text) || kugouLine.containsMatchIn(text)) {
            LyricsFormat.LRC
        } else {
            LyricsFormat.PLAIN
        }
        val entries = parseEntries(text)
        if (entries.isEmpty()) {
            val plain = text.lines()
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() && !tagLine.matches(it) && !LyricsSanitizer.isIgnorableLyricText(it)
                }
                .map { MetadataTextFix.normalize(it) }
            if (plain.isEmpty()) return LyricsDocument(format = format)
            return LyricsDocument(
                format = format,
                lines = plain.mapIndexed { index, lineText ->
                    LyricLineNode(
                        id = "$index-0",
                        startMs = 0,
                        parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, lineText)),
                    )
                },
            )
        }
        return LyricsDocument(
            format = format,
            lines = groupEntries(entries).mapIndexed { index, group -> group.toNode(index) },
        )
    }

    private data class ParsedBody(val text: String, val cues: List<LyricCue>) {
        fun toLyricLine(lineTimeMs: Int): LyricLine = LyricLine(lineTimeMs, text, cues)
    }

    /**
     * One timed (or orphan untimed) lyric row as encountered in file order.
     * [trailingTexts] holds SPL untimestamped continuation lines attached while adjacent.
     */
    private data class RawEntry(
        val timeMs: Int,
        val text: String,
        val cues: List<LyricCue>,
        val explicitTime: Boolean,
        val trailingTexts: MutableList<String> = mutableListOf(),
    )

    private data class GroupedLine(
        val timeMs: Int,
        val text: String,
        val cues: List<LyricCue>,
        val secondaries: List<String>,
    ) {
        fun toNode(index: Int): LyricLineNode {
            val reading: String?
            val translation: String?
            when (secondaries.size) {
                0 -> {
                    reading = null
                    translation = null
                }
                1 -> {
                    // NetEase dual-track / SPL bilingual: second line is translation.
                    reading = null
                    translation = secondaries[0]
                }
                else -> {
                    // NetEase triple-track merge: reading then translation(+extra langs).
                    reading = secondaries[0]
                    translation = secondaries.drop(1).joinToString("\n")
                }
            }
            val parts = buildList {
                reading?.takeIf { it.isNotEmpty() }?.let {
                    add(LyricTextPart(LyricTextRole.READING, it))
                }
                add(LyricTextPart(LyricTextRole.ORIGINAL, text))
                translation?.takeIf { it.isNotEmpty() }?.let {
                    add(LyricTextPart(LyricTextRole.TRANSLATION, it))
                }
            }
            return LyricLineNode(
                id = "$index-$timeMs",
                startMs = timeMs,
                parts = parts,
                tokens = cues.mapIndexed { cueIndex, cue ->
                    LyricToken(
                        text = cue.text,
                        startMs = cue.timeMs,
                        endMs = cues.getOrNull(cueIndex + 1)?.timeMs,
                        partRole = LyricTextRole.ORIGINAL,
                    )
                },
            )
        }
    }

    private fun parseEntries(text: String): List<RawEntry> {
        val lines = text.lines()
        val offsetMs = lines.firstNotNullOfOrNull { line ->
            offsetTag.find(line)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0
        val entries = mutableListOf<RawEntry>()
        var openEntry: RawEntry? = null
        var allowUntimedAttach = false

        fun addEntry(entry: RawEntry) {
            entries += entry
            openEntry = entry
            allowUntimedAttach = entry.explicitTime
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // SPL: untimestamped translation must be adjacent; blank breaks the chain.
                allowUntimedAttach = false
                continue
            }
            parseKugouLine(trimmed, offsetMs)?.let { parsed ->
                if (shouldKeepParsedLine(parsed)) {
                    addEntry(
                        RawEntry(
                            timeMs = parsed.timeMs,
                            text = parsed.text,
                            cues = parsed.cues,
                            explicitTime = true,
                        ),
                    )
                }
                continue
            }
            val matches = timestamp.findAll(trimmed).toList()
            parseInlineBracketLine(trimmed, matches, offsetMs)?.let { parsed ->
                if (shouldKeepParsedLine(parsed)) {
                    addEntry(
                        RawEntry(
                            timeMs = parsed.timeMs,
                            text = parsed.text,
                            cues = parsed.cues,
                            explicitTime = true,
                        ),
                    )
                }
                continue
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
                        addEntry(
                            RawEntry(
                                timeMs = lineTimeMs.coerceAtLeast(0),
                                text = parsedBody.text,
                                cues = parsedBody.cues,
                                explicitTime = true,
                            ),
                        )
                    }
                }
                continue
            }
            if (!trimmed.startsWith("[") && !LyricsSanitizer.isIgnorableLyricText(trimmed)) {
                val normalized = MetadataTextFix.normalize(trimmed)
                val open = openEntry
                if (allowUntimedAttach && open != null && open.explicitTime) {
                    open.trailingTexts += normalized
                } else {
                    addEntry(
                        RawEntry(
                            timeMs = 0,
                            text = normalized,
                            cues = emptyList(),
                            explicitTime = false,
                        ),
                    )
                    // Orphan / plain-file lines are not SPL translation hosts.
                    allowUntimedAttach = false
                }
            }
        }
        return entries
    }

    /**
     * Same-timestamp grouping (stable by file order) plus NetEase/SPL secondary roles.
     * Two word-timed lines at the same stamp stay separate (harmony / ad-lib).
     */
    private fun groupEntries(entries: List<RawEntry>): List<GroupedLine> {
        if (entries.isEmpty()) return emptyList()
        val sorted = entries.mapIndexed { index, entry -> index to entry }
            .sortedWith(compareBy({ it.second.timeMs }, { it.first }))
            .map { it.second }

        return buildList {
            var index = 0
            while (index < sorted.size) {
                val head = sorted[index]
                if (!head.explicitTime) {
                    add(head.toGroupedLine())
                    index += 1
                    continue
                }
                val group = sorted.drop(index).takeWhile {
                    it.explicitTime && it.timeMs == head.timeMs
                }
                addAll(collapseSameTimestampGroup(group))
                index += group.size
            }
        }
    }

    private fun RawEntry.toGroupedLine(): GroupedLine = GroupedLine(
        timeMs = timeMs,
        text = text,
        cues = cues,
        secondaries = trailingTexts.toList(),
    )

    private fun collapseSameTimestampGroup(group: List<RawEntry>): List<GroupedLine> {
        if (group.size == 1) return listOf(group.single().toGroupedLine())

        val withCues = group.filter { it.cues.isNotEmpty() }
        val withoutCues = group.filter { it.cues.isEmpty() }

        // Preserve prior behavior: two (or more) word-timed lines do not merge.
        if (withCues.size >= 2 && withoutCues.isEmpty()) {
            return group.map { it.toGroupedLine() }
        }

        val main: RawEntry
        val others: List<RawEntry>
        if (withCues.size == 1) {
            main = withCues.single()
            others = group.filter { it !== main }
        } else {
            main = group.first()
            others = group.drop(1)
        }

        val secondaries = buildList {
            addAll(main.trailingTexts)
            others.forEach { other ->
                add(other.text)
                addAll(other.trailingTexts)
            }
        }
        return listOf(
            GroupedLine(
                timeMs = main.timeMs,
                text = main.text,
                cues = main.cues,
                secondaries = secondaries,
            ),
        )
    }

    private fun shouldKeepParsedLine(line: LyricLine): Boolean {
        if (line.cues.isNotEmpty()) return true
        return !LyricsSanitizer.isIgnorableLyricText(line.text)
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
