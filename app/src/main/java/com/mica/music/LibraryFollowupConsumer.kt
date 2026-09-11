package com.mica.music

import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxPage
import com.mica.music.data.library.LibraryFollowupPaging
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LibraryFollowupConsumer(
    private val loadOutboxPage: suspend (
        LibraryFollowupOutboxCursor,
        Int,
    ) -> LibraryFollowupOutboxPage,
    private val acknowledge: suspend (String) -> Boolean,
    private val removeSongFromAllPlaylists: (String) -> Boolean,
) {
    private val drainMutex = Mutex()
    private var resumeCursor = LibraryFollowupOutboxCursor.Start

    suspend fun drain(): Int = drainMutex.withLock {
        drainBatchLocked().acknowledgedCount
    }

    /**
     * Drains a large backlog as bounded batches, yielding between batches so one publication can
     * eventually consume more than [MAX_ITEMS_PER_DRAIN] without requiring another library event.
     */
    suspend fun drainToTail(): Int = drainMutex.withLock {
        var totalAcknowledged = 0
        while (true) {
            val batch = drainBatchLocked()
            totalAcknowledged += batch.acknowledgedCount
            if (!batch.continuationRequired) break
            yield()
        }
        totalAcknowledged
    }

    private suspend fun drainBatchLocked(): DrainBatchResult {
        var acknowledgedCount = 0
        var cursor = resumeCursor
        var pagesRead = 0
        var itemsRead = 0
        var reachedTail = false

        while (pagesRead < MAX_PAGES_PER_DRAIN && itemsRead < MAX_ITEMS_PER_DRAIN) {
            val pageLimit = minOf(PAGE_SIZE, MAX_ITEMS_PER_DRAIN - itemsRead)
            val page = loadOutboxPage(cursor, pageLimit)
            if (page.items.isEmpty()) {
                reachedTail = true
                break
            }

            page.items.forEach { item ->
                val handled = when (item.action) {
                    LibraryFollowupProtocol.PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP -> {
                        val songId = LibraryFollowupProtocol.playlistRemovalSongId(item.payload)
                        if (songId.isNullOrBlank()) {
                            DiagnosticLog.important(
                                "LibraryFollowup",
                                "invalid playlist-removal payload event=${item.eventId}",
                            )
                            false
                        } else {
                            removeSongFromAllPlaylists(songId)
                        }
                    }
                    else -> {
                        DiagnosticLog.important(
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

            pagesRead += 1
            itemsRead += page.items.size
            val next = page.nextCursor
            if (next == null || next == cursor) {
                reachedTail = true
                break
            }
            cursor = next
            if (page.items.size < pageLimit) {
                reachedTail = true
                break
            }
        }

        val budgetExhausted =
            itemsRead >= MAX_ITEMS_PER_DRAIN || pagesRead >= MAX_PAGES_PER_DRAIN
        val continuationRequired = budgetExhausted && !reachedTail
        resumeCursor = if (continuationRequired) {
            cursor
        } else {
            LibraryFollowupOutboxCursor.Start
        }
        if (continuationRequired) {
            DiagnosticLog.event(
                "LibraryFollowup",
                "bounded-drain-yield pages=$pagesRead items=$itemsRead " +
                    "acknowledged=$acknowledgedCount resume=${resumeCursor.eventId}",
            )
        }
        return DrainBatchResult(
            acknowledgedCount = acknowledgedCount,
            continuationRequired = continuationRequired,
        )
    }

    private data class DrainBatchResult(
        val acknowledgedCount: Int,
        val continuationRequired: Boolean,
    )

    internal companion object {
        const val PAGE_SIZE = LibraryFollowupPaging.PAGE_SIZE
        const val MAX_PAGES_PER_DRAIN = LibraryFollowupPaging.MAX_PAGES_PER_DRAIN
        const val MAX_ITEMS_PER_DRAIN = PAGE_SIZE * MAX_PAGES_PER_DRAIN
    }
}
