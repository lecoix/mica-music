package com.mica.music.data

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueModelTest {
    @Test
    fun insertPlayNextMovesExistingSongAfterCurrent() {
        val queue = SongFixtures.queue(4)
        val model = PlaybackQueueModel(
            queue = queue,
            currentIndex = 1,
            order = PlaybackOrderState(
                sourceIds = queue.map { it.id },
                playbackIds = queue.map { it.id },
                currentId = queue[1].id,
            ),
        )

        val updated = model.insertPlayNext(queue[0])

        assertEquals(listOf(queue[1].id, queue[0].id, queue[2].id, queue[3].id), updated.queue.map { it.id })
        assertEquals(0, updated.currentIndex)
        assertEquals(queue[1].id, updated.currentSong?.id)
    }

    @Test
    fun moveKeepsCurrentSongStableWhenEarlierItemMovesPastIt() {
        val queue = SongFixtures.queue(4)
        val model = PlaybackQueueModel(
            queue = queue,
            currentIndex = 2,
            order = PlaybackOrderState(
                sourceIds = queue.map { it.id },
                playbackIds = queue.map { it.id },
                currentId = queue[2].id,
            ),
        )

        val updated = model.move(fromIndex = 0, toIndex = 3)

        assertEquals(listOf(queue[1].id, queue[2].id, queue[3].id, queue[0].id), updated.queue.map { it.id })
        assertEquals(1, updated.currentIndex)
        assertEquals(queue[2].id, updated.currentSong?.id)
    }

    @Test
    fun removeCurrentSelectsAdjacentReplacement() {
        val queue = SongFixtures.queue(3)
        val model = PlaybackQueueModel(
            queue = queue,
            currentIndex = 1,
            order = PlaybackOrderState(
                sourceIds = queue.map { it.id },
                playbackIds = queue.map { it.id },
                currentId = queue[1].id,
            ),
        )

        val updated = model.removeAt(1)

        assertEquals(listOf(queue[0].id, queue[2].id), updated.queue.map { it.id })
        assertEquals(1, updated.currentIndex)
        assertEquals(queue[2].id, updated.currentSong?.id)
        assertEquals(queue[2].id, updated.order.currentId)
    }

    @Test
    fun mirrorFromPlayerPreservesShuffleSourceOrder() {
        val queue = SongFixtures.queue(3)
        val mirroredQueue = listOf(queue[1], queue[2], queue[0])
        val model = PlaybackQueueModel(
            order = PlaybackOrderState(
                sourceIds = queue.map { it.id },
                playbackIds = mirroredQueue.map { it.id },
                currentId = mirroredQueue[0].id,
                shuffleEnabled = true,
            ),
        )

        val mirrored = model.mirrorFromPlayer(mirroredQueue, playerIndex = 2)

        assertEquals(queue.map { it.id }, mirrored.order.sourceIds)
        assertEquals(mirroredQueue.map { it.id }, mirrored.order.playbackIds)
        assertEquals(mirroredQueue[2].id, mirrored.order.currentId)
        assertEquals(true, mirrored.order.shuffleEnabled)
        assertEquals(2, mirrored.currentIndex)
    }
}
