package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationLyricsBoundaryPlannerTest {
    @Test
    fun schedulesNextLyricBoundaryUsingPlaybackSpeed() {
        val plan = NotificationLyricsBoundaryPlanner.plan(
            lineStartTimesMs = intArrayOf(0, 1_375),
            positionMs = 475,
            playbackSpeed = 2f,
            isAdvancing = true,
            publishedIndex = 0,
            nowRealtimeMs = 10_000,
            lastPublishedRealtimeMs = 9_000,
        )

        assertEquals(null, plan.publishIndex)
        assertEquals(450L, plan.wakeInMs)
    }

    @Test
    fun coalescesDenseLinesUntilPublishIntervalEnds() {
        val plan = NotificationLyricsBoundaryPlanner.plan(
            lineStartTimesMs = intArrayOf(0, 100, 200),
            positionMs = 300,
            playbackSpeed = 1f,
            isAdvancing = true,
            publishedIndex = 0,
            nowRealtimeMs = 10_100,
            lastPublishedRealtimeMs = 10_000,
        )

        assertEquals(null, plan.publishIndex)
        assertEquals(150L, plan.wakeInMs)
    }

    @Test
    fun publishesLatestDenseLineWhenCooldownEnds() {
        val plan = NotificationLyricsBoundaryPlanner.plan(
            lineStartTimesMs = intArrayOf(0, 100, 200),
            positionMs = 450,
            playbackSpeed = 1f,
            isAdvancing = true,
            publishedIndex = 0,
            nowRealtimeMs = 10_250,
            lastPublishedRealtimeMs = 10_000,
        )

        assertEquals(2, plan.publishIndex)
        assertEquals(null, plan.wakeInMs)
    }

    @Test
    fun pausedSeekPublishesCurrentLineWithoutSchedulingPositionWake() {
        val plan = NotificationLyricsBoundaryPlanner.plan(
            lineStartTimesMs = intArrayOf(0, 2_000, 4_000),
            positionMs = 2_500,
            playbackSpeed = 1f,
            isAdvancing = false,
            publishedIndex = 0,
            nowRealtimeMs = 20_000,
            lastPublishedRealtimeMs = 19_000,
        )

        assertEquals(1, plan.publishIndex)
        assertEquals(null, plan.wakeInMs)
    }

    @Test
    fun skipsIntermediateBoundaryWakeupsDuringPublishCooldown() {
        val plan = NotificationLyricsBoundaryPlanner.plan(
            lineStartTimesMs = intArrayOf(0, 100, 200),
            positionMs = 0,
            playbackSpeed = 1f,
            isAdvancing = true,
            publishedIndex = 0,
            nowRealtimeMs = 10_100,
            lastPublishedRealtimeMs = 10_000,
        )

        assertEquals(null, plan.publishIndex)
        assertEquals(150L, plan.wakeInMs)
    }
}
