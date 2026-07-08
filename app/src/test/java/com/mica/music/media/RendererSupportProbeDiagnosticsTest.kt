package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSupportProbeDiagnosticsTest {

    @Test
    fun candidateForFormat_marksDsdMimeAsDsdOnly() {
        val format = Format.Builder()
            .setSampleMimeType("audio/x-dsf")
            .setSampleRate(2_822_400)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build()

        val candidate = RendererSupportProbeDiagnostics.candidateForFormat(format)

        assertTrue(candidate.dsdOnly)
        assertFalse(candidate.pcmOnly)
        assertFalse(candidate.platformDefault)
    }

    @Test
    fun candidateForFormat_marksHighRateFloatPcmAsDsdOnlyCandidate() {
        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setSampleRate(1_411_200)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build()

        val candidate = RendererSupportProbeDiagnostics.candidateForFormat(format)

        assertTrue(candidate.dsdOnly)
        assertFalse(candidate.pcmOnly)
    }

    @Test
    fun candidateForFormat_doesNotTreatHiResFlacAsDsd() {
        val format = Format.Builder()
            .setSampleMimeType("audio/flac")
            .setSampleRate(192_000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .build()

        val candidate = RendererSupportProbeDiagnostics.candidateForFormat(format)

        assertFalse(candidate.dsdOnly)
        assertTrue(candidate.pcmOnly)
        assertFalse(candidate.platformDefault)
    }

    @Test
    fun candidateForFormat_leavesMp3ForPlatformDefault() {
        val format = Format.Builder()
            .setSampleMimeType("audio/mpeg")
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build()

        val candidate = RendererSupportProbeDiagnostics.candidateForFormat(format)

        assertFalse(candidate.dsdOnly)
        assertFalse(candidate.pcmOnly)
        assertTrue(candidate.platformDefault)
    }

    @Test
    fun describeFormatSupport_handledIsAccept() {
        val outcome = RendererSupportProbeDiagnostics.describeFormatSupport(C.FORMAT_HANDLED)

        assertEquals("accept", outcome.decision)
        assertEquals("handled", outcome.label)
    }

    @Test
    fun describeFormatSupport_unsupportedSubtypeIsReject() {
        val outcome =
            RendererSupportProbeDiagnostics.describeFormatSupport(C.FORMAT_UNSUPPORTED_SUBTYPE)

        assertEquals("reject", outcome.decision)
        assertEquals("unsupported-subtype", outcome.label)
    }

    @Test
    fun describeFormatSupport_unsupportedTypeIsReject() {
        val outcome =
            RendererSupportProbeDiagnostics.describeFormatSupport(C.FORMAT_UNSUPPORTED_TYPE)

        assertEquals("reject", outcome.decision)
        assertEquals("unsupported-type", outcome.label)
    }

    @Test
    fun describeFormatSupport_exceedsCapabilitiesIsReject() {
        val outcome =
            RendererSupportProbeDiagnostics.describeFormatSupport(C.FORMAT_EXCEEDS_CAPABILITIES)

        assertEquals("reject", outcome.decision)
        assertEquals("exceeds-capabilities", outcome.label)
    }
}
