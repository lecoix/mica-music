package com.mica.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mica.music.MicaApp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricsSlot
import com.mica.music.data.SharedLyricsMemoryCache
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun rememberSongWithLyrics(
    library: MusicLibrary,
    song: Song,
    nextSong: Song? = null,
    priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
): Song {
    val lyricsDataVersion = library.lyricsDataVersion
    val app = LocalContext.current.applicationContext as? MicaApp
    var resolved by remember(song.id, song.source, song.lyricsCacheRevision, lyricsDataVersion, priority) {
        mutableStateOf(song)
    }
    LaunchedEffect(
        song.id,
        song.source,
        song.lyricsCacheRevision,
        nextSong?.id,
        nextSong?.source,
        nextSong?.lyricsCacheRevision,
        lyricsDataVersion,
        priority,
    ) {
        suspend fun hydrate(target: Song, isPrefetch: Boolean = false): Song = when {
            target.source == SongSource.REMOTE && app != null ->
                app.remoteLyricsRepository.songWithLyrics(target, isPrefetch)
            target.source == SongSource.REMOTE -> target
            else -> library.songWithLyrics(target, priority, isPrefetch)
        }

        coroutineScope {
            launch {
                SharedLyricsMemoryCache.invalidations.collect { songIds ->
                    if (song.id in songIds) resolved = hydrate(song)
                }
            }
            resolved = hydrate(song)
            nextSong?.let { upcoming ->
                when {
                    upcoming.source == SongSource.REMOTE && app != null ->
                        launch { app.remoteLyricsRepository.songWithLyrics(upcoming, isPrefetch = true) }
                    upcoming.source != SongSource.REMOTE -> library.prefetchLyrics(upcoming, priority)
                }
            }
        }
    }
    return resolved
}
