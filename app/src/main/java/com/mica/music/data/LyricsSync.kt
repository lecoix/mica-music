package com.mica.music.data

/** 歌词与播放进度对齐（播放页三行与全屏歌词页共用）。 */
object LyricsSync {

    /** 略提前切换当前行，抵消听感上的滞后。 */
    const val LEAD_MS = 150

    fun hasTimedLyrics(lyrics: List<LyricLine>): Boolean =
        lyrics.any { it.timeMs > 0 }

    fun indexForPosition(lyrics: List<LyricLine>, positionMs: Int): Int {
        if (lyrics.isEmpty() || !hasTimedLyrics(lyrics)) return -1
        val t = positionMs + LEAD_MS
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= t) idx = i else break
        }
        return idx
    }

    /** Returns the active cue in [line], or -1 when the line has no usable word timing. */
    fun cueIndexForPosition(line: LyricLine, positionMs: Int): Int {
        if (line.cues.isEmpty()) return -1
        val t = positionMs + LEAD_MS
        if (t < line.timeMs || t < line.cues.first().timeMs) return -1
        return line.cues.indexOfLast { it.timeMs <= t }
    }
}
