package com.mica.music.media

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicaRendererSupportPoliciesTest {

    @Test
    fun dsdOnly_acceptsDsdMime() {
        assertTrue(MicaRendererSupportPolicies.dsdOnlyAccepts("audio/dsd", null))
        assertTrue(MicaRendererSupportPolicies.dsdOnlyAccepts("audio/x-dsf", null))
    }

    @Test
    fun dsdOnly_rejectsPcmAndLossy() {
        assertFalse(MicaRendererSupportPolicies.dsdOnlyAccepts(MimeTypes.AUDIO_FLAC, null))
        assertFalse(MicaRendererSupportPolicies.dsdOnlyAccepts(MimeTypes.AUDIO_ALAC, null))
        assertFalse(MicaRendererSupportPolicies.dsdOnlyAccepts(MimeTypes.AUDIO_RAW, null))
        assertFalse(MicaRendererSupportPolicies.dsdOnlyAccepts(MimeTypes.AUDIO_MPEG, null))
        assertFalse(MicaRendererSupportPolicies.dsdOnlyAccepts("audio/mp4a-latm", null))
    }

    @Test
    fun pcmOnly_acceptsFlacAlacAndApe() {
        assertTrue(MicaRendererSupportPolicies.pcmOnlyAccepts(MimeTypes.AUDIO_FLAC))
        assertTrue(MicaRendererSupportPolicies.pcmOnlyAccepts(MimeTypes.AUDIO_ALAC))
        assertTrue(MicaRendererSupportPolicies.pcmOnlyAccepts("audio/ape"))
    }

    @Test
    fun pcmOnly_rejectsDsdRawAndLossy() {
        // WAV (audio/raw) is rejected so the platform renderer claims it (R1a log 34 finding).
        assertFalse(MicaRendererSupportPolicies.pcmOnlyAccepts(MimeTypes.AUDIO_RAW))
        assertFalse(MicaRendererSupportPolicies.pcmOnlyAccepts("audio/dsd"))
        assertFalse(MicaRendererSupportPolicies.pcmOnlyAccepts(MimeTypes.AUDIO_MPEG))
        assertFalse(MicaRendererSupportPolicies.pcmOnlyAccepts("audio/mp4a-latm"))
        assertFalse(MicaRendererSupportPolicies.pcmOnlyAccepts(null))
    }

    @Test
    fun policies_areMutuallyExclusive() {
        val mimes = listOf(
            "audio/dsd",
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC,
            "audio/ape",
            MimeTypes.AUDIO_RAW,
            MimeTypes.AUDIO_MPEG,
            "audio/mp4a-latm",
        )
        mimes.forEach { mime ->
            val dsd = MicaRendererSupportPolicies.dsdOnlyAccepts(mime, null)
            val pcm = MicaRendererSupportPolicies.pcmOnlyAccepts(mime)
            assertFalse("both accepted $mime", dsd && pcm)
        }
    }
}
