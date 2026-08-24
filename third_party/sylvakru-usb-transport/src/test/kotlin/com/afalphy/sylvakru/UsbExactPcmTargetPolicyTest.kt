package com.afalphy.sylvakru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExactPcmTargetPolicyTest {
    @Test fun acceptsExactAndLosslessWidening() {
        assertTrue(UsbExactPcmTargetPolicy.accepts(16, 2, 16))
        assertTrue(UsbExactPcmTargetPolicy.accepts(16, 3, 24))
        assertTrue(UsbExactPcmTargetPolicy.accepts(16, 4, 24))
        assertTrue(UsbExactPcmTargetPolicy.accepts(16, 4, 32))
        assertTrue(UsbExactPcmTargetPolicy.accepts(24, 3, 24))
        assertTrue(UsbExactPcmTargetPolicy.accepts(24, 4, 24))
        assertTrue(UsbExactPcmTargetPolicy.accepts(24, 4, 32))
        assertTrue(UsbExactPcmTargetPolicy.accepts(32, 4, 32))
    }

    @Test fun rejectsPrecisionLossAndUnsupportedIntegerWidths() {
        assertFalse(UsbExactPcmTargetPolicy.accepts(32, 3, 24))
        assertFalse(UsbExactPcmTargetPolicy.accepts(24, 2, 16))
        assertFalse(UsbExactPcmTargetPolicy.accepts(8, 2, 16))
        assertFalse(UsbExactPcmTargetPolicy.accepts(20, 3, 24))
        assertFalse(UsbExactPcmTargetPolicy.accepts(24, 2, 24))
    }
}
