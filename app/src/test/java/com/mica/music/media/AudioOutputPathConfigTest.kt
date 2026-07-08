package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPathConfigTest {

    @Test
    fun production_isSharedPcmWithIntDsdDecimation() {
        val config = AudioOutputPathConfig.PRODUCTION
        assertEquals(PlaybackOutputMode.SharedPcm, config.outputMode)
        assertEquals(DsdDecimationOutputMode.IntPcm, config.dsdDecimationMode)
        config.requireSupportedForPlayback()
    }

    @Test
    fun sharedPcm_allowsDsp_usbDirect_requiresMinimalChain() {
        assertTrue(PlaybackOutputMode.SharedPcm.allowsSharedPcmDsp)
        assertFalse(PlaybackOutputMode.SharedPcm.requiresMinimalProcessorChain)
        assertFalse(PlaybackOutputMode.UsbDirectPcm.allowsSharedPcmDsp)
        assertTrue(PlaybackOutputMode.UsbDirectPcm.requiresMinimalProcessorChain)
        assertFalse(PlaybackOutputMode.UsbNativeDsd.usesExoPcmChain)
    }

    @Test(expected = IllegalArgumentException::class)
    fun usbDirectPcm_rejectedUntilP6() {
        AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDirectPcm).requireSupportedForPlayback()
    }

    @Test(expected = IllegalArgumentException::class)
    fun dsdFloatPcm_rejectedUntilP4() {
        AudioOutputPathConfig(dsdDecimationMode = DsdDecimationOutputMode.FloatPcm)
            .requireSupportedForPlayback()
    }
}
