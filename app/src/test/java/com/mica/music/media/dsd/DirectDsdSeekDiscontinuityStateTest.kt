package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDsdSeekDiscontinuityStateTest {
    private val session = DirectDsdSessionGeneration(rendererGeneration = 7L, sessionGeneration = 3L)

    @Test
    fun matchingPlayingSeekSuppressesOneStopThenConsumesExactTargetReset() {
        var nowMs = 10L
        val state = DirectDsdSeekDiscontinuityState(nowMs = { nowMs })
        state.activateSession(session)

        val published = requireNotNull(state.publishPlayingSeek(45_000_000L))
        val stopped = state.observeStopped(session)
        val reset = state.consumePositionReset(
            session = session,
            sourcePositionUs = 45_000_000L,
            isPlaying = true,
        )

        assertEquals(published.requestId, stopped?.requestId)
        assertEquals(DirectDsdSeekResetMatch.MATCHED, reset.match)
        assertEquals(published.requestId, reset.requestId)
        assertNull(state.pendingForTest())
        assertNull(state.observeStopped(session))
    }

    @Test
    fun mismatchedResetConsumesIntentSoLaterPauseCannotBeSuppressed() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        state.publishPlayingSeek(45_000_000L)
        assertNotNull(state.observeStopped(session))

        val reset = state.consumePositionReset(
            session = session,
            sourcePositionUs = 46_000_000L,
            isPlaying = true,
        )

        assertEquals(DirectDsdSeekResetMatch.MISMATCHED, reset.match)
        assertNull(state.pendingForTest())
        assertNull(state.observeStopped(session))
    }

    @Test
    fun resetWithoutSeekStopFailsClosedAndClearsRequest() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        state.publishPlayingSeek(1_000_000L)

        val reset = state.consumePositionReset(
            session = session,
            sourcePositionUs = 1_000_000L,
            isPlaying = true,
        )

        assertEquals(DirectDsdSeekResetMatch.MISMATCHED, reset.match)
        assertNull(state.pendingForTest())
        assertNull(state.observeStopped(session))
    }

    @Test
    fun playbackPauseCancelsPendingIntentBeforeRendererStop() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        state.publishPlayingSeek(2_000_000L)

        state.cancelForPlaybackPause()

        assertNull(state.pendingForTest())
        assertNull(state.observeStopped(session))
    }

    @Test
    fun staleIntentCannotSuppressLaterStop() {
        var nowMs = 10L
        val state = DirectDsdSeekDiscontinuityState(nowMs = { nowMs }, maxPendingAgeMs = 100L)
        state.activateSession(session)
        state.publishPlayingSeek(3_000_000L)
        nowMs = 111L

        assertNull(state.observeStopped(session))
        assertNull(state.pendingForTest())
    }

    @Test
    fun duplicateStopConsumesSuppressionAndFailsClosed() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        state.publishPlayingSeek(4_000_000L)

        assertNotNull(state.observeStopped(session))
        assertNull(state.observeStopped(session))
        assertNull(state.pendingForTest())
    }

    @Test
    fun newerSeekReplacesOlderRequestAndOnlyNewTargetCanMatch() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        val first = requireNotNull(state.publishPlayingSeek(5_000_000L))
        val second = requireNotNull(state.publishPlayingSeek(6_000_000L))

        assertEquals(second.requestId, state.observeStopped(session)?.requestId)
        val reset = state.consumePositionReset(session, 6_000_000L, isPlaying = true)

        assertEquals(DirectDsdSeekResetMatch.MATCHED, reset.match)
        assertEquals(second.requestId, reset.requestId)
        assert(first.requestId < second.requestId)
    }

    @Test
    fun differentSessionGenerationCannotObserveOrConsumeRequest() {
        val state = DirectDsdSeekDiscontinuityState(nowMs = { 10L })
        state.activateSession(session)
        state.publishPlayingSeek(7_000_000L)
        val other = session.copy(sessionGeneration = 4L)

        assertNull(state.observeStopped(other))
        assertEquals(
            DirectDsdSeekResetMatch.NONE,
            state.consumePositionReset(other, 7_000_000L, isPlaying = true).match,
        )
        assertNotNull(state.pendingForTest())

        state.deactivateSession(session)
        assertNull(state.pendingForTest())
    }
}
