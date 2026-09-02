package com.mica.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSeekClearTest {

    @Test
    fun convergesWhenReportedPositionMatchesPending() {
        assertEquals(
            "converged driftMs=200",
            evaluatePendingSeekClear(pendingMs = 60_000, reportedMs = 60_200, pendingAgeMs = 100L),
        )
    }

    @Test
    fun bailsOutWhenExoStaysAheadAfterBackwardSeek() {
        assertEquals(
            "ahead-drift ageMs=600 pendingMs=62000 reportedMs=136000",
            evaluatePendingSeekClear(pendingMs = 62_000, reportedMs = 136_000, pendingAgeMs = 600L),
        )
    }

    @Test
    fun timesOutWhenPendingNeverConverges() {
        assertEquals(
            "timeout ageMs=5000 driftMs=74000",
            evaluatePendingSeekClear(pendingMs = 62_000, reportedMs = 136_000, pendingAgeMs = 5_000L),
        )
    }

    @Test
    fun keepsPendingWhileWaitingForSeekToLand() {
        assertNull(
            evaluatePendingSeekClear(pendingMs = 62_000, reportedMs = 136_000, pendingAgeMs = 200L),
        )
    }
}
