package com.mica.music.data

/**
 * 将一条 LRC 文本拆成 1～2 行用于 UI 展示。
 * 以外挂 LRC 常见的细空格（U+2009 / U+200A / U+2005）为主；同步索引仍按原始 [LyricLine]。
 */
object LyricDisplayRows {

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

    fun splitForDisplay(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf(trimmed)
        if (isCreditMetadataLine(trimmed)) return listOf(trimmed)

        splitBySpecialSpaces(trimmed)?.let { return it }
        splitByNewline(trimmed)?.let { return it }
        splitByExplicitSeparator(trimmed)?.let { return it }

        return listOf(trimmed)
    }

    fun isBilingualLine(text: String): Boolean = splitForDisplay(text).size > 1

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
