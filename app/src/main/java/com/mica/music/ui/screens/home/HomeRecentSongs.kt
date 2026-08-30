package com.mica.music.ui.screens.home

import com.mica.music.data.Song

internal const val RECENT_SONG_PRESENTATION_LIMIT = 500

/** Builds the unified local + remote recent-play presentation from persisted Song stats. */
internal fun recentSongsForPresentation(
    localSongs: List<Song>,
    remoteSongs: List<Song>,
    limit: Int = RECENT_SONG_PRESENTATION_LIMIT,
): List<Song> {
    if (limit <= 0) return emptyList()
    return sequenceOf(localSongs.asSequence(), remoteSongs.asSequence())
        .flatten()
        .filter { it.lastPlayedAtMs > 0L }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<Song> { it.lastPlayedAtMs }
                .thenBy { it.id },
        )
        .take(limit)
        .toList()
}
