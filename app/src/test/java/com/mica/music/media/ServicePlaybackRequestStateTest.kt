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
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val second = state.begin(
            SongFixtures.song("second"),
            PlaybackBackendKind.SOFTWARE,
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
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val second = state.begin(
            changed,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertFalse(
            state.accepts(
                generation = first.generation,
                songId = first.songId,
                sourceRevision = first.sourceRevision,
                backend = PlaybackBackendKind.MEDIA3,
            ),
        )
        assertTrue(
            state.accepts(
                generation = second.generation,
                songId = second.songId,
                sourceRevision = second.sourceRevision,
                backend = PlaybackBackendKind.MEDIA3,
            ),
        )
    }

    @Test
    fun userPlayIntentCanBeClearedWithoutChangingRequestGeneration() {
        val state = ServicePlaybackRequestState()
        val request = state.begin(
            SongFixtures.song(),
            PlaybackBackendKind.SOFTWARE,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        val updated = state.setUserPlayIntent(request.id, false)

        assertEquals(request.generation, updated?.generation)
        assertFalse(updated?.userPlayIntent ?: true)
    }

    @Test
    fun fallbackCanOnlyBeClaimedOncePerSourceRevision() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song("alac", container = "ALAC", mime = "audio/alac")
        val first = state.begin(
            song,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertTrue(state.claimSoftwareFallback(first.id, PlaybackFailureKind.DECODER_UNSUPPORTED))

        val retry = state.begin(
            song,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        assertFalse(state.claimSoftwareFallback(retry.id, PlaybackFailureKind.DECODER_UNSUPPORTED))
        assertTrue(state.shouldUseSoftwareFallback(song))
    }

    @Test
    fun stablePlaybackResetsConsecutiveFailureCount() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song()
        val request = state.begin(
            song,
            PlaybackBackendKind.SOFTWARE,
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
            PlaybackBackendKind.SOFTWARE,
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
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        val failure = PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, "failed")

        assertEquals(1, state.markFailed(request.id, failure))
        assertEquals(null, state.markFailed(request.id, failure))
        assertEquals(1, state.consecutiveFailures)
    }

    @Test
    fun backendSelectionCoversAllSwitchCombinations() {
        val state = ServicePlaybackRequestState()
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val alac = SongFixtures.song("alac", container = "ALAC", mime = "audio/alac")
        val dsd = SongFixtures.song("dsd", container = "DSD", mime = "audio/x-dsf")

        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(flac))
        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(alac))
        assertEquals(PlaybackBackendKind.SOFTWARE, state.backendFor(dsd))

        val media3Request = state.begin(
            alac,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        assertTrue(
            state.claimSoftwareFallback(
                media3Request.id,
                PlaybackFailureKind.DECODER_UNSUPPORTED,
            ),
        )
        assertEquals(PlaybackBackendKind.SOFTWARE, state.backendFor(alac))
        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(flac))
    }

    @Test
    fun oneAlacDecoderFailureDoesNotDisableMedia3ForOtherSources() {
        val state = ServicePlaybackRequestState()
        val first = SongFixtures.song("alac-1", container = "ALAC", mime = "audio/alac")
        val second = SongFixtures.song("alac-2", container = "ALAC", mime = "audio/alac")
        val request = state.begin(
            first,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertFalse(
            state.recordAlacDecoderFailure(request.id, "c2.qti.alac.sw.decoder"),
        )
        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(second))
    }

    @Test
    fun repeatedFailureOfSameSourceDoesNotOpenAlacDecoderCircuit() {
        val state = ServicePlaybackRequestState()
        val song = SongFixtures.song("alac", container = "ALAC", mime = "audio/alac")
        val first = state.begin(
            song,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        assertFalse(
            state.recordAlacDecoderFailure(first.id, "c2.qti.alac.sw.decoder"),
        )
        val retry = state.begin(
            song,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertFalse(
            state.recordAlacDecoderFailure(retry.id, "c2.qti.alac.sw.decoder"),
        )
        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(song))
    }

    @Test
    fun twoDistinctFailuresOpenProcessLocalAlacDecoderCircuit() {
        val state = ServicePlaybackRequestState()
        val first = SongFixtures.song("alac-1", container = "ALAC", mime = "audio/alac")
        val second = SongFixtures.song("alac-2", container = "ALAC", mime = "audio/alac")
        val third = SongFixtures.song("alac-3", container = "ALAC", mime = "audio/alac")
        val flac = SongFixtures.song("flac", container = "FLAC", mime = "audio/flac")
        val firstRequest = state.begin(
            first,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )
        assertFalse(
            state.recordAlacDecoderFailure(firstRequest.id, "c2.qti.alac.sw.decoder"),
        )
        val secondRequest = state.begin(
            second,
            PlaybackBackendKind.MEDIA3,
            0L,
            true,
            AudioQualityMode.HIFI,
        )

        assertTrue(
            state.recordAlacDecoderFailure(secondRequest.id, "c2.qti.alac.sw.decoder"),
        )
        assertEquals(PlaybackBackendKind.SOFTWARE, state.backendFor(third))
        assertEquals(PlaybackBackendKind.MEDIA3, state.backendFor(flac))
    }
}
