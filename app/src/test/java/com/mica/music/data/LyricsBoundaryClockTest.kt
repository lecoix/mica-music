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
        assertEquals(450L, plan.wakeInMs)
    }

    @Test
    fun positiveOffsetActivatesAndWakesLyricsEarly() {
        val plan = LyricsBoundaryClock.plan(
            lineStartTimesMs = intArrayOf(1_000, 5_000),
            positionMs = 4_500,
            playbackSpeed = 1f,
            isAdvancing = true,
            effectiveOffsetMs = 500,
        )

        assertEquals(1, plan.activeIndex)
        assertEquals(null, plan.wakeInMs)
    }
}
