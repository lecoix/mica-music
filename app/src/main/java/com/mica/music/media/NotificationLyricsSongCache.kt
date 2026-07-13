package com.mica.music.media

import android.os.Handler
import com.mica.music.data.LyricsDocument
import com.mica.music.data.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NotificationLyricsSongCache(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val loadSong: suspend (String) -> Song?,
) {
    private data class CacheKey(val songId: String, val lyricsRevision: String)

    private val lyricsByKey = ConcurrentHashMap<CacheKey, LyricsDocument>()
    private val loadingKeys = ConcurrentHashMap.newKeySet<CacheKey>()

    fun songWithLyrics(decoded: Song, lyricsRevision: String, onLoaded: () -> Unit): Song {
        val key = CacheKey(decoded.id, lyricsRevision)
        lyricsByKey[key]?.let { return decoded.copy(lyricsDocument = it) }
        if (loadingKeys.add(key)) {
            scope.launch {
                val lyrics = runCatching { loadSong(decoded.id)?.lyricsDocument }
                    .getOrNull()
                    ?: LyricsDocument()
                lyricsByKey[key] = lyrics
                loadingKeys.remove(key)
                handler.post(onLoaded)
            }
        }
        return decoded
    }

    fun clear() {
        lyricsByKey.clear()
        loadingKeys.clear()
    }
}
