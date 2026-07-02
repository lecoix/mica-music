package com.mica.music.media

import com.mica.music.data.LyricLine
import com.mica.music.data.Song
import java.util.concurrent.ConcurrentHashMap

/** 进程内歌词缓存：供播放服务更新通知元数据，避免经 MediaController 跨进程 replace。 */
object NotificationLyricsCatalog {

    private val lyricsBySongId = ConcurrentHashMap<String, List<LyricLine>>()

    fun sync(songs: List<Song>) {
        val ids = songs.map { it.id }.toSet()
        lyricsBySongId.keys.retainAll(ids)
        for (song in songs) {
            lyricsBySongId[song.id] = song.lyrics
        }
    }

    fun lyricsFor(songId: String): List<LyricLine> =
        lyricsBySongId[songId].orEmpty()

    fun clear() {
        lyricsBySongId.clear()
    }
}
