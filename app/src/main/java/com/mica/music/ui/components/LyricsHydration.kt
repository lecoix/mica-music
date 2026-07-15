package com.mica.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricsSlot

@Composable
internal fun rememberSongWithLyrics(
    library: MusicLibrary,
    song: Song,
    nextSong: Song? = null,
    priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
): Song {
    var resolved by remember(song.id, song.lyricsCacheRevision, priority) { mutableStateOf(song) }
    LaunchedEffect(song.id, song.lyricsCacheRevision, nextSong?.id, nextSong?.lyricsCacheRevision, priority) {
        resolved = library.songWithLyrics(song, priority)
        library.prefetchLyrics(nextSong, priority)
    }
    return resolved
}
