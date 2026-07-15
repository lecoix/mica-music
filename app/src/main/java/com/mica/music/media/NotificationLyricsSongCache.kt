package com.mica.music.media

import android.os.Handler
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsCacheKey
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class NotificationLyricsSongCache(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val loadSong: suspend (String) -> Song?,
) {
    private val loadingKeys = ConcurrentHashMap.newKeySet<LyricsCacheKey>()

    fun songWithLyrics(
        decoded: Song,
        lyricsRevision: String,
        lyricsDataVersion: Int,
        onLoaded: () -> Unit,
    ): Song {
        val key = LyricsCacheKey(decoded.id, lyricsRevision, lyricsDataVersion)
        SharedLyricsMemoryCache.get(key)?.let {
            return decoded.copy(lyricsDocument = it, lyricsLoaded = true)
        }
        if (loadingKeys.add(key)) {
            scope.launch {
                val lyrics = runCatching { loadSong(decoded.id)?.lyricsDocument }
                    .getOrNull()
                    ?: LyricsDocument()
                SharedLyricsMemoryCache.put(key, lyrics)
                loadingKeys.remove(key)
                handler.post(onLoaded)
            }
        }
        return decoded
    }

    fun clear() {
        loadingKeys.clear()
    }
}
