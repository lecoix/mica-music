package com.mica.music.media.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorParserTest {
    @Test
    fun parsesUac1TypeIDiscreteRatesAndEndpointRateControl() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(uac1DiscreteFixture(), UsbBusSpeed.FULL),
        )

        assertTrue(result is UsbAudioDescriptorParseResult.Parsed)
        val facts = (result as UsbAudioDescriptorParseResult.Parsed).facts
        assertEquals(UsbAudioProtocol.UAC1, facts.audioFunction.protocol)
        assertEquals(0, facts.audioFunction.controlInterfaceNumber)
        assertEquals(setOf(1), facts.audioFunction.streamingInterfaceNumbers)

        val alt = facts.streamingAlternates.single()
        assertEquals(1, alt.alternateSetting)
        assertTrue(alt.formatIsPcm)
        assertEquals(
            UsbRawStreamingFormatIdentity.Uac1(formatTag = 0x0001, formatType = 0x01),
            alt.rawFormatIdentity,
        )
        val format = checkNotNull(alt.format)
        assertEquals(2, format.channelCount)
        assertEquals(2, format.subslotBytes)
        assertEquals(16, format.bitResolution)
        assertTrue(format.sampleRates.supports(44_100))
        assertTrue(format.sampleRates.supports(48_000))
        assertFalse(format.sampleRates.supports(96_000))

        val data = alt.endpoints.first { !it.directionIn }
        assertEquals(0x01, data.address)
        assertTrue(data.samplingFrequencyControl)
        assertEquals(1, data.transferType)
        assertEquals(1, data.syncTypeCode)
        assertEquals(200, data.maxServiceIntervalBytes)
        assertEquals(0x82, data.synchAddress)
        val feedback = alt.endpoints.first { it.directionIn }
        assertEquals(1, feedback.usageTypeCode)
    }

    @Test
    fun parsesUac1ContinuousRateRangeWithoutInventingDiscreteRates() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(uac1ContinuousFixture(), UsbBusSpeed.FULL),
        ) as UsbAudioDescriptorParseResult.Parsed

        val rates = checkNotNull(result.facts.streamingAlternates.single().format).sampleRates
        assertTrue(rates.supports(44_100))
        assertTrue(rates.supports(48_000))
        assertTrue(rates.supports(96_000))
        assertFalse(rates.supports(96_001))
    }

    @Test
    fun carriesAuthoritativeDeviceRevisionWhenRawDeviceDescriptorIsPresent() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(
                concat(deviceDescriptorFixture(), uac1DiscreteFixture()),
                UsbBusSpeed.FULL,
            ),
        ) as UsbAudioDescriptorParseResult.Parsed

        assertEquals(
            UsbDeviceDescriptorFacts(
                vendorId = 0x262a,
                productId = 0x0001,
                bcdDevice = 0x0004,
            ),
            result.facts.deviceDescriptor,
        )
    }

    @Test
    fun parsesUac2ClockFactsButLeavesSampleRateUnverified() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(uac2Padded24Fixture(), UsbBusSpeed.HIGH),
        )

        assertTrue(result is UsbAudioDescriptorParseResult.Parsed)
        val facts = (result as UsbAudioDescriptorParseResult.Parsed).facts
        assertEquals(UsbAudioProtocol.UAC2, facts.audioFunction.protocol)
        assertEquals(4, facts.uac2TerminalClockLinks[2])
        assertEquals(
            UsbUac2ClockEntity.Source(id = 4, attributes = 3, controls = 3),
            facts.uac2ClockEntities[4],
        )

        val alt = facts.streamingAlternates.single()
        assertEquals(
            UsbRawStreamingFormatIdentity.Uac2(formatType = 0x01, formatsBitmap = 0x00000001L),
            alt.rawFormatIdentity,
        )
        val format = checkNotNull(alt.format)
        assertEquals(2, format.channelCount)
        assertEquals(4, format.subslotBytes)
        assertEquals(24, format.bitResolution)
        assertEquals(UsbSampleRateSupport.Unverified, format.sampleRates)
        assertEquals(300, alt.endpoints.first { !it.directionIn }.maxServiceIntervalBytes)
        assertEquals(4, alt.endpoints.first { it.directionIn }.packetPayloadBytes)
    }

    @Test
    fun preservesUac2RawDataBitmapWithoutInferringPcmOrDsdPolicy() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(uac2RawDataFixture(), UsbBusSpeed.HIGH),
        ) as UsbAudioDescriptorParseResult.Parsed

        val alt = result.facts.streamingAlternates.single()
        assertFalse(alt.formatIsPcm)
        assertEquals(
            UsbRawStreamingFormatIdentity.Uac2(
                formatType = 0x01,
                formatsBitmap = 0x80000000L,
            ),
            alt.rawFormatIdentity,
        )
    }

    @Test
    fun truncatedDescriptorFailsClosedWithTypedReason() {
        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(byteArrayOf(9, 4, 0, 0), UsbBusSpeed.FULL),
        )

        assertTrue(result is UsbAudioDescriptorParseResult.Rejected)
        result as UsbAudioDescriptorParseResult.Rejected
        assertEquals(UsbAudioRejectionCode.MALFORMED_DESCRIPTOR, result.rejection.code)
    }

    @Test
    fun duplicateUac2ClockEntityFailsClosedAsAmbiguous() {
        val duplicateClock = concat(
            byteArrayOf(9, 4, 0, 0, 0, 1, 1, 0x20, 0),
            byteArrayOf(8, 0x24, 0x0a, 4, 3, 3, 0, 0),
            byteArrayOf(8, 0x24, 0x0a, 4, 3, 3, 0, 0),
            byteArrayOf(9, 4, 1, 1, 0, 1, 2, 0x20, 0),
        )

        val result = StandardUacDescriptorParser.parse(
            UsbRawAudioDescriptorSet(duplicateClock, UsbBusSpeed.HIGH),
        )

        assertTrue(result is UsbAudioDescriptorParseResult.Rejected)
        result as UsbAudioDescriptorParseResult.Rejected
        assertEquals(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, result.rejection.code)
    }

    private fun uac1DiscreteFixture(): ByteArray = concat(
        byteArrayOf(9, 4, 0, 0, 0, 1, 1, 0, 0),
        byteArrayOf(9, 4, 1, 1, 2, 1, 2, 0, 0),
        byteArrayOf(7, 0x24, 0x01, 1, 1, 0x01, 0x00),
        byteArrayOf(
            14, 0x24, 0x02, 0x01, 2, 2, 16, 2,
            0x44, 0xac.toByte(), 0x00,
            0x80.toByte(), 0xbb.toByte(), 0x00,
        ),
        byteArrayOf(9, 5, 0x01, 0x05, 0xc8.toByte(), 0x00, 1, 0, 0x82.toByte()),
        byteArrayOf(7, 0x25, 0x01, 0x01, 0, 0, 0),
        byteArrayOf(7, 5, 0x82.toByte(), 0x11, 3, 0, 1),
    )

    private fun deviceDescriptorFixture(): ByteArray = byteArrayOf(
        18, 0x01,
        0x00, 0x02,
        0x00, 0x00, 0x00, 64,
        0x2a, 0x26,
        0x01, 0x00,
        0x04, 0x00,
        1, 2, 3, 1,
    )

    private fun uac1ContinuousFixture(): ByteArray = concat(
        byteArrayOf(9, 4, 0, 0, 0, 1, 1, 0, 0),
        byteArrayOf(9, 4, 1, 1, 1, 1, 2, 0, 0),
        byteArrayOf(7, 0x24, 0x01, 1, 1, 0x01, 0x00),
        byteArrayOf(
            14, 0x24, 0x02, 0x01, 2, 3, 24, 0,
            0x44, 0xac.toByte(), 0x00,
            0x00, 0x77, 0x01,
        ),
        byteArrayOf(7, 5, 0x01, 0x09, 0x20, 0x01, 1),
    )

    private fun uac2Padded24Fixture(): ByteArray = concat(
        byteArrayOf(9, 4, 1, 0, 0, 1, 1, 0x20, 0),
        byteArrayOf(8, 0x24, 0x0a, 4, 3, 3, 0, 0),
        byteArrayOf(
            17, 0x24, 0x02, 2, 0x01, 0x01, 0, 4,
            2, 0x03, 0x00, 0x00, 0x00, 0, 0, 0, 0,
        ),
        byteArrayOf(9, 4, 2, 1, 2, 1, 2, 0x20, 0),
        byteArrayOf(
            16, 0x24, 0x01, 2, 0, 0x01, 0x01, 0x00,
            0x00, 0x00, 2, 0x03, 0x00, 0x00, 0x00, 0,
        ),
        byteArrayOf(6, 0x24, 0x02, 0x01, 4, 24),
        byteArrayOf(9, 5, 0x03, 0x05, 0x2c, 0x01, 1, 0, 0x84.toByte()),
        byteArrayOf(8, 0x25, 0x01, 0, 0, 0, 0, 0),
        byteArrayOf(7, 5, 0x84.toByte(), 0x11, 4, 0, 4),
    )

    private fun uac2RawDataFixture(): ByteArray = concat(
        byteArrayOf(9, 4, 1, 0, 0, 1, 1, 0x20, 0),
        byteArrayOf(8, 0x24, 0x0a, 4, 3, 3, 0, 0),
        byteArrayOf(
            17, 0x24, 0x02, 2, 0x01, 0x01, 0, 4,
            2, 0x03, 0x00, 0x00, 0x00, 0, 0, 0, 0,
        ),
        byteArrayOf(9, 4, 2, 1, 1, 1, 2, 0x20, 0),
        byteArrayOf(
            16, 0x24, 0x01, 2, 0, 0x01, 0x00, 0x00,
            0x00, 0x80.toByte(), 2, 0x03, 0x00, 0x00, 0x00, 0,
        ),
        byteArrayOf(6, 0x24, 0x02, 0x01, 4, 32),
        byteArrayOf(7, 5, 0x03, 0x09, 0x90.toByte(), 0x01, 1),
    )

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
