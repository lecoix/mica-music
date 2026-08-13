package com.mica.music.media.usbprototype

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExactCarrierActivationContractTest {
    @Test
    fun armResultCodesKeepRetryableAndTerminalStatesDistinct() {
        assertEquals(0, UsbExactCarrierArmResult.RETRY_INSUFFICIENT_PREFILL)
        assertEquals(1, UsbExactCarrierArmResult.ARMED)
        assertEquals(2, UsbExactCarrierArmResult.ALREADY_ARMED)
        assertEquals(-1, UsbExactCarrierArmResult.NOT_EXACT_SESSION)
        assertEquals(-2, UsbExactCarrierArmResult.STOPPED_OR_FAILED)
    }

    @Test
    fun exactPayloadPolicyRemainsSeparateFromPcmProductionPolicy() {
        assertEquals(0, UsbNativePayloadPolicy.PCM_ZERO_FILL.nativeValue)
        assertEquals(1, UsbNativePayloadPolicy.EXACT_FRAMES_ONLY.nativeValue)
    }
}
