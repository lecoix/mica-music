package com.mica.music.media.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioCapabilityModelTest {
    @Test
    fun fixedRateMatchesOnlyItsSingleRate() {
        val support = UsbSampleRateSupport.Fixed(48_000)

        assertTrue(support.supports(48_000))
        assertFalse(support.supports(44_100))
    }

    @Test
    fun discreteRatesDoNotInventIntermediateRates() {
        val support = UsbSampleRateSupport.Discrete(setOf(44_100, 48_000, 96_000))

        assertTrue(support.supports(44_100))
        assertTrue(support.supports(96_000))
        assertFalse(support.supports(88_200))
    }

    @Test
    fun rangedRatesRespectResolution() {
        val support = UsbSampleRateSupport.Ranges(
            listOf(UsbSampleRateRange(minHz = 44_100, maxHz = 192_000, resolutionHz = 100)),
        )

        assertTrue(support.supports(44_100))
        assertTrue(support.supports(48_000))
        assertFalse(support.supports(44_150))
        assertFalse(support.supports(192_100))
    }

    @Test
    fun pcm24In32RemainsDistinctFromPacked24() {
        val packed = UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_24_PACKED)
        val in32 = UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_24_IN_32)

        assertFalse(packed == in32)
    }
}
