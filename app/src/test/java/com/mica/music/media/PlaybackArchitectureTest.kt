package com.mica.music.media

import androidx.media3.common.PlaybackException
import com.mica.music.testutil.SongFixtures
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArchitectureTest {

    @Test
    fun commonFormatsUseMedia3AndDsdUsesSoftware() {
        val flac = SongFixtures.song(container = "FLAC", mime = "audio/flac")
        val dsd = SongFixtures.song(container = "DSD", mime = "audio/x-dsf")

        assertEquals(PlaybackBackendKind.MEDIA3, PlaybackRouter.decide(flac).primary)
        assertEquals(PlaybackBackendKind.SOFTWARE, PlaybackRouter.decide(dsd).primary)
    }

    @Test
    fun alacUsesMedia3WithSoftwareFallback() {
        val alac = SongFixtures.song(container = "ALAC", mime = "audio/mp4")
        val route = PlaybackRouter.decide(alac)

        assertEquals(PlaybackBackendKind.MEDIA3, route.primary)
        assertEquals(PlaybackBackendKind.SOFTWARE, route.fallback)
    }

    @Test
    fun fallbackLedgerAllowsOneAttemptPerSourceRevision() {
        val ledger = PlaybackFallbackLedger()
        assertTrue(ledger.claimSoftwareFallback("revision-1"))
        assertFalse(ledger.claimSoftwareFallback("revision-1"))
        assertTrue(ledger.claimSoftwareFallback("revision-2"))
    }

    @Test
    fun requestIdsIncreaseAndRevisionTracksSourceChanges() {
        val sequencer = PlaybackRequestSequencer()
        val original = SongFixtures.song(id = "song").copy(dateModifiedMs = 1)
        val changed = original.copy(dateModifiedMs = 2)

        val first = sequencer.next(
            original,
            PlaybackBackendKind.MEDIA3,
            0,
            true,
            AudioQualityMode.HIFI,
        )
        val second = sequencer.next(
            changed,
            PlaybackBackendKind.MEDIA3,
            0,
            true,
            AudioQualityMode.HIFI,
        )

        assertTrue(second.id > first.id)
        assertNotEquals(first.sourceRevision, second.sourceRevision)
    }

    @Test
    fun nestedSecurityExceptionIsClassifiedAsSourcePermission() {
        val error = PlaybackException(
            "Source error",
            IllegalStateException("loader failed", SecurityException("permission denied")),
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )

        assertEquals(
            PlaybackFailureKind.SOURCE_PERMISSION,
            PlaybackFailureClassifier.classify(error),
        )
        assertFalse(
            PlaybackFailureClassifier.allowsAutomaticSkip(
                PlaybackFailureClassifier.classify(error),
            ),
        )
    }

    @Test
    fun nestedFileNotFoundExceptionIsClassifiedAsMissingSource() {
        val error = PlaybackException(
            "Source error",
            IllegalStateException("loader failed", FileNotFoundException("missing")),
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )

        assertEquals(
            PlaybackFailureKind.SOURCE_MISSING,
            PlaybackFailureClassifier.classify(error),
        )
    }

    @Test
    fun stableAlacDecoderIdentityRequiresNamedAlacDecoder() {
        val alacError = PlaybackException(
            "Renderer failed",
            IllegalStateException("Decoder failed: c2.qti.alac.sw.decoder"),
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        val genericError = PlaybackException(
            "Renderer failed",
            IllegalStateException("Malformed sample"),
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertEquals(
            "c2.qti.alac.sw.decoder",
            PlaybackFailureClassifier.stableAlacDecoderIdentity(alacError),
        )
        assertEquals(
            null,
            PlaybackFailureClassifier.stableAlacDecoderIdentity(genericError),
        )
    }
}
