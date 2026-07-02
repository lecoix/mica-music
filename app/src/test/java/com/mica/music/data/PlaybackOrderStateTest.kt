package com.mica.music.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOrderStateTest {

    @Test
    fun shuffledOrderPinsCurrentSongAndKeepsAllIds() {
        val source = listOf("a", "b", "c", "d", "e")
        val order = PlaybackOrderState.fromSource(
            sourceIds = source,
            currentId = "c",
            shuffleEnabled = true,
            random = Random(1),
        )

        assertEquals("c", order.currentId)
        assertEquals("c", order.playbackIds.first())
        assertEquals(source.toSet(), order.playbackIds.toSet())
        assertEquals(source.size, order.playbackIds.distinct().size)
    }

    @Test
    fun nextAndPreviousFollowPlaybackOrderOnly() {
        val order = PlaybackOrderState(
            sourceIds = listOf("a", "b", "c", "d"),
            playbackIds = listOf("c", "a", "d", "b"),
            currentId = "a",
            shuffleEnabled = true,
        )

        assertEquals("d", order.nextId(manualSkip = true, repeatAll = false, repeatOne = true))
        assertEquals("d", order.nextId(manualSkip = false, repeatAll = false, repeatOne = false))
        assertEquals("a", order.nextId(manualSkip = false, repeatAll = false, repeatOne = true))
        assertEquals("c", order.previousId(repeatAll = false))
    }

    @Test
    fun disablingShuffleRestoresSourceOrderAndKeepsCurrentSong() {
        val shuffled = PlaybackOrderState.fromSource(
            sourceIds = listOf("a", "b", "c", "d"),
            currentId = "c",
            shuffleEnabled = true,
            random = Random(2),
        )

        val normal = shuffled.setShuffleEnabled(false, random = Random(2))

        assertEquals(listOf("a", "b", "c", "d"), normal.playbackIds)
        assertEquals("c", normal.currentId)
    }

    @Test
    fun insertPlayNextEditsPlaybackOrder() {
        val order = PlaybackOrderState(
            sourceIds = listOf("a", "b", "c"),
            playbackIds = listOf("a", "b", "c"),
            currentId = "b",
        )

        val inserted = order.insertPlayNext("a")

        assertEquals(listOf("b", "a", "c"), inserted.playbackIds.drop(inserted.currentOrderIndex))
        assertEquals("b", inserted.currentId)
    }

    @Test
    fun removeCurrentSelectsAdjacentReplacement() {
        val order = PlaybackOrderState(
            sourceIds = listOf("a", "b", "c"),
            playbackIds = listOf("a", "b", "c"),
            currentId = "b",
        )

        val removed = order.removeAt(1)

        assertEquals(listOf("a", "c"), removed.playbackIds)
        assertEquals("c", removed.currentId)
    }

    @Test
    fun emptyOrderIsSafe() {
        val order = PlaybackOrderState.fromSource(
            sourceIds = emptyList(),
            currentId = "missing",
            shuffleEnabled = true,
            random = Random(1),
        )

        assertTrue(order.playbackIds.isEmpty())
        assertNull(order.currentId)
        assertNull(order.nextId(manualSkip = true, repeatAll = true, repeatOne = false))
        assertNull(order.previousId(repeatAll = true))
    }
}
