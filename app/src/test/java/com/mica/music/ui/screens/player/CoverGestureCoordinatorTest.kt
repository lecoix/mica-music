package com.mica.music.ui.screens.player

import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CoverGestureCoordinatorTest {

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "artist",
        album = "album",
        durationSec = 180,
        metadata = TrackMetadata.fallback("audio/mpeg", 320_000, id),
        albumArtUri = "content://$id",
        coverColorArgb = 0xFF000000.toInt(),
        mediaUri = "content://media/$id",
    )

    private fun queueOf(vararg ids: String) = ids.map { song(it) }

    @Test
    fun buildLaneBindings_keepsSevenLanesAroundAnchor() {
        val bindings = buildLaneBindings(queue = emptyList(), centerAnchorIndex = 2)
        assertEquals(7, bindings.size)
        assertEquals(2, bindings.first { it.laneOffset == 0 }.queueIndex)
        assertEquals(5, bindings.first { it.laneOffset == 3 }.queueIndex)
        assertEquals(null, bindings.first { it.laneOffset == -3 }.song)
    }

    @Test
    fun buildLaneBindings_placesCurrentAndNextAtExpectedOffsets() {
        val queue = queueOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val anchor = 6
        val bindings = buildLaneBindings(queue, anchor)
        val current = bindings.first { it.laneOffset == 0 }
        val next = bindings.first { it.laneOffset == 1 }
        assertEquals("g", current.song?.id)
        assertEquals("h", next.song?.id)
        assertEquals(0f, current.queueIndex - anchor.toFloat(), 0.0001f)
        assertEquals(1f, next.queueIndex - anchor.toFloat(), 0.0001f)
    }

    @Test
    fun buildLaneBindings_afterNextAnchor_includesNextSongInLanePlusOne() {
        val queue = queueOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val afterNext = buildLaneBindings(queue, centerAnchorIndex = 6)
        val nextLane = afterNext.first { it.laneOffset == 1 }
        assertNotNull(nextLane.song)
        assertEquals(7, nextLane.queueIndex)
        assertEquals("h", nextLane.song?.id)
        assertEquals(1f, nextLane.queueIndex - 6f, 0.0001f)
    }

    @Test
    fun reconcileLaneBindings_rebindsOnlyHiddenLanes() {
        val queue = queueOf("a", "b", "c", "d", "e", "f", "g", "h")
        val before = buildLaneBindings(queue, centerAnchorIndex = 1)
        val after = reconcileLaneBindings(
            bindings = before,
            anchorIndex = 4,
            virtualCenterIndex = 4f,
            queue = queue,
            coverFlowMode = PlayerCoverFlowMode.PAUSE_FOLD,
            foldProgress = 1f,
        )
        val farLane = after.first { it.laneOffset == -3 }
        assertEquals(1, farLane.queueIndex)
        assertEquals("b", farLane.song?.id)
        val centerLane = after.first { it.laneOffset == 0 }
        assertEquals("b", centerLane.song?.id)
    }

    @Test
    fun visibleOffset_usesBoundQueueIndexNotAnchor() {
        val queue = queueOf("a", "b", "c", "d", "e")
        val bindings = buildLaneBindings(queue, centerAnchorIndex = 1)
        val centerLane = bindings.first { it.laneOffset == 1 }
        val offset = centerLane.queueIndex - 2f
        assertEquals(0f, offset, 0.0001f)
    }
}
