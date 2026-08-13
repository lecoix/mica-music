package com.mica.music.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLyricsBoundarySyncTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun synchronizesPositionOnceAtNextLyricBoundary() = runTest {
        var syncCount = 0
        val job = launch {
            awaitNextHomeLyricBoundary(
                lineStartTimesMs = intArrayOf(0, 1_375),
                positionMs = 475,
                playbackSpeed = 2f,
                isAdvancing = true,
                syncPosition = { syncCount += 1 },
            )
        }

        advanceTimeBy(449)
        runCurrent()
        assertEquals(0, syncCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, syncCount)
        job.join()
    }
}
