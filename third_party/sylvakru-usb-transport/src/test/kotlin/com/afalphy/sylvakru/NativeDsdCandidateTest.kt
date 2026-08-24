package com.afalphy.sylvakru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDsdCandidateTest {
    @Test
    fun rawDataU32SubslotInfersReferenceLittleEndianFraming() {
        val candidate = UsbStreamingTargetResolver.classifyNativeCandidate(
            rawDataSubslotSizes = listOf(4),
            quirk = DacQuirk(),
        )
        assertEquals(NativeCandidate.Proven("u32le"), candidate)
    }

    @Test
    fun rawDataU16AndU8SubslotsInferReferenceFraming() {
        assertEquals(NativeCandidate.Proven("u16le"), UsbStreamingTargetResolver.classifyNativeCandidate(listOf(2), DacQuirk()))
        assertEquals(NativeCandidate.Proven("u8"), UsbStreamingTargetResolver.classifyNativeCandidate(listOf(1), DacQuirk()))
    }

    @Test
    fun ambiguousOrUnsupportedRawDataSubslotsRemainUnproven() {
        assertTrue(UsbStreamingTargetResolver.classifyNativeCandidate(listOf(2, 4), DacQuirk()) is NativeCandidate.FramingUnproven)
        assertTrue(UsbStreamingTargetResolver.classifyNativeCandidate(listOf(3), DacQuirk()) is NativeCandidate.FramingUnproven)
    }

    @Test
    fun noRawDataAndNoQuirkIsUnavailable() {
        assertTrue(UsbStreamingTargetResolver.classifyNativeCandidate(emptyList(), DacQuirk()) is NativeCandidate.Unavailable)
    }

    @Test
    fun explicitQuirkOverridesDescriptorInference() {
        val candidate = UsbStreamingTargetResolver.classifyNativeCandidate(
            rawDataSubslotSizes = listOf(4),
            quirk = DacQuirk(nativeDsdFormat = "u32be"),
        )
        assertEquals(NativeCandidate.Proven("u32be"), candidate)
    }

    @Test
    fun quirkCanEnableNativeWithoutRawDataDescriptor() {
        assertEquals(
            NativeCandidate.Proven("u32le"),
            UsbStreamingTargetResolver.classifyNativeCandidate(emptyList(), DacQuirk(nativeDsdFormat = "u32le")),
        )
    }

    @Test
    fun nativePreferenceHasNoFallbackVariant() {
        assertEquals(
            setOf("DopOnly", "NativeOnly"),
            UsbExclusiveAudioTransport.DsdPreference.entries.map { it.name }.toSet(),
        )
    }
}
