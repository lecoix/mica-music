package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTimelineCoordinatorTest {
    @Test
    fun songChangeDropsPreviousPlayerDuration() {
        val timeline = PlaybackTimelineCoordinator { 0L }
        timeline.updatePlayerDuration(312_000L)

        assertEquals(312_000, timeline.uiDurationMs(songDurationSec = 218))
        assertEquals(312, timeline.resetDurationForSongChange(songDurationSec = 218))
        assertEquals(218_000, timeline.uiDurationMs(songDurationSec = 218))
    }

    @Test
    fun pendingSeekUsesMonotonicAgeAndClearsAfterConvergence() {
        var nowMs = 1_000L
        val timeline = PlaybackTimelineCoordinator { nowMs }
        timeline.armPendingSeek(30_000)
        timeline.setPositionClamped(30_000, songDurationSec = 60)

        nowMs = 1_100L
        val cleared = timeline.reconcilePendingSeek(reportedMs = 30_200)

        assertEquals("converged driftMs=200", cleared?.reason)
        assertEquals(30_000, cleared?.pendingMs)
        assertEquals(-1, timeline.pendingSeekMs)
    }

    @Test
    fun restoreAnchorSurvivesSyncUntilMatchingSongStarts() {
        val timeline = PlaybackTimelineCoordinator { 0L }
        timeline.setPendingRestore("song-a", 12_345)

        assertEquals(12_345, timeline.restorePositionForSync("song-a"))
        assertEquals(12_345, timeline.consumeRestoreStartPosition("song-a"))
        assertNull(timeline.restorePositionForSync("song-a"))
    }

    @Test
    fun staleRestoreAnchorIsClearedByDifferentSong() {
        val timeline = PlaybackTimelineCoordinator { 0L }
        timeline.setPendingRestore("song-a", 12_345)

        assertNull(timeline.restorePositionForSync("song-b"))
        assertEquals(0, timeline.consumeRestoreStartPosition("song-a"))
    }
}
