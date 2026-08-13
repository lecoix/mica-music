package com.mica.music.media.usbprototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UsbNativePayloadPolicyTest {
    @Test
    fun nativePolicyValuesAreStableAndDistinctFromTransportFacts() {
        assertEquals(0, UsbNativePayloadPolicy.PCM_ZERO_FILL.nativeValue)
        assertEquals(1, UsbNativePayloadPolicy.EXACT_FRAMES_ONLY.nativeValue)
        assertNotEquals(
            UsbNativePayloadPolicy.PCM_ZERO_FILL.nativeValue,
            UsbNativePayloadPolicy.EXACT_FRAMES_ONLY.nativeValue,
        )
        assertEquals(2, UsbNativePayloadPolicy.entries.size)
    }
}
