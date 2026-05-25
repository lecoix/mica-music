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
}
