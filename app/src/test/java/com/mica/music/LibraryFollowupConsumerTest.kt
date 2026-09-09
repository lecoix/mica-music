package com.mica.music

import com.mica.music.data.ScanSource
import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupOutboxPage
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.data.library.SourceIdentityKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFollowupConsumerTest {

    @Test
    fun playlistCleanupSuccessAcknowledgesEvent() = runTest {
        val event = playlistRemovalEvent("evt-1", "song-1")
        val acknowledged = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            acknowledge = { id -> acknowledged += id; true },
            removeSongFromAllPlaylists = { id -> removed += id; true },
        )

        assertEquals(1, consumer.drain())
        assertEquals(listOf("song-1"), removed)
        assertEquals(listOf("evt-1"), acknowledged)
    }

    @Test
    fun playlistCleanupFailureKeepsEventUnacknowledged() = runTest {
        val event = playlistRemovalEvent("evt-fail", "song-fail")
        val acknowledged = mutableListOf<String>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            acknowledge = { id -> acknowledged += id; true },
            removeSongFromAllPlaylists = { false },
        )

        assertEquals(0, consumer.drain())
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun invalidPayloadIsNotAcknowledged() = runTest {
        val event = playlistRemovalEvent("evt-invalid", "song-invalid").copy(payload = "{}")
        var acknowledged = false
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            acknowledge = { acknowledged = true; true },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(0, consumer.drain())
        assertTrue(!acknowledged)
    }

    @Test
    fun unknownActionIsNotAcknowledged() = runTest {
        val event = playlistRemovalEvent("evt-unknown", "song").copy(action = "UNKNOWN")
        var acknowledged = false
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            acknowledge = { acknowledged = true; true },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(0, consumer.drain())
        assertTrue(!acknowledged)
    }

    @Test
    fun drainConsumesMultipleKeysetPagesInStableOrder() = runTest {
        val events = List(130) { index ->
            playlistRemovalEvent(
                eventId = "evt-${index.toString().padStart(3, '0')}",
                songId = "song-$index",
            ).copy(createdAtMs = (index / 2).toLong())
        }
        val pageLimits = mutableListOf<Int>()
        val acknowledged = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val loader = pageLoader(events) { _, limit -> pageLimits += limit }
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = loader,
            acknowledge = { id -> acknowledged += id; true },
            removeSongFromAllPlaylists = { id -> removed += id; true },
        )

        assertEquals(130, consumer.drain())
        assertEquals(events.map(LibraryFollowupOutboxItem::stableObjectKey), removed)
        assertEquals(events.map(LibraryFollowupOutboxItem::eventId), acknowledged)
        assertEquals(listOf(64, 64, 64), pageLimits)
    }

    @Test
    fun drainYieldsAtFixedBudgetAndContinuesOnNextInvocation() = runTest {
        val backlog = MutableList(600) { index ->
            playlistRemovalEvent(
                eventId = "evt-${index.toString().padStart(4, '0')}",
                songId = "song-$index",
            ).copy(createdAtMs = index.toLong())
        }
        val pageLimits = mutableListOf<Int>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader({ backlog.toList() }) { _, limit -> pageLimits += limit },
            acknowledge = { id ->
                backlog.removeAll { it.eventId == id }
                true
            },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(LibraryFollowupConsumer.MAX_ITEMS_PER_DRAIN, consumer.drain())
        assertEquals(88, backlog.size)
        assertTrue(pageLimits.all { it <= LibraryFollowupConsumer.PAGE_SIZE })

        pageLimits.clear()
        assertEquals(88, consumer.drain())
        assertTrue(backlog.isEmpty())
        assertTrue(pageLimits.all { it <= LibraryFollowupConsumer.PAGE_SIZE })
    }

    @Test
    fun tenKDrainToTailKeepsEveryBatchBoundedWithoutAnotherLibraryEvent() = runTest {
        val events = List(10_000) { index ->
            playlistRemovalEvent(
                eventId = "evt-${index.toString().padStart(5, '0')}",
                songId = "song-$index",
            ).copy(createdAtMs = index.toLong())
        }
        val pageLimits = mutableListOf<Int>()
        var acknowledgedCount = 0
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = { cursor, limit ->
                pageLimits += limit
                val startIndex = if (cursor == LibraryFollowupOutboxCursor.Start) {
                    0
                } else {
                    cursor.eventId.removePrefix("evt-").toInt() + 1
                }
                val items = if (startIndex >= events.size) {
                    emptyList()
                } else {
                    events.subList(startIndex, minOf(startIndex + limit, events.size))
                }
                val nextCursor = items.lastOrNull()?.let { item ->
                    LibraryFollowupOutboxCursor(item.createdAtMs, item.eventId)
                }
                LibraryFollowupOutboxPage(items, nextCursor)
            },
            acknowledge = {
                acknowledgedCount += 1
                true
            },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(10_000, consumer.drainToTail())
        assertEquals(10_000, acknowledgedCount)
        assertTrue(pageLimits.all { it <= LibraryFollowupConsumer.PAGE_SIZE })
        assertTrue(pageLimits.size > LibraryFollowupConsumer.MAX_PAGES_PER_DRAIN)
    }

    @Test
    fun boundedDrainResumePreventsUnacknowledgedPrefixStarvation() = runTest {
        val events = List(600) { index ->
            playlistRemovalEvent(
                eventId = "evt-${index.toString().padStart(4, '0')}",
                songId = "song-$index",
            ).copy(
                createdAtMs = index.toLong(),
                action = if (index < LibraryFollowupConsumer.MAX_ITEMS_PER_DRAIN) {
                    "UNKNOWN"
                } else {
                    LibraryFollowupProtocol.PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP
                },
            )
        }
        val acknowledged = mutableListOf<String>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(events),
            acknowledge = { id -> acknowledged += id; true },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(0, consumer.drain())
        assertEquals(88, consumer.drain())
        assertEquals(
            events.drop(LibraryFollowupConsumer.MAX_ITEMS_PER_DRAIN)
                .map(LibraryFollowupOutboxItem::eventId),
            acknowledged,
        )
    }

    private fun pageLoader(
        events: List<LibraryFollowupOutboxItem>,
        onLoad: (LibraryFollowupOutboxCursor, Int) -> Unit = { _, _ -> },
    ): suspend (LibraryFollowupOutboxCursor, Int) -> LibraryFollowupOutboxPage =
        pageLoader({ events }, onLoad)

    private fun pageLoader(
        events: () -> List<LibraryFollowupOutboxItem>,
        onLoad: (LibraryFollowupOutboxCursor, Int) -> Unit = { _, _ -> },
    ): suspend (LibraryFollowupOutboxCursor, Int) -> LibraryFollowupOutboxPage = { cursor, limit ->
        onLoad(cursor, limit)
        val items = events()
            .asSequence()
            .filter { item ->
                item.createdAtMs > cursor.createdAtMs ||
                    (item.createdAtMs == cursor.createdAtMs && item.eventId > cursor.eventId)
            }
            .sortedWith(
                compareBy(
                    LibraryFollowupOutboxItem::createdAtMs,
                    LibraryFollowupOutboxItem::eventId,
                ),
            )
            .take(limit)
            .toList()
        val nextCursor = items.lastOrNull()?.let { item ->
            LibraryFollowupOutboxCursor(item.createdAtMs, item.eventId)
        }
        LibraryFollowupOutboxPage(items, nextCursor)
    }

    private fun playlistRemovalEvent(
        eventId: String,
        songId: String,
    ): LibraryFollowupOutboxItem =
        LibraryFollowupOutboxItem(
            eventId = eventId,
            libraryRevision = 1L,
            action = LibraryFollowupProtocol.PLAYLIST_REMOVE_LIBRARY_MEMBERSHIP,
            sourceIdentity = SourceIdentityKey(ScanSource.DEVICE, "device"),
            activationEpoch = 1L,
            stableObjectKey = songId,
            payload = LibraryFollowupProtocol.playlistRemovalPayload(songId),
            createdAtMs = 1L,
        )
}
