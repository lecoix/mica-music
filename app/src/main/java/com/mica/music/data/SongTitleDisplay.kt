package com.mica.music.data

object SongTitleDisplay {
    fun displayTitle(title: String, stripParenthetical: Boolean): String =
        if (stripParenthetical) stripParenthetical(title) else title

    fun stripParenthetical(title: String): String {
        val ranges = parentheticalRanges(title)
        if (ranges.isEmpty()) return title

        val out = StringBuilder(title.length)
        var nextStart = 0
        for (range in ranges) {
            if (nextStart < range.first) {
                out.append(title, nextStart, range.first)
            }
            nextStart = range.last + 1
        }
        if (nextStart < title.length) {
            out.append(title, nextStart, title.length)
        }
        return collapseWhitespace(out.toString()).trim()
    }

    private fun parentheticalRanges(title: String): List<IntRange> {
        val starts = ArrayDeque<Int>()
        val ranges = mutableListOf<IntRange>()
        title.forEachIndexed { index, char ->
            when (char) {
                '(', '（' -> starts.addLast(index)
                ')', '）' -> if (starts.isNotEmpty()) {
                    ranges += starts.removeLast()..index
                }
            }
        }
        return ranges
            .sortedBy { it.first }
            .fold(mutableListOf<IntRange>()) { merged, range ->
                val previous = merged.lastOrNull()
                if (previous != null && range.first <= previous.last + 1) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
                } else {
                    merged += range
                }
                merged
            }
    }

    private fun collapseWhitespace(value: String): String =
        buildString(value.length) {
            var inWhitespace = false
            for (char in value) {
                if (char.isWhitespace()) {
                    if (!inWhitespace) append(' ')
                    inWhitespace = true
                } else {
                    append(char)
                    inWhitespace = false
                }
            }
        }
}
