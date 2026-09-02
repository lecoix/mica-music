package com.mica.music.data.library

import android.os.SystemClock
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricsSlot
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
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
            DiagnosticLog.event(
                "LyricsCache",
                "hit song=${song.id.takeLast(12)} lines=${it.lines.size} " +
                    "sizeBytes=${SharedLyricsMemoryCache.sizeBytes()} " +
                    "entries=${SharedLyricsMemoryCache.entryCount()}",
            )
            return song.copy(lyricsDocument = it, lyricsLoaded = true)
        }
        val startedMs = SystemClock.elapsedRealtime()
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
            }.also {
                DiagnosticLog.event(
                    "LyricsCache",
                    "miss song=${song.id.takeLast(12)} lines=${it.lines.size} " +
                        "durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                        "sizeBytes=${SharedLyricsMemoryCache.sizeBytes()} " +
                        "entries=${SharedLyricsMemoryCache.entryCount()}",
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
