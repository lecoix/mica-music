package com.mica.music.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import com.mica.music.queue.PlaybackShuffleOrder
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
        assertTrue(order.shuffleSeed != null)
    }

    @Test
    fun sameSeedProducesSameLogicalOrderFromDifferentPhysicalOrders() {
        val seed = 0x1234_5678L
        val first = PlaybackShuffleOrder.orderedIds(
            ids = listOf("a", "b", "c", "d", "e"),
            currentId = "c",
            seed = seed,
        )
        val second = PlaybackShuffleOrder.orderedIds(
            ids = listOf("e", "c", "a", "d", "b"),
            currentId = "c",
            seed = seed,
        )

        assertEquals(first, second)
        assertEquals("c", first.first())
    }

    @Test
    fun shuffleSeedSurvivesQueueRefreshAndClearsWhenDisabled() {
        val shuffled = PlaybackOrderState.fromSource(
            sourceIds = listOf("a", "b", "c", "d"),
            currentId = "b",
            shuffleEnabled = true,
            shuffleSeed = 42L,
        )

        val refreshed = shuffled.withQueue(listOf("d", "c", "b", "a", "e"), preserveId = "b")
        val normal = refreshed.setShuffleEnabled(false)

        assertEquals(42L, refreshed.shuffleSeed)
        assertEquals(
            PlaybackShuffleOrder.orderedIds(listOf("d", "c", "b", "a", "e"), "b", 42L),
            refreshed.playbackIds,
        )
        assertNull(normal.shuffleSeed)
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
    fun shuffledOrderStopsAfterOneNaturalPassButManualNextWraps() {
        val order = PlaybackOrderState(
            sourceIds = listOf("a", "b", "c", "d"),
            playbackIds = listOf("c", "a", "d", "b"),
            currentId = "b",
            shuffleEnabled = true,
            shuffleSeed = 42L,
        )

        assertNull(order.nextId(manualSkip = false, repeatAll = false, repeatOne = false))
        assertEquals("c", order.nextId(manualSkip = true, repeatAll = false, repeatOne = false))
    }

    @Test
    fun media3ShuffleOrderHasNoSuccessorAfterItsLastItem() {
        val seed = 42L
        val indices = PlaybackShuffleOrder.physicalIndices(
            physicalIds = listOf("a", "b", "c", "d"),
            currentId = "b",
            seed = seed,
        )
        val shuffleOrder = ShuffleOrder.DefaultShuffleOrder(indices, seed)

        assertEquals(C.INDEX_UNSET, shuffleOrder.getNextIndex(indices.last()))
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
