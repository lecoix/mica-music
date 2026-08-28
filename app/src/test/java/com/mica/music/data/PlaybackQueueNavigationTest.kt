package com.mica.music.data

import com.mica.music.playback.PlaybackQueueNavigation
import com.mica.music.playback.PlaybackQueueNavigationPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueNavigationTest {
    @Test
    fun alignedServiceQueueSeeksWithoutPayload() {
        val plan = PlaybackQueueNavigation.plan(
            queueIds = listOf("song-a", "song-b", "song-c"),
            requestedIndex = 2,
            songId = "song-c",
            currentMediaId = "song-a",
            serviceItemCount = 3,
            serviceMediaIdAt = { listOf("song-a", "song-b", "song-c").getOrNull(it) },
        )

        assertTrue(plan is PlaybackQueueNavigationPlan.SeekAligned)
        assertEquals(2, plan?.serviceIndex)
    }

    @Test
    fun logicalShuffleOrderSeeksByMediaIdWithoutRebuildingPhysicalQueue() {
        val service = listOf("song-a", "song-b", "song-c", "song-d")
        val logicalShuffle = listOf("song-c", "song-a", "song-d", "song-b")
        val plan = PlaybackQueueNavigation.plan(
            queueIds = logicalShuffle,
            requestedIndex = 2,
            songId = "song-d",
            currentMediaId = "song-c",
            serviceItemCount = service.size,
            serviceMediaIdAt = service::getOrNull,
        )

        assertTrue(plan is PlaybackQueueNavigationPlan.SeekAligned)
        assertEquals(3, plan?.serviceIndex)
    }

    @Test
    fun switchingSongOnMisalignedServiceQueueCarriesPayload() {
        val plan = PlaybackQueueNavigation.plan(
            queueIds = listOf("song-a", "song-b", "song-c"),
            requestedIndex = 2,
            songId = "song-c",
            currentMediaId = "song-a",
            serviceItemCount = 2,
            serviceMediaIdAt = { listOf("song-a", "song-b").getOrNull(it) },
        )

        assertTrue(plan is PlaybackQueueNavigationPlan.CarryQueuePayload)
        assertEquals(2, plan?.serviceIndex)
    }

    @Test
    fun unknownCurrentSongOnMisalignedServiceQueueSyncsQueue() {
        val plan = PlaybackQueueNavigation.plan(
            queueIds = listOf("song-a", "song-b", "song-c"),
            requestedIndex = 2,
            songId = "song-c",
            currentMediaId = null,
            serviceItemCount = 2,
            serviceMediaIdAt = { listOf("song-a", "song-b").getOrNull(it) },
        )

        assertTrue(plan is PlaybackQueueNavigationPlan.SyncQueue)
        assertEquals(2, plan?.serviceIndex)
    }

    @Test
    fun mismatchedRequestedIndexReturnsNoPlan() {
        val plan = PlaybackQueueNavigation.plan(
            queueIds = listOf("song-a", "song-b", "song-c"),
            requestedIndex = 1,
            songId = "song-c",
            currentMediaId = "song-a",
            serviceItemCount = 3,
            serviceMediaIdAt = { listOf("song-a", "song-b", "song-c").getOrNull(it) },
        )

        assertNull(plan)
    }
}
