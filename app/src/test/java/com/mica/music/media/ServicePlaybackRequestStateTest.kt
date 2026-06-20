package com.mica.music.media

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePlaybackRequestStateTest {
    @Test
    fun staleCallbacksCannotChangeCurrentRequest() {
        val state = ServicePlaybackRequestState()
        val first = state.begin(SongFixtures.song("first"), 0L)
        val second = state.begin(SongFixtures.song("second"), 0L)

        state.markPlaybackProgress(first.id, 2_000L)

        assertFalse(state.accepts(first.id))
        assertTrue(state.accepts(second.id))
        assertEquals(second, state.activeRequest)
    }

    @Test
    fun sameSongIdWithChangedSourceRevisionRejectsOldRequest() {
        val state = ServicePlaybackRequestState()
        val original = SongFixtures.song("same").copy(dateModifiedMs = 1L)
        val changed = original.copy(dateModifiedMs = 2L)
        val first = state.begin(original, 0L)
        val second = state.begin(changed, 0L)

        assertFalse(
            state.accepts(
                requestId = first.id,
                songId = first.songId,
                sourceRevision = first.sourceRevision,
            ),
        )
        assertTrue(
            state.accepts(
                requestId = second.id,
                songId = second.songId,
                sourceRevision = second.sourceRevision,
            ),
        )
    }

    @Test
    fun terminalFailureOnlyMatchesItsRequestIdentity() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song().copy(dateModifiedMs = 1L)
        val request = state.begin(song, 0L)
        val failure = PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed")

        state.markFailed(request.id, failure)

        assertEquals(failure, state.failureFor(request.songId, request.sourceRevision))
        assertNull(state.failureFor("other", request.sourceRevision))

        val changed = state.begin(song.copy(dateModifiedMs = 2L), 0L)
        assertNull(state.failureFor(changed.songId, changed.sourceRevision))
    }

    @Test
    fun stablePlaybackResetsConsecutiveFailureCount() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song()
        val request = state.begin(song, 0L)
        state.markFailed(
            request.id,
            PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed"),
        )
        val retry = state.begin(song, 0L)

        state.markPlaybackProgress(retry.id, 1_000L)

        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun sameRequestFailureIsOnlyCountedOnce() {
        val state = ServicePlaybackRequestState()
        val request = state.begin(SongFixtures.song(), 0L)
        val failure = PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed")

        assertEquals(1, state.markFailed(request.id, failure))
        assertEquals(null, state.markFailed(request.id, failure))
        assertEquals(1, state.consecutiveFailures)
    }
}
