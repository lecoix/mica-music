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
    fun commonFormatsAreSupportedByExo() {
        val commonFormats = listOf(
            SongFixtures.song("mp3", container = "MP3", mime = "audio/mpeg"),
            SongFixtures.song("aac", container = "AAC", mime = "audio/mp4"),
            SongFixtures.song("flac", container = "FLAC", mime = "audio/flac"),
            SongFixtures.song("wav", container = "WAV", mime = "audio/wav"),
            SongFixtures.song("ogg", container = "OGG", mime = "audio/ogg"),
            SongFixtures.song("opus", container = "OPUS", mime = "audio/opus"),
        )
        commonFormats.forEach { song ->
            val route = PlaybackRouter.decide(song)
            assertTrue(route is PlaybackRouteDecision.Supported)
        }
    }

    @Test
    fun dsfIsSupportedAndDffIsRejected() {
        val dsf = SongFixtures.song("dsf", container = "DSD", mime = "audio/x-dsf")
            .copy(fileName = "track.dsf", filePath = "Music/track.dsf")
        val dff = SongFixtures.song("dff", container = "DSD", mime = "audio/x-dsdiff")
            .copy(fileName = "track.dff", filePath = "Music/track.dff")

        assertTrue(PlaybackRouter.decide(dsf) is PlaybackRouteDecision.Supported)
        val dffRoute = PlaybackRouter.decide(dff)
        assertTrue(dffRoute is PlaybackRouteDecision.Unsupported)
        assertEquals(
            "不支持 DFF/DSDIFF 格式，请使用 DSF",
            PlaybackRouter.unsupportedMessage(dff),
        )
        assertFalse(PlaybackRouter.isPlayable(dff))
        assertTrue(PlaybackRouter.isPlayable(dsf))
    }

    @Test
    fun alacIsSupportedViaFfmpeg() {
        val alac = SongFixtures.song(container = "ALAC", mime = "audio/mp4")
        val route = PlaybackRouter.decide(alac)
        assertTrue(route is PlaybackRouteDecision.Supported)
        assertEquals("alac-ffmpeg", (route as PlaybackRouteDecision.Supported).reason)
    }

    @Test
    fun requestIdsIncreaseAndRevisionTracksSourceChanges() {
        val sequencer = PlaybackRequestSequencer()
        val original = SongFixtures.song(id = "song").copy(dateModifiedMs = 1)
        val changed = original.copy(dateModifiedMs = 2)

        val first = sequencer.next(original, 0)
        val second = sequencer.next(changed, 0)

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
}
