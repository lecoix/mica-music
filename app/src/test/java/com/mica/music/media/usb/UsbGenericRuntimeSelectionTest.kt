package com.mica.music.media.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbGenericRuntimeSelectionTest {
    @Test
    fun realSk02DescriptorBuildsGoldenPcm16Pcm24AndPcm32ProfilesAndTransport() {
        val runtime = sk02RuntimeFacts()
        val parsed = StandardUacDescriptorParser.parse(runtime.descriptorSet) as UsbAudioDescriptorParseResult.Parsed
        val evidence = sk02ClockEvidence()
        val formats = listOf(
            UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            UsbPcmFormat(96_000, 2, UsbPcmEncoding.PCM_24_PACKED),
            UsbPcmFormat(192_000, 2, UsbPcmEncoding.PCM_32),
        )

        formats.forEach { format ->
            val generic = UsbGenericPcmSelection.select(
                source = format,
                identity = runtime.identity,
                facts = parsed.facts,
                uac2ClockEvidence = evidence,
            ) as UsbGenericPcmSelectionResult.Ready
            val goldenDecision = Sk02UsbContract.negotiate(
                source = format,
                capability = Sk02UsbContract.capability,
                signalPolicy = UsbSignalPolicy.EXACT_ONLY,
            ) as UsbFormatDecision.Accepted
            val goldenTransport = UsbTransportConfigBuilder.build(
                goldenDecision,
                Sk02UsbContract.capability.busSpeed,
            ) as UsbTransportConfigResult.Ready

            assertProfileGeometryEquals(goldenDecision.streamingProfile, generic.decision.streamingProfile)
            assertEquals(goldenTransport.config, generic.transportConfig)
            assertEquals(0x0004, generic.capability.identity.bcdDevice)
            assertEquals(UsbBusSpeed.HIGH, generic.capability.busSpeed)
            assertEquals(1, generic.capability.clockSourceId)
        }
    }

    @Test
    fun genericExactSelectionRejectsUnsupportedRateEncodingAndAmbiguity() {
        val runtime = sk02RuntimeFacts()
        val parsed = StandardUacDescriptorParser.parse(runtime.descriptorSet) as UsbAudioDescriptorParseResult.Parsed
        val evidence = sk02ClockEvidence()

        val unsupportedRate = UsbGenericPcmSelection.select(
            UsbPcmFormat(384_001, 2, UsbPcmEncoding.PCM_16),
            runtime.identity,
            parsed.facts,
            evidence,
        ) as UsbGenericPcmSelectionResult.Rejected
        assertEquals(UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE, unsupportedRate.rejection.code)

        val unsupportedEncoding = UsbGenericPcmSelection.select(
            UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_24_IN_32),
            runtime.identity,
            parsed.facts,
            evidence,
        ) as UsbGenericPcmSelectionResult.Rejected
        assertEquals(UsbAudioRejectionCode.UNSUPPORTED_FORMAT, unsupportedEncoding.rejection.code)

        val pcm16 = parsed.facts.streamingAlternates.first { it.alternateSetting == 1 }
        val ambiguousFacts = parsed.facts.copy(
            streamingAlternates = parsed.facts.streamingAlternates + pcm16.copy(alternateSetting = 5),
        )
        val ambiguous = UsbGenericPcmSelection.select(
            UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            runtime.identity,
            ambiguousFacts,
            evidence,
        ) as UsbGenericPcmSelectionResult.Rejected
        assertEquals(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, ambiguous.rejection.code)
    }

    @Test
    fun missingUac2ClockEvidenceFailsClosedWithoutGoldenFallback() {
        val runtime = sk02RuntimeFacts()
        val parsed = StandardUacDescriptorParser.parse(runtime.descriptorSet) as UsbAudioDescriptorParseResult.Parsed

        val result = UsbGenericPcmSelection.select(
            UsbPcmFormat(48_000, 2, UsbPcmEncoding.PCM_16),
            runtime.identity,
            parsed.facts,
            Uac2RuntimeClockEvidence(),
        )

        assertTrue(result is UsbGenericPcmSelectionResult.Rejected)
        result as UsbGenericPcmSelectionResult.Rejected
        assertEquals(UsbAudioRejectionCode.CLOCK_INVALID, result.rejection.code)
    }

    @Test
    fun uac2RuntimeClockReaderUsesValidityAndRangeEvidence() {
        val runtime = sk02RuntimeFacts()
        val parsed = StandardUacDescriptorParser.parse(runtime.descriptorSet) as UsbAudioDescriptorParseResult.Parsed
        val requests = mutableListOf<UsbControlRequest>()
        val io = UsbAudioControlIo { request ->
            requests += request
            when {
                request.request == 0x01 && request.value == 0x0200 && request.readLength == 1 ->
                    UsbControlIoResult.Success(1, byteArrayOf(1))
                request.request == 0x02 && request.value == 0x0100 && request.readLength == 2 ->
                    UsbControlIoResult.Success(2, byteArrayOf(1, 0))
                request.request == 0x02 && request.value == 0x0100 && request.readLength == 14 -> {
                    val body = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(1)
                        .putInt(8_000)
                        .putInt(384_000)
                        .putInt(1)
                        .array()
                    UsbControlIoResult.Success(body.size, body)
                }
                else -> UsbControlIoResult.Failure("unexpected request=$request")
            }
        }

        val result = Uac2RuntimeClockEvidenceReader.read(parsed.facts, io)
            as Uac2RuntimeClockEvidenceReadResult.Ready

        assertEquals(setOf(1), result.evidence.validClockSourceIds)
        val support = result.evidence.sampleRatesByClockSourceId.getValue(1)
        assertTrue(support.supports(8_000))
        assertTrue(support.supports(44_100))
        assertTrue(support.supports(384_000))
        assertTrue(requests.all { it.index == 0x0101 })
    }

    @Test
    fun selectorOrMultiplierGraphFailsClosedUntilRuntimeReadbackIsProven() {
        val runtime = sk02RuntimeFacts()
        val parsed = StandardUacDescriptorParser.parse(runtime.descriptorSet) as UsbAudioDescriptorParseResult.Parsed
        val withSelector = parsed.facts.copy(
            uac2ClockEntities = parsed.facts.uac2ClockEntities +
                (2 to UsbUac2ClockEntity.Selector(id = 2, sourceIds = listOf(1), controls = 3)),
        )
        val result = Uac2RuntimeClockEvidenceReader.read(withSelector) {
            UsbControlIoResult.Failure("must not issue IO")
        }

        assertTrue(result is Uac2RuntimeClockEvidenceReadResult.Rejected)
        result as Uac2RuntimeClockEvidenceReadResult.Rejected
        assertEquals(UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH, result.rejection.code)
    }

    private fun sk02RuntimeFacts(): UsbRuntimeDescriptorFacts =
        (UsbRuntimeDescriptorFactsAssembler.assemble(
            runtimeVendorId = 0x262a,
            runtimeProductId = 0x0001,
            runtimeDeviceId = 7,
            rawDescriptors = sk02RawDescriptors(),
            busSpeed = UsbBusSpeed.HIGH,
            serialNumber = null,
        ) as UsbRuntimeFactsResult.Ready).facts

    private fun sk02ClockEvidence() = Uac2RuntimeClockEvidence(
        sampleRatesByClockSourceId = mapOf(
            1 to UsbSampleRateSupport.Ranges(
                listOf(UsbSampleRateRange(8_000, 384_000, 1)),
            ),
        ),
        validClockSourceIds = setOf(1),
    )

    private fun assertProfileGeometryEquals(
        expected: UsbAudioStreamingProfile,
        actual: UsbAudioStreamingProfile,
    ) {
        assertEquals(expected.interfaceNumber, actual.interfaceNumber)
        assertEquals(expected.alternateSetting, actual.alternateSetting)
        assertEquals(expected.endpointAddress, actual.endpointAddress)
        assertEquals(expected.feedbackEndpointAddress, actual.feedbackEndpointAddress)
        assertEquals(expected.feedbackMaxPacketBytes, actual.feedbackMaxPacketBytes)
        assertEquals(expected.feedbackInterval, actual.feedbackInterval)
        assertEquals(expected.channelCount, actual.channelCount)
        assertEquals(expected.encoding, actual.encoding)
        assertEquals(expected.subslotBytes, actual.subslotBytes)
        assertEquals(expected.bitResolution, actual.bitResolution)
        assertEquals(expected.maxPacketBytes, actual.maxPacketBytes)
        assertEquals(expected.interval, actual.interval)
        assertEquals(expected.syncMode, actual.syncMode)
        assertEquals(expected.feedbackPlan, actual.feedbackPlan)
        assertEquals(expected.clockPlan, actual.clockPlan)
        assertEquals(expected.capacityEvidence, actual.capacityEvidence)
        assertEquals(expected.claimPlan, actual.claimPlan)
    }
}
