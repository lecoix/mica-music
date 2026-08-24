package com.mica.music.media.usbhybrid

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExactPcmPolicyTest {
    @Test
    fun acceptsOnlyIntegerPcm16Pcm24AndPcm32() {
        assertEquals(16, UsbExactPcmPolicy.bitDepth(C.ENCODING_PCM_16BIT))
        assertEquals(24, UsbExactPcmPolicy.bitDepth(C.ENCODING_PCM_24BIT))
        assertEquals(32, UsbExactPcmPolicy.bitDepth(C.ENCODING_PCM_32BIT))
        assertTrue(UsbExactPcmPolicy.bitDepth(C.ENCODING_PCM_FLOAT) == null)
        assertTrue(UsbExactPcmPolicy.bitDepth(C.ENCODING_PCM_8BIT) == null)
    }

    @Test
    fun rejectsAnyPlaybackSpeedOtherThanOne() {
        assertTrue(UsbExactPcmPolicy.speedFailure(1f) == null)
        assertEquals("SPEED_REJECTED", UsbExactPcmPolicy.speedFailure(1.01f)?.code)
    }
}
