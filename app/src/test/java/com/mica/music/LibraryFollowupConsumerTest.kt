package com.mica.music

import com.mica.music.data.ScanSource
import com.mica.music.data.library.LibraryFollowupOutboxItem
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
            loadOutbox = { listOf(event) },
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
            loadOutbox = { listOf(event) },
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
            loadOutbox = { listOf(event) },
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
            loadOutbox = { listOf(event) },
            acknowledge = { acknowledged = true; true },
            removeSongFromAllPlaylists = { true },
        )

        assertEquals(0, consumer.drain())
        assertTrue(!acknowledged)
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
