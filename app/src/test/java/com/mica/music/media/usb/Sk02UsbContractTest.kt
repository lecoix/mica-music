package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sk02UsbContractTest {
    @Test
    fun exactPcm16AndPcm32AreAcceptedWithoutSignalChange() {
        listOf(UsbPcmEncoding.PCM_16, UsbPcmEncoding.PCM_32).forEach { encoding ->
            val source = UsbPcmFormat(
                sampleRateHz = if (encoding == UsbPcmEncoding.PCM_16) 48_000 else 96_000,
                channelCount = 2,
                encoding = encoding,
            )
            val decision = Sk02UsbContract.negotiate(
                source = source,
                capability = Sk02UsbContract.capability,
                signalPolicy = UsbSignalPolicy.EXACT_ONLY,
            )

            assertEquals(
                UsbFormatDecision.Accepted(source, source, signalExact = true),
                decision,
            )
        }
    }

    @Test
    fun unsupportedTopologyFailsClosedInsteadOfFallingBackToPcm16() {
        val decision = Sk02UsbContract.negotiate(
            source = UsbPcmFormat(
                sampleRateHz = 768_000,
                channelCount = 6,
                encoding = UsbPcmEncoding.PCM_32,
            ),
            capability = Sk02UsbContract.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        )

        assertTrue(decision is UsbFormatDecision.Rejected)
    }
}
