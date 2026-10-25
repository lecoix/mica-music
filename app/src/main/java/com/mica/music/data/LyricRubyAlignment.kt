package com.mica.music.data

/** No language heuristics: explicit ranges win; independent tracks need exact, unique intervals. */
fun lyricRubyAlignment(
    parts: List<LyricTextPart>,
    tokens: List<LyricToken>,
    allowSourceTimingMatch: Boolean = false,
): List<LyricRuby> {
    val originals = parts.filter { it.role == LyricTextRole.ORIGINAL || it.role == LyricTextRole.EXTRA }
    val original = originals.singleOrNull() ?: return emptyList()
    val reading = parts.filter { it.role == LyricTextRole.READING }.joinToString("") { it.text }
    if (reading.isBlank()) return emptyList()
    if (original.ruby.isNotEmpty()) {
        return original.ruby.takeIf { validLyricRuby(original.text, it) && sameReading(it, reading) }.orEmpty()
    }
    // Only the parser may use raw source times. Persisted token ends may have been inferred.
    if (!allowSourceTimingMatch) return emptyList()
    val readings = tokens.filter { it.partRole == LyricTextRole.READING }
    if (readings.isEmpty()) return emptyList()
    var cursor = 0
    val timedOriginals = tokens.filter { it.partRole == LyricTextRole.ORIGINAL }.map { token ->
        val base = token.text.trim()
        while (cursor < original.text.length && original.text[cursor].isWhitespace()) cursor++
        if (base.isEmpty() || !original.text.startsWith(base, cursor)) return emptyList()
        val start = cursor
        cursor += base.length
        token to (start to cursor)
    }.groupBy { it.first.startMs to it.first.endMs }
    val result = ArrayList<LyricRuby>(readings.size)
    for (token in readings) {
        if (token.endMs == null || token.endMs <= token.startMs || token.text.isBlank()) return emptyList()
        val (_, range) = timedOriginals[token.startMs to token.endMs]?.singleOrNull() ?: return emptyList()
        result += LyricRuby(range.first, range.second, token.text.trim())
    }
    return result.takeIf { validLyricRuby(original.text, it) && sameReading(it, reading) }.orEmpty()
}

fun validLyricRuby(text: String, ruby: List<LyricRuby>): Boolean {
    var end = 0
    for (annotation in ruby) {
        if (annotation.start < end || annotation.endExclusive <= annotation.start ||
            annotation.endExclusive > text.length || annotation.reading.isBlank() ||
            annotation.start.splitsSurrogate(text) || annotation.endExclusive.splitsSurrogate(text)
        ) return false
        end = annotation.endExclusive
    }
    return true
}

private fun Int.splitsSurrogate(text: String): Boolean =
    this in 1 until text.length && text[this].isLowSurrogate() && text[this - 1].isHighSurrogate()

private fun sameReading(ruby: List<LyricRuby>, reading: String): Boolean =
    ruby.joinToString("") { it.reading }.filterNot(Char::isWhitespace) == reading.filterNot(Char::isWhitespace)
