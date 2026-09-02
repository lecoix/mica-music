package com.mica.music.playback

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

    @Test
    fun onlyTransportDiscontinuitiesAdvancePositionRevision() {
        val timeline = PlaybackTimelineCoordinator { 0L }
        timeline.setPositionClamped(10_000, songDurationSec = 60)
        timeline.setPositionClamped(10_100, songDurationSec = 60)
        assertEquals(0L, timeline.positionRevision)

        timeline.armPendingSeek(2_000)
        assertEquals(1L, timeline.positionRevision)

        timeline.markPositionDiscontinuity()
        assertEquals(2L, timeline.positionRevision)

        timeline.resetDurationForSongChange(songDurationSec = 30)
        assertEquals(3L, timeline.positionRevision)
    }

    @Test
    fun presentationClockIgnoresOrdinarySamplesAndAdvancesFromMonotonicTime() {
        var nowMs = 1_000L
        val timeline = PlaybackTimelineCoordinator { nowMs }
        timeline.samplePresentationPosition(12_207, 60, isAdvancing = true, playbackSpeed = 1f)

        nowMs = 1_050L
        timeline.samplePresentationPosition(12_206, 60, isAdvancing = true, playbackSpeed = 1f)
        assertEquals(12_257, timeline.positionMs)

        nowMs = 1_100L
        timeline.samplePresentationPosition(12_500, 60, isAdvancing = true, playbackSpeed = 1f)
        assertEquals(12_307, timeline.positionMs)
    }

    @Test
    fun explicitDiscontinuityCanStillPublishBackwardPosition() {
        val timeline = PlaybackTimelineCoordinator { 0L }
        timeline.samplePresentationPosition(12_207, 60, isAdvancing = true, playbackSpeed = 1f)

        timeline.markPositionDiscontinuity()
        timeline.setPositionClamped(2_000, songDurationSec = 60)

        assertEquals(2_000, timeline.positionMs)
        assertEquals(1L, timeline.positionRevision)
    }

    @Test
    fun presentationClockFreezesOnPauseAndResumesFromFrozenAnchor() {
        var nowMs = 1_000L
        val timeline = PlaybackTimelineCoordinator { nowMs }
        timeline.samplePresentationPosition(5_000, 60, isAdvancing = true, playbackSpeed = 1f)

        nowMs = 1_080L
        timeline.samplePresentationPosition(4_990, 60, isAdvancing = false, playbackSpeed = 1f)
        assertEquals(5_080, timeline.positionMs)
        nowMs = 2_000L
        timeline.samplePresentationPosition(5_500, 60, isAdvancing = false, playbackSpeed = 1f)
        assertEquals(5_080, timeline.positionMs)

        timeline.samplePresentationPosition(5_075, 60, isAdvancing = true, playbackSpeed = 1f)
        nowMs = 2_050L
        timeline.samplePresentationPosition(4_000, 60, isAdvancing = true, playbackSpeed = 1f)
        assertEquals(5_130, timeline.positionMs)
    }

    @Test
    fun repeatedPauseResumeDoesNotAccumulateReportedPositionLag() {
        var nowMs = 1_000L
        val timeline = PlaybackTimelineCoordinator { nowMs }
        timeline.samplePresentationPosition(10_000, 60, isAdvancing = true, playbackSpeed = 1f)

        repeat(3) {
            nowMs += 100L
            timeline.samplePresentationPosition(0, 60, isAdvancing = false, playbackSpeed = 1f)
            val frozenPositionMs = timeline.positionMs

            nowMs += 1_000L
            timeline.samplePresentationPosition(
                frozenPositionMs - 100,
                60,
                isAdvancing = true,
                playbackSpeed = 1f,
            )
        }

        nowMs += 100L
        timeline.samplePresentationPosition(0, 60, isAdvancing = true, playbackSpeed = 1f)
        assertEquals(10_400, timeline.positionMs)
    }
}
