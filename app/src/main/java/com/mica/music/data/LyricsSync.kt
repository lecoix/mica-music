package com.mica.music.data

/** 歌词与播放进度对齐（播放页三行与全屏歌词页共用）。 */
object LyricsSync {

    /** True when [tokens] carry at least two distinct word-level timestamps. */
    fun isWordTimedTokens(tokens: List<LyricToken>): Boolean {
        val meaningfulTokens = tokens.filter { it.text.isNotBlank() }
        return meaningfulTokens.size >= 2 &&
            meaningfulTokens.map { it.startMs }.distinct().size >= 2 &&
            meaningfulTokens.zipWithNext().all { (left, right) -> right.startMs >= left.startMs }
    }

    fun hasTimedLyrics(lyrics: List<LyricLine>): Boolean =
        lyrics.any { it.timeMs > 0 }

    fun indexForPosition(lyrics: List<LyricLine>, positionMs: Int): Int {
        if (lyrics.isEmpty() || !hasTimedLyrics(lyrics)) return -1
        val t = positionMs
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= t) idx = i else break
        }
        return idx
    }

    /** Returns the active cue in [line], or -1 when the line has no usable word timing. */
    fun cueIndexForPosition(line: LyricLine, positionMs: Int): Int {
        if (line.cues.isEmpty()) return -1
        val t = positionMs
        if (t < line.timeMs || t < line.cues.first().timeMs) return -1
        return line.cues.indexOfLast { it.timeMs <= t }
    }
}
