package com.mica.music.data

/**
 * 将一条 LRC 文本拆成 1～2 行用于 UI 展示。
 * 以外挂 LRC 常见的细空格（U+2009 / U+200A / U+2005）为主；同步索引仍按原始 [LyricLine]。
 */
object LyricDisplayRows {

    data class DisplayRow(
        val text: String,
        val start: Int,
        val endExclusive: Int,
        val splitIndex: Int,
        val role: LyricTextRole = LyricTextRole.ORIGINAL,
    )

    /** LRC 原文与译文之间常见的不可见窄空格 */
    private val specialSpaceSeparators = charArrayOf(
        '\u2009', // thin space
        '\u200A', // hair space
        '\u2005', // four-per-em space
    )

    private val explicitSeparators = listOf(
        " // ",
        " / ",
        " | ",
        "／",
        "｜",
        "\t",
    )

    /** 制作信息行不拆分 */
    private val creditLineHint = Regex(
        """(?i)(composer|arranger|lyricist|vocal|mixing|recording|mastering|engineer|""" +
            """studio|produced|assistant|scoring|solo|instrumental|guita|violin)""",
    )

    fun splitForDisplay(text: String, enabled: Boolean = true): List<String> {
        val trimmed = text.trim()
        if (!enabled) return listOf(trimmed)
        if (trimmed.isEmpty()) return listOf(trimmed)
        if (isCreditMetadataLine(trimmed)) return listOf(trimmed)

        splitBySpecialSpaces(trimmed)?.let { return it }
        splitByNewline(trimmed)?.let { return it }
        splitByExplicitSeparator(trimmed)?.let { return it }

        return listOf(trimmed)
    }

    /** Display rows plus their ranges in the original line, used to retain cue styling after splitting. */
    fun splitForDisplayRows(text: String, enabled: Boolean = true): List<DisplayRow> {
        val rows = splitForDisplay(text, enabled)
        var searchFrom = 0
        return rows.mapIndexed { index, row ->
            val start = text.indexOf(row, startIndex = searchFrom).takeIf { it >= 0 }
                ?: text.indexOf(row).coerceAtLeast(0)
            val end = (start + row.length).coerceAtMost(text.length)
            searchFrom = end
            DisplayRow(row, start, end, index)
        }
    }

    fun rowsForBilingualDisplayMode(
        text: String,
        enabled: Boolean = true,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    ): List<DisplayRow> {
        val rows = splitForDisplayRows(text, enabled).mapIndexed { index, row ->
            row.copy(role = if (index == 0) LyricTextRole.ORIGINAL else LyricTextRole.TRANSLATION)
        }
        if (!enabled || rows.size < 2) return rows
        return when (mode) {
            LyricsBilingualDisplayMode.ALL -> rows
            LyricsBilingualDisplayMode.ORIGINAL -> rows.take(1)
            LyricsBilingualDisplayMode.TRANSLATION -> rows.drop(1).take(1)
        }
    }

    /**
     * Structured parts path for TTML / LRC-SPL: READING above ORIGINAL, TRANSLATION below.
     * Returns null when [parts] cannot drive display (caller should fall back to text split).
     */
    fun rowsFromParts(
        parts: List<LyricTextPart>,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
        readingEnabled: Boolean = true,
    ): List<DisplayRow>? {
        if (parts.isEmpty()) return null
        val reading = parts
            .filter { it.role == LyricTextRole.READING }
            .joinToString(" ") { it.text.trim() }
            .trim()
        val original = parts
            .filter { it.role == LyricTextRole.ORIGINAL || it.role == LyricTextRole.EXTRA }
            .joinToString(" ") { it.text.trim() }
            .trim()
        val translation = parts
            .filter { it.role == LyricTextRole.TRANSLATION }
            .joinToString(" ") { it.text.trim() }
            .trim()
        val structured = reading.isNotEmpty() || translation.isNotEmpty() ||
            parts.any { it.role == LyricTextRole.ORIGINAL }
        if (!structured) return null

        fun row(role: LyricTextRole, text: String, splitIndex: Int): DisplayRow? {
            if (text.isEmpty()) return null
            return DisplayRow(
                text = text,
                start = 0,
                endExclusive = text.length,
                splitIndex = splitIndex,
                role = role,
            )
        }

        val built = when (mode) {
            LyricsBilingualDisplayMode.ALL -> buildList {
                if (readingEnabled) row(LyricTextRole.READING, reading, 0)?.let(::add)
                row(LyricTextRole.ORIGINAL, original, size)?.let(::add)
                row(LyricTextRole.TRANSLATION, translation, size)?.let(::add)
            }
            LyricsBilingualDisplayMode.ORIGINAL -> buildList {
                if (readingEnabled) row(LyricTextRole.READING, reading, 0)?.let(::add)
                row(LyricTextRole.ORIGINAL, original, size)?.let(::add)
            }
            LyricsBilingualDisplayMode.TRANSLATION -> listOfNotNull(
                row(LyricTextRole.TRANSLATION, translation.ifEmpty { original }, 0),
            )
        }
        return built.takeIf { it.isNotEmpty() }
    }

    fun isBilingualLine(text: String, enabled: Boolean = true): Boolean =
        splitForDisplay(text, enabled).size > 1

    /** 在最后一个细空格类字符处切成两行。 */
    private fun splitBySpecialSpaces(text: String): List<String>? {
        var splitAt = -1
        for (i in text.indices) {
            if (text[i] in specialSpaceSeparators) splitAt = i
        }
        if (splitAt < 0) return null
        val left = text.substring(0, splitAt).trim()
        val right = text.substring(splitAt + 1).trim()
        if (left.isEmpty() || right.isEmpty()) return null
        return listOf(left, right)
    }

    private fun isCreditMetadataLine(text: String): Boolean =
        creditLineHint.containsMatchIn(text) && (':' in text || '：' in text)

    private fun splitByNewline(text: String): List<String>? {
        if (!text.contains('\n')) return null
        val parts = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .toList()
        return parts.takeIf { it.size == 2 }
    }

    private fun splitByExplicitSeparator(text: String): List<String>? {
        for (sep in explicitSeparators) {
            if (sep !in text) continue
            val parts = text.split(sep, limit = 2)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size == 2) return parts
        }
        if ('/' in text) {
            val parts = text.split('/', limit = 2)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size == 2) return parts
        }
        return null
    }
}
