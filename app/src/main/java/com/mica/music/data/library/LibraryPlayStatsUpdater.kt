package com.mica.music.data.library

import com.mica.music.data.PlayHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LibraryPlayStatsUpdater(
    private val backing: MusicLibraryBacking,
) {
    private val catalog get() = backing.catalog

    fun onSongPlayed(songId: String) {
        backing.ioScope.launch {
            val stats = PlayHistoryStore.recordPlay(backing.context, songId)
            withContext(Dispatchers.Main.immediate) {
                catalog.applyPlayStats(songId, stats)
            }
        }
    }

    fun onSongListened(songId: String, seconds: Long) {
        if (seconds <= 0L) return
        backing.ioScope.launch {
            val stats = PlayHistoryStore.recordListenSeconds(backing.context, songId, seconds)
            withContext(Dispatchers.Main.immediate) {
                catalog.applyPlayStats(songId, stats)
            }
        }
    }
}
