package com.mica.music.data.library

import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricsSlot
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LibraryLyricsHydrator(
    private val backing: MusicLibraryBacking,
) {
    suspend fun hydrate(
        song: Song,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
        isPrefetch: Boolean = false,
    ): Song {
        if (song.lyricsLoaded) return song
        val priorityRevision = priority.joinToString(separator = ",", transform = LyricsSlot::name)
        val cacheRevision = "${song.lyricsCacheRevision}:$priorityRevision"
        SharedLyricsMemoryCache.get(song.id, cacheRevision, backing.lyricsDataVersion)?.let {
            return song.copy(lyricsDocument = it, lyricsLoaded = true)
        }
        val lyrics = withContext(backing.ioDispatcher) {
            SharedLyricsMemoryCache.load(
                song.id,
                cacheRevision,
                backing.lyricsDataVersion,
                isPrefetch,
            ) {
                backing.libraryStore.loadLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    priority,
                )
            }
        }
        return song.copy(lyricsDocument = lyrics, lyricsLoaded = true)
    }

    fun prefetch(
        song: Song?,
        priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
    ) {
        if (song == null || song.lyricsLoaded) return
        backing.ioScope.launch { hydrate(song, priority, isPrefetch = true) }
    }
}
