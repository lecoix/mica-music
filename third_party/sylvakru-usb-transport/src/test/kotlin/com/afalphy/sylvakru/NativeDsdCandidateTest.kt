package com.afalphy.sylvakru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDsdCandidateTest {
    @Test
    fun rawDataWithoutBuiltInFramingProofRemainsUnproven() {
        val candidate = UsbStreamingTargetResolver.classifyNativeCandidate(
            hasRawData = true,
            quirk = DacQuirk(),
        )

        assertTrue(candidate is NativeCandidate.FramingUnproven)
    }

    @Test
    fun exactBuiltInFormatCanProduceExplicitCandidate() {
        val candidate = UsbStreamingTargetResolver.classifyNativeCandidate(
            hasRawData = true,
            quirk = DacQuirk(nativeDsdFormat = "u32le"),
        )

        assertEquals(NativeCandidate.Proven("u32le"), candidate)
    }

    @Test
    fun nativePreferenceHasNoFallbackVariant() {
        assertEquals(
            setOf("DopOnly", "NativeOnly"),
            UsbExclusiveAudioTransport.DsdPreference.entries.map { it.name }.toSet(),
        )
    }
}
