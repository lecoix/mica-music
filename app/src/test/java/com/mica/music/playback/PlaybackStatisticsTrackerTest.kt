package com.mica.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatisticsTrackerTest {
    @Test
    fun explicitPlaybackWaitsForNewPlayerEvidence() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-a")

        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))

        tracker.onTransition("song-a", PlaybackMediaTransition.Explicit)
        assertTrue(tracker.finishEventBatch())
        assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun explicitSeekDiscontinuityConfirmsSameSongReplay() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-a")

        tracker.onPositionDiscontinuity(
            PlaybackPositionDiscontinuity(
                oldSongId = "song-a",
                newSongId = "song-a",
                oldPositionMs = 20_000L,
                newPositionMs = 0L,
                automatic = false,
            ),
        )

        assertTrue(tracker.finishEventBatch())
        assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun repeatTransitionAloneDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun automaticBoundaryAloneDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onPositionDiscontinuity(repeatBoundary())

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun confirmedRepeatBoundaryCountsOnce() {
        val tracker = tracker()
        tracker.reset("song-a")

        assertTrue(tracker.onConfirmedAutomaticBoundary(repeatBoundary()))
        assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun controllerRepeatPairDoesNotDuplicateConfirmedBoundary() {
        val tracker = tracker()
        tracker.reset("song-a")

        assertTrue(tracker.onConfirmedAutomaticBoundary(repeatBoundary()))
        assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
        tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)
        tracker.onPositionDiscontinuity(repeatBoundary())

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun duplicateControllerRepeatCallbacksDoNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")
        val boundary = repeatBoundary()

        repeat(2) {
            tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)
            tracker.onPositionDiscontinuity(boundary)
        }

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun consecutiveRepeatGenerationsEachCountOnce() {
        val tracker = tracker()
        tracker.reset("song-a")

        repeat(3) {
            assertTrue(tracker.onConfirmedAutomaticBoundary(repeatBoundary()))
            assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
            assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))

            tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)
            assertFalse(tracker.finishEventBatch())
            assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
        }
    }

    @Test
    fun incompleteRepeatEvidenceDoesNotLeakAcrossEventBatches() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)
        assertFalse(tracker.finishEventBatch())

        tracker.onPositionDiscontinuity(repeatBoundary())
        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))

        assertTrue(tracker.onConfirmedAutomaticBoundary(repeatBoundary()))
        assertEquals("song-a", tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun metadataReplacementReportedAsRepeatDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun seekFromEndToStartDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onPositionDiscontinuity(
            PlaybackPositionDiscontinuity(
                oldSongId = "song-a",
                newSongId = "song-a",
                oldPositionMs = 59_900L,
                newPositionMs = 0L,
                automatic = false,
            ),
        )

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun automaticSameSongDiscontinuityWithoutPositionWrapDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onTransition("song-a", PlaybackMediaTransition.Repeat)
        tracker.onPositionDiscontinuity(
            PlaybackPositionDiscontinuity(
                oldSongId = "song-a",
                newSongId = "song-a",
                oldPositionMs = 0L,
                newPositionMs = 1_000L,
                automatic = true,
            ),
        )

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun pauseResumeDoesNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.observePlayback("song-a", playing = false)
        tracker.observePlayback("song-a", playing = true)

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
    }

    @Test
    fun automaticNextSongBoundaryCountsOnce() {
        val tracker = tracker()
        tracker.reset("song-a")

        assertTrue(tracker.onConfirmedAutomaticBoundary(automaticNextBoundary()))
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun automaticNextCountsOnceWhenTransitionPrecedesBoundaryAndCallbacksRepeat() {
        val tracker = tracker()
        tracker.reset("song-a")
        val boundary = automaticNextBoundary()

        assertTrue(tracker.onConfirmedAutomaticBoundary(boundary))
        repeat(2) {
            tracker.onTransition("song-b", PlaybackMediaTransition.Automatic)
            tracker.onPositionDiscontinuity(boundary)
        }

        assertFalse(tracker.finishEventBatch())
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun mismatchedAutomaticTransitionAndBoundaryDoNotCount() {
        val tracker = tracker()
        tracker.reset("song-a")

        tracker.onTransition("song-b", PlaybackMediaTransition.Automatic)
        tracker.onPositionDiscontinuity(automaticNextBoundary(newSongId = "song-c"))

        assertFalse(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-c", playing = true))
    }

    @Test
    fun explicitRequestSurvivesUnrelatedBatchAndWaitsForMatchingPlayback() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-b")

        tracker.onTransition("song-a", PlaybackMediaTransition.Explicit)
        assertFalse(tracker.finishEventBatch())

        tracker.onTransition("song-b", PlaybackMediaTransition.Explicit)
        assertTrue(tracker.finishEventBatch())
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = false))
        assertNull(tracker.publishPlayStartedIfReady("song-a", playing = true))
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun confirmedRequestedPlaybackPublishesOnceWithoutTransitionEvidence() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-b")

        assertFalse(tracker.confirmRequestedPlayback("song-c"))
        assertTrue(tracker.confirmRequestedPlayback("song-b"))
        assertFalse(tracker.confirmRequestedPlayback("song-b"))
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun explicitTransitionConsumesRequestBeforePhysicalSelectionConfirmation() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-b")
        tracker.onTransition("song-b", PlaybackMediaTransition.Explicit)

        assertTrue(tracker.finishEventBatch())
        assertFalse(tracker.confirmRequestedPlayback("song-b"))
        assertEquals("song-b", tracker.publishPlayStartedIfReady("song-b", playing = true))
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
    }

    @Test
    fun newerExplicitRequestReplacesUnpublishedSession() {
        val tracker = tracker()
        tracker.reset("song-a")
        tracker.requestPlayback("song-b")
        tracker.onTransition("song-b", PlaybackMediaTransition.Explicit)
        assertTrue(tracker.finishEventBatch())

        tracker.requestPlayback("song-c")
        assertNull(tracker.publishPlayStartedIfReady("song-b", playing = true))
        tracker.onTransition("song-c", PlaybackMediaTransition.Explicit)
        assertTrue(tracker.finishEventBatch())
        assertEquals("song-c", tracker.publishPlayStartedIfReady("song-c", playing = true))
    }

    @Test
    fun listeningPublishesWholeSecondsWithoutRestartingSameSession() {
        var nowMs = 1_000L
        val listened = mutableListOf<Pair<String, Long>>()
        val tracker = tracker(
            nowMs = { nowMs },
            onListenSecondsAdded = { songId, seconds -> listened += songId to seconds },
        )

        tracker.observePlayback("song-a", playing = true)
        nowMs += 5_000L
        tracker.observePlayback("song-a", playing = true)
        nowMs += 60_400L
        tracker.observePlayback("song-b", playing = true)
        nowMs -= 1_000L
        tracker.observePlayback("song-b", playing = false)

        assertEquals(listOf("song-a" to 65L), listened)
    }

    private fun repeatBoundary() = PlaybackPositionDiscontinuity(
        oldSongId = "song-a",
        newSongId = "song-a",
        oldPositionMs = 59_900L,
        newPositionMs = 0L,
        automatic = true,
    )

    private fun automaticNextBoundary(
        newSongId: String = "song-b",
    ) = PlaybackPositionDiscontinuity(
        oldSongId = "song-a",
        newSongId = newSongId,
        oldPositionMs = 59_900L,
        newPositionMs = 0L,
        automatic = true,
    )

    private fun tracker(
        nowMs: () -> Long = { 0L },
        onListenSecondsAdded: (String, Long) -> Unit = { _, _ -> },
    ) = PlaybackStatisticsTracker(
        monotonicNowMs = nowMs,
        onListenSecondsAdded = onListenSecondsAdded,
    )
}
