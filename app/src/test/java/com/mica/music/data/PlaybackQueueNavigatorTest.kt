package com.mica.music.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueNavigatorTest {

    @Test
    fun emptyAndSingleQueuesRemainValid() {
        PlaybackQueueMode.entries.forEach { mode ->
            assertEquals(
                0,
                PlaybackQueueNavigator.nextIndex(mode, 10, 0, true) { 99 },
            )
            assertEquals(
                0,
                PlaybackQueueNavigator.previousIndex(mode, -3, 1) { 99 },
            )
        }
    }

    @Test
    fun repeatOneOnlyRepeatsForAutomaticAdvance() {
        assertEquals(
            1,
            PlaybackQueueNavigator.nextIndex(
                PlaybackQueueMode.REPEAT_ONE,
                currentIndex = 1,
                queueSize = 3,
                manualSkip = false,
            ) { 2 },
        )
        assertEquals(
            2,
            PlaybackQueueNavigator.nextIndex(
                PlaybackQueueMode.REPEAT_ONE,
                currentIndex = 1,
                queueSize = 3,
                manualSkip = true,
            ) { 2 },
        )
    }

    @Test
    fun offModeStopsAtEndForAutomaticAdvanceButManualWraps() {
        assertEquals(
            2,
            PlaybackQueueNavigator.nextIndex(
                PlaybackQueueMode.OFF,
                currentIndex = 2,
                queueSize = 3,
                manualSkip = false,
            ) { 0 },
        )
        assertEquals(
            0,
            PlaybackQueueNavigator.nextIndex(
                PlaybackQueueMode.OFF,
                currentIndex = 2,
                queueSize = 3,
                manualSkip = true,
            ) { 0 },
        )
    }

    @Test
    fun randomizedOperationsNeverReturnOutOfBounds() {
        val random = Random(0x4D494341)
        repeat(10_000) {
            val size = random.nextInt(0, 100)
            val current = random.nextInt(-20, 120)
            val mode = PlaybackQueueMode.entries[random.nextInt(PlaybackQueueMode.entries.size)]
            val next = PlaybackQueueNavigator.nextIndex(
                mode,
                current,
                size,
                random.nextBoolean(),
            ) { excluded ->
                if (size <= 1) 0 else (excluded + 1) % size
            }
            val previous = PlaybackQueueNavigator.previousIndex(mode, current, size) { excluded ->
                if (size <= 1) 0 else (excluded + 1) % size
            }
            if (size == 0) {
                assertEquals(0, next)
                assertEquals(0, previous)
            } else {
                assertTrue(next in 0 until size)
                assertTrue(previous in 0 until size)
            }
        }
    }
}
