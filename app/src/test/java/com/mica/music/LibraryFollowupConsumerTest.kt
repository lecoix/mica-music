package com.mica.music

import com.mica.music.data.ScanSource
import com.mica.music.data.library.LibraryFollowupOutboxCursor
import com.mica.music.data.library.LibraryFollowupOutboxItem
import com.mica.music.data.library.LibraryFollowupOutboxPage
import com.mica.music.data.library.LibraryFollowupProtocol
import com.mica.music.data.library.MembershipRemovalReason
import com.mica.music.data.library.SourceIdentityKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFollowupConsumerTest {

    @Test
    fun playlistCleanupSuccessAcknowledgesEvent() = runTest {
        val event = playlistRemovalEvent("evt-1", "song-1")
        val consumed = mutableListOf<Pair<String, String>>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            consumeConfirmedMissing = { requests ->
                consumed += requests.map { it.item.eventId to it.songId }
                requests.size
            },
        )

        assertEquals(1, consumer.drain())
        assertEquals(listOf("evt-1" to "song-1"), consumed)
    }

    @Test
    fun playlistCleanupFailureKeepsEventUnacknowledged() = runTest {
        val event = playlistRemovalEvent("evt-fail", "song-fail")
        var consumed = false
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            consumeConfirmedMissing = { consumed = true; 0 },
        )

        assertEquals(0, consumer.drain())
        assertTrue(consumed)
    }

    @Test
    fun invalidPayloadIsNotAcknowledged() = runTest {
        val event = playlistRemovalEvent("evt-invalid", "song-invalid").copy(payload = "{}")
        var consumed = false
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            consumeConfirmedMissing = { requests -> consumed = true; requests.size },
        )

        assertEquals(0, consumer.drain())
        assertTrue(!consumed)
    }

    @Test
    fun unknownActionIsNotAcknowledged() = runTest {
        val event = playlistRemovalEvent("evt-unknown", "song").copy(action = "UNKNOWN")
        var consumed = false
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(listOf(event)),
            consumeConfirmedMissing = { requests -> consumed = true; requests.size },
        )

        assertEquals(0, consumer.drain())
        assertTrue(!consumed)
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
            consumeConfirmedMissing = { requests ->
                acknowledged += requests.map { it.item.eventId }
                removed += requests.map { it.songId }
                requests.size
            },
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
            consumeConfirmedMissing = { requests ->
                val ids = requests.mapTo(hashSetOf()) { it.item.eventId }
                backlog.removeAll { it.eventId in ids }
                requests.size
            },
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
            consumeConfirmedMissing = { requests ->
                acknowledgedCount += requests.size
                requests.size
            },
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
                    LibraryFollowupProtocol.PLAYLIST_REMOVE_CONFIRMED_MISSING
                },
            )
        }
        val acknowledged = mutableListOf<String>()
        val consumer = LibraryFollowupConsumer(
            loadOutboxPage = pageLoader(events),
            consumeConfirmedMissing = { requests ->
                acknowledged += requests.map { it.item.eventId }
                requests.size
            },
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
            action = LibraryFollowupProtocol.PLAYLIST_REMOVE_CONFIRMED_MISSING,
            sourceIdentity = SourceIdentityKey(ScanSource.DEVICE, "device"),
            activationEpoch = 1L,
            stableObjectKey = songId,
            evidenceRevision = "evidence-$songId",
            removalReason = MembershipRemovalReason.CONFIRMED_MISSING,
            payload = LibraryFollowupProtocol.playlistRemovalPayload(songId),
            createdAtMs = 1L,
        )
}
