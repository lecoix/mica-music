package com.mica.music.data

/**
 * Text-only LRC layout compatibility. Display splits never establish semantic translation.
 * The semantic lyric line and its word timing remain unchanged.
 */
object LyricDisplayRows {

    data class DisplayRow(
        val text: String,
        val start: Int,
        val endExclusive: Int,
        val splitIndex: Int,
        val role: LyricTextRole = LyricTextRole.ORIGINAL,
        /** Legacy split is a visual secondary line, not semantic TRANSLATION. */
        val inferredSecondary: Boolean = false,
        val ruby: List<LyricRuby> = emptyList(),
    ) {
        val usesSecondaryStyle: Boolean
            get() = role == LyricTextRole.TRANSLATION ||
                role == LyricTextRole.READING || inferredSecondary
    }

    fun rowsFromPartsWithRuby(
        parts: List<LyricTextPart>,
        tokens: List<LyricToken>,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
        readingEnabled: Boolean = true,
        splitEnabled: Boolean = true,
        authoritativeOriginalOnly: Boolean = false,
    ): List<DisplayRow>? {
        val rows = rowsFromParts(parts, mode, readingEnabled, splitEnabled, authoritativeOriginalOnly) ?: return null
        if (!readingEnabled || mode == LyricsBilingualDisplayMode.ORIGINAL) return rows
        val original = parts.singleOrNull { it.role == LyricTextRole.ORIGINAL } ?: return rows
        // Bound per-unit text layout allocation, including malformed or unusually long source lines.
        if (original.text.length > 4096 || original.text.contains('\n') || original.text.contains('\r')) return rows
        if (rows.none { it.role == LyricTextRole.ORIGINAL && it.text == original.text }) return rows
        val ruby = lyricRubyAlignment(parts, tokens)
        if (ruby.isEmpty()) return rows
        return rows.filter { it.role != LyricTextRole.READING }.map {
            if (it.role == LyricTextRole.ORIGINAL) it.copy(ruby = ruby) else it
        }
    }

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

    /** Recognised metadata/credit prefixes, including mixed CJK/English credits. */
    private val creditPrefix = Regex(
        """(?i)^\s*(?:作词|作詞|作曲|编曲|編曲|词|詞|曲|编|編|监|監|演唱|歌手|合唱|原唱|翻唱|和声|混音|母带|""" +
            """录音|录音棚|录音师|录音监督|编写|制作|制作人|助理|谱务|指挥|乐团|""" +
            """音乐监制|录音承办人|人声编辑|艺术家|艺人|歌曲|歌名|标题|""" +
            """lyrics?\s+by|composed\s+by|composer|arranger|lyricist|vocal|""" +
            """singer|recording|mixing|mastering|engineer|studio|assistant|scoring|""" +
            """instrument|guitar|violin|conductor|orchestra|producer|title|artist)""",
    )

    fun splitForDisplay(text: String, enabled: Boolean = true): List<String> {
        val trimmed = text.trim()
        if (!enabled) return listOf(trimmed)
        if (trimmed.isEmpty()) return listOf(trimmed)
        if (isCreditMetadataLine(trimmed)) return listOf(trimmed)

        // Explicit line breaks are physical ORIGINAL rows even when there are > 2.
        splitByNewline(trimmed)?.let { return it }
        splitBySpecialSpaces(trimmed)?.let { return it }
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
            DisplayRow(
                text = row,
                start = start,
                endExclusive = end,
                splitIndex = index,
                inferredSecondary = index > 0 && !text.contains('\n'),
            )
        }
    }

    fun rowsForBilingualDisplayMode(
        text: String,
        enabled: Boolean = true,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
    ): List<DisplayRow> {
        val rows = splitForDisplayRows(text, enabled)
        if (!enabled || rows.size < 2 || rows.none { it.inferredSecondary }) return rows
        // Keep legacy bilingual *display* modes; never invent TRANSLATION metadata.
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
    /**
     * Source roles are authoritative for TTML / SYLT, including an ORIGINAL-only line.
     * LRC/PLAIN may be created by toLyricsDocumentCompat and must retain visual splitting.
     */
    fun hasAuthoritativeOriginalRole(format: LyricsFormat): Boolean =
        format == LyricsFormat.TTML || format == LyricsFormat.SYLT

    fun rowsForDocumentLine(
        document: LyricsDocument,
        index: Int,
        text: String,
        enabled: Boolean = true,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
        readingEnabled: Boolean = true,
    ): List<DisplayRow> =
        rowsFromPartsWithRuby(
            parts = document.lines.getOrNull(index)?.parts.orEmpty(),
            tokens = document.lines.getOrNull(index)?.tokens.orEmpty(),
            mode = mode,
            readingEnabled = readingEnabled,
            splitEnabled = enabled,
            authoritativeOriginalOnly = hasAuthoritativeOriginalRole(document.format) ||
                LyricsSync.isWordTimedTokens(document.lines.getOrNull(index)?.tokens.orEmpty()),
        ) ?: rowsForBilingualDisplayMode(text, enabled, mode)

    fun rowsFromParts(
        parts: List<LyricTextPart>,
        mode: LyricsBilingualDisplayMode = LyricsBilingualDisplayMode.ALL,
        readingEnabled: Boolean = true,
        splitEnabled: Boolean = true,
        authoritativeOriginalOnly: Boolean = false,
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
            (authoritativeOriginalOnly && original.isNotEmpty())
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

        // Do not merge source-provided semantic tracks. SPLIT only governs
        // heuristic visual rows of ordinary untimed LRC text.
        val built = when (mode) {
            LyricsBilingualDisplayMode.ALL -> buildList {
                if (readingEnabled) row(LyricTextRole.READING, reading, 0)?.let(::add)
                row(LyricTextRole.ORIGINAL, original, size)?.let(::add)
                row(LyricTextRole.TRANSLATION, translation, size)?.let(::add)
            }
            LyricsBilingualDisplayMode.ORIGINAL -> listOfNotNull(row(LyricTextRole.ORIGINAL, original, 0))
            LyricsBilingualDisplayMode.TRANSLATION -> listOfNotNull(
                if (translation.isNotEmpty()) row(LyricTextRole.TRANSLATION, translation, 0)
                else row(LyricTextRole.ORIGINAL, original, 0),
            )
        }
        return built.takeIf { it.isNotEmpty() }
    }

    /** Returns the word-timed tokens owned by one displayed semantic row. */
    fun tokensForRole(tokens: List<LyricToken>, role: LyricTextRole): List<LyricToken> =
        tokens.filter { token ->
            when (role) {
                LyricTextRole.ORIGINAL -> token.partRole == LyricTextRole.ORIGINAL ||
                    token.partRole == LyricTextRole.EXTRA
                LyricTextRole.EXTRA -> token.partRole == LyricTextRole.EXTRA
                LyricTextRole.READING -> token.partRole == LyricTextRole.READING
                LyricTextRole.TRANSLATION -> token.partRole == LyricTextRole.TRANSLATION
            }
        }

    fun isBilingualLine(text: String, enabled: Boolean = true): Boolean =
        splitForDisplay(text, enabled).size > 1

    /** Text separators are display-only heuristics, never persistent translation metadata. */
    fun splitPartsAtIngest(parts: List<LyricTextPart>): List<LyricTextPart> = parts

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

    private val creditFieldHint = Regex(
        """(?i)(guitar|instrument|recording|studio|engineer|mastering|mixing|vocal|orchestra)""",
    )
    private fun isCreditMetadataLine(text: String): Boolean {
        val colonAt = text.indexOfAny(charArrayOf(':', '：'))
        if (colonAt !in 1..80) return false
        val field = text.substring(0, colonAt)
        return creditPrefix.containsMatchIn(field) || creditFieldHint.containsMatchIn(field)
    }

    private fun splitByNewline(text: String): List<String>? {
        if (!text.contains('\n')) return null
        val parts = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return parts.takeIf { it.size >= 2 }
    }

    private fun splitByExplicitSeparator(text: String): List<String>? {
        for (sep in explicitSeparators) {
            if (sep !in text) continue
            val parts = text.split(sep, limit = 2)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size == 2) return parts
        }
        // Bare '/' is ordinary content: names (AC/DC), credits, dates and song text.
        return null
    }
}
