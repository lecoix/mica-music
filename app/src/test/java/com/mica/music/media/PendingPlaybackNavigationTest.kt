package com.mica.music.media

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPlaybackNavigationTest {
    @Test
    fun payloadBuildsSnapshotAtTargetSong() {
        val items = listOf(mediaItem("first"), mediaItem("second"), mediaItem("third"))
        val payload = PendingNavigationPayload("second", items)

        val override = payload.toNavigationOverride()

        assertEquals("second", override?.targetSongId)
        assertEquals(1, override?.queue?.currentIndex)
        assertEquals(items, override?.queue?.items)
        assertEquals(Long.MAX_VALUE, override?.queue?.revision)
    }

    @Test
    fun payloadFallsBackToFirstItemWhenTargetIsMissing() {
        val payload = PendingNavigationPayload("missing", listOf(mediaItem("first")))

        val override = payload.toNavigationOverride()

        assertEquals(0, override?.queue?.currentIndex)
        assertEquals("missing", override?.targetSongId)
    }

    @Test
    fun payloadRejectsEmptyQueue() {
        val payload = PendingNavigationPayload("missing", emptyList())

        assertNull(payload.toNavigationOverride())
    }

    @Test
    fun pendingNavigationIsConsumedOnce() {
        PendingPlaybackNavigation.prepare("second", listOf(mediaItem("first"), mediaItem("second")))

        val first = PendingPlaybackNavigation.consumeNavigationOverride()
        val second = PendingPlaybackNavigation.consumeNavigationOverride()

        assertEquals("second", first?.targetSongId)
        assertNull(second)
    }

    @Test
    fun clearDropsPendingNavigation() {
        PendingPlaybackNavigation.prepare("second", listOf(mediaItem("second")))

        PendingPlaybackNavigation.clear()

        assertNull(PendingPlaybackNavigation.consumeNavigationOverride())
    }

    private fun mediaItem(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()
}
