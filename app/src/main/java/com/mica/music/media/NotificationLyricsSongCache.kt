package com.mica.music.media

import android.os.Handler
import com.mica.music.data.LyricLine
import com.mica.music.data.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NotificationLyricsSongCache(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val loadSong: suspend (String) -> Song?,
) {
    private val lyricsBySongId = ConcurrentHashMap<String, List<LyricLine>>()
    private val loadingSongIds = ConcurrentHashMap.newKeySet<String>()

    fun songWithLyrics(decoded: Song, onLoaded: () -> Unit): Song {
        lyricsBySongId[decoded.id]?.let { return decoded.copy(lyrics = it) }
        if (loadingSongIds.add(decoded.id)) {
            scope.launch {
                val lyrics = runCatching { loadSong(decoded.id)?.lyrics }
                    .getOrNull()
                    .orEmpty()
                lyricsBySongId[decoded.id] = lyrics
                loadingSongIds.remove(decoded.id)
                handler.post(onLoaded)
            }
        }
        return decoded
    }

    fun clear() {
        lyricsBySongId.clear()
        loadingSongIds.clear()
    }
}
