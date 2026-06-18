package com.mica.music.media

import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePlaybackRequestStateTest {
    @Test
    fun staleCallbacksCannotChangeCurrentRequest() {
        val state = ServicePlaybackRequestState()
        val first = state.begin(
            SongFixtures.song("first"),
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val second = state.begin(
            SongFixtures.song("second"),
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        state.markPlaying(first.id, 2_000L)

        assertFalse(state.accepts(first.id))
        assertTrue(state.accepts(second.id))
        assertEquals(PlaybackEngineState.Preparing(second), state.engineState)
    }

    @Test
    fun sameSongIdWithChangedSourceRevisionRejectsOldRequest() {
        val state = ServicePlaybackRequestState()
        val original = SongFixtures.song("same").copy(dateModifiedMs = 1L)
        val changed = original.copy(dateModifiedMs = 2L)
        val first = state.begin(
            original,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val second = state.begin(
            changed,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertFalse(
            state.accepts(
                generation = first.generation,
                songId = first.songId,
                sourceRevision = first.sourceRevision,
            ),
        )
        assertTrue(
            state.accepts(
                generation = second.generation,
                songId = second.songId,
                sourceRevision = second.sourceRevision,
            ),
        )
    }

    @Test
    fun userPlayIntentCanBeClearedWithoutChangingRequestGeneration() {
        val state = ServicePlaybackRequestState()
        val request = state.begin(
            SongFixtures.song(),
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        val updated = state.setUserPlayIntent(request.id, false)

        assertEquals(request.generation, updated?.generation)
        assertFalse(updated?.userPlayIntent ?: true)
    }

    @Test
    fun stablePlaybackResetsConsecutiveFailureCount() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song()
        val request = state.begin(
            song,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        state.markFailed(
            request.id,
            PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed"),
        )
        val retry = state.begin(
            song,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        state.markPlaying(retry.id, 1_000L)

        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun sameRequestFailureIsOnlyCountedOnce() {
        val state = ServicePlaybackRequestState()
        val request = state.begin(
            SongFixtures.song(),
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val failure = PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed")

        assertEquals(1, state.markFailed(request.id, failure))
        assertEquals(null, state.markFailed(request.id, failure))
        assertEquals(1, state.consecutiveFailures)
    }
}
