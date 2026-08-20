package com.afalphy.sylvakru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExactPcmTargetPolicyTest {
    @Test
    fun acceptsOnlyExactPcm16AndPcm32Slots() {
        assertTrue(UsbExactPcmTargetPolicy.accepts(16, 2, 16))
        assertTrue(UsbExactPcmTargetPolicy.accepts(32, 4, 32))
        assertFalse(UsbExactPcmTargetPolicy.accepts(16, 4, 32))
        assertFalse(UsbExactPcmTargetPolicy.accepts(32, 3, 24))
        assertFalse(UsbExactPcmTargetPolicy.accepts(24, 3, 24))
    }
}
