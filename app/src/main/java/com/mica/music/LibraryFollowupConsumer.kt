package com.mica.music

import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LibraryFollowupConsumer(
    private val loadOutbox: suspend () -> List<LibraryFollowupOutboxItem>,
    private val acknowledge: suspend (String) -> Boolean,
    private val removeSongFromAllPlaylists: (String) -> Boolean,
) {
    private val drainMutex = Mutex()

    suspend fun drain(): Int = drainMutex.withLock {
        var acknowledgedCount = 0
        loadOutbox().forEach { item ->
            val handled = when (item.action) {
                LibraryFollowupProtocol.PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP -> {
                    val songId = LibraryFollowupProtocol.playlistRemovalSongId(item.payload)
                    if (songId.isNullOrBlank()) {
                        DiagnosticLog.event(
                            "LibraryFollowup",
                            "invalid playlist-removal payload event=${item.eventId}",
                        )
                        false
                    } else {
                        removeSongFromAllPlaylists(songId)
                    }
                }
                else -> {
                    DiagnosticLog.event(
                        "LibraryFollowup",
                        "unknown action=${item.action} event=${item.eventId}",
                    )
                    false
                }
            }
            if (handled && acknowledge(item.eventId)) {
                acknowledgedCount++
            }
        }
        acknowledgedCount
    }
}
