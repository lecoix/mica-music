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

            assertTrue(decision is UsbFormatDecision.Accepted)
            decision as UsbFormatDecision.Accepted
            assertEquals(source, decision.requestedFormat)
            assertEquals(source, decision.deviceFormat)
            assertEquals(source.encoding, decision.streamingProfile.encoding)
            assertEquals(true, decision.signalExact)
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

    @Test
    fun acceptedProfileCarriesTheProvenUsbTransportShape() {
        val decision = Sk02UsbContract.negotiate(
            source = UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_32),
            capability = Sk02UsbContract.capability,
            signalPolicy = UsbSignalPolicy.EXACT_ONLY,
        ) as UsbFormatDecision.Accepted

        assertEquals(2, decision.streamingProfile.interfaceNumber)
        assertEquals(3, decision.streamingProfile.alternateSetting)
        assertEquals(0x03, decision.streamingProfile.endpointAddress)
        assertEquals(0x84, decision.streamingProfile.feedbackEndpointAddress)
        assertEquals(4, decision.streamingProfile.feedbackMaxPacketBytes)
        assertEquals(4, decision.streamingProfile.feedbackInterval)
        assertEquals(4, decision.streamingProfile.subslotBytes)
        assertEquals(32, decision.streamingProfile.bitResolution)
        assertEquals(400, decision.streamingProfile.maxPacketBytes)
        assertEquals(1, decision.streamingProfile.interval)
    }

    @Test
    fun runtimeEndpointShapeMatchesTheProvenSk02Descriptor() {
        val profile = checkNotNull(
            Sk02UsbContract.profileFor(UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_32)),
        )

        val result = Sk02UsbContract.validateRuntimeEndpoints(
            profile,
            listOf(
                UsbAudioEndpointShape(0x03, 1, 400, 1),
                UsbAudioEndpointShape(0x84, 1, 4, 4),
            ),
        )

        assertEquals(UsbStreamingProfileValidation.Valid, result)
    }

    @Test
    fun mismatchedFeedbackEndpointFailsClosed() {
        val profile = checkNotNull(
            Sk02UsbContract.profileFor(UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_32)),
        )

        val result = Sk02UsbContract.validateRuntimeEndpoints(
            profile,
            listOf(
                UsbAudioEndpointShape(0x03, 1, 400, 1),
                UsbAudioEndpointShape(0x84, 1, 4, 1),
            ),
        )

        assertTrue(result is UsbStreamingProfileValidation.Rejected)
    }
}
