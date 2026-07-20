package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBoundaryClockTest {
    @Test
    fun plansActiveLineAndNextBoundaryAtPlaybackSpeed() {
        val plan = LyricsBoundaryClock.plan(
            lineStartTimesMs = intArrayOf(0, 1_375),
            positionMs = 475,
            playbackSpeed = 2f,
            isAdvancing = true,
        )

        assertEquals(0, plan.activeIndex)
        assertEquals(375L, plan.wakeInMs)
    }
}
