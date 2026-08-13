package com.mica.music.media.usb

import com.mica.music.media.dsd.DsdCarrierRejectionCode
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.ProvenDoPPackingEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDoPCarrierBridgeTest {
    @Test
    fun dsd64Packed24MapsP3FactsIntoExpectedP5Plan() {
        val fixture = fixture(rateHz = 176_400, maxBytesPerServiceInterval = 300)

        val bridged = plan(
            fixture,
            DsdCarrierSourceFacts(dsdBitRateHz = 2_822_400L, channelCount = 2),
        )
        val ready = bridged.result as DoPCarrierPlanningResult.Ready

        assertEquals(176_400L, bridged.pcmFacts.runtimeFrameRateHz)
        assertEquals(2, bridged.pcmFacts.channelCount)
        assertEquals(3, bridged.pcmFacts.subslotBytesPerChannel)
        assertEquals(24, bridged.pcmFacts.bitResolution)
        assertEquals(6, bridged.pcmFacts.bytesPerRuntimeFrame)
        assertEquals(1L, bridged.pcmFacts.servicePeriodNumeratorSeconds)
        assertEquals(8_000L, bridged.pcmFacts.servicePeriodDenominatorSeconds)
        assertEquals(176_400L, ready.plan.runtimeFrameRateHz)
        assertEquals(DoPCarrierPacking.PACKED_24_LE, ready.plan.packing)
        assertEquals(23L, ready.plan.maxRuntimeFramesPerServiceInterval)
        assertEquals(138L, ready.plan.requiredMaxBytesPerServiceInterval)
    }

    @Test
    fun dsd128ThroughDsd512RatesCrossBridgeWhenP3FactsAreExact() {
        val cases = listOf(
            5_644_800L to 352_800,
            11_289_600L to 705_600,
            22_579_200L to 1_411_200,
        )

        for ((dsdRate, carrierRate) in cases) {
            val bridged = plan(
                fixture(rateHz = carrierRate, maxBytesPerServiceInterval = 2_000),
                DsdCarrierSourceFacts(dsdBitRateHz = dsdRate, channelCount = 2),
            )
            val ready = bridged.result as DoPCarrierPlanningResult.Ready
            assertEquals(carrierRate.toLong(), bridged.pcmFacts.runtimeFrameRateHz)
            assertEquals(carrierRate.toLong(), ready.plan.runtimeFrameRateHz)
        }
    }

    @Test
    fun transportRuntimeRateMismatchIsRejectedByP3BeforePlanner() {
        val fixture = fixture(rateHz = 176_400)
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport.copy(nominalRuntimeFrameRateHz = 192_000L),
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )

        assertBridgeRejected(result, UsbDoPCarrierBridgeRejectionCode.RUNTIME_RATE_MISMATCH)
    }

    @Test
    fun nonExactPcmDecisionIsRejectedBeforeP5() {
        val fixture = fixture(rateHz = 176_400)
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision.copy(signalExact = false),
            transport = fixture.transport,
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )

        assertBridgeRejected(result, UsbDoPCarrierBridgeRejectionCode.NON_EXACT_PCM_DECISION)
    }

    @Test
    fun transportEndpointMismatchIsRejectedBeforeP5() {
        val fixture = fixture(rateHz = 176_400)
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport.copy(dataEndpointAddress = 0x04),
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )

        assertBridgeRejected(result, UsbDoPCarrierBridgeRejectionCode.ENDPOINT_MISMATCH)
    }

    @Test
    fun channelSubslotBitResolutionAndRuntimeFrameMismatchesStayP3Side() {
        val fixture = fixture(rateHz = 176_400)
        val profile = fixture.decision.streamingProfile

        val channelMismatch = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision.copy(
                streamingProfile = profile.copy(channelCount = 3),
            ),
            transport = fixture.transport,
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )
        assertBridgeRejected(channelMismatch, UsbDoPCarrierBridgeRejectionCode.PROFILE_FORMAT_MISMATCH)

        val subslotMismatch = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision.copy(
                streamingProfile = profile.copy(subslotBytes = 4),
            ),
            transport = fixture.transport,
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )
        assertBridgeRejected(subslotMismatch, UsbDoPCarrierBridgeRejectionCode.PROFILE_FORMAT_MISMATCH)

        val bitResolutionMismatch = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision.copy(
                streamingProfile = profile.copy(bitResolution = 32),
            ),
            transport = fixture.transport,
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )
        assertBridgeRejected(bitResolutionMismatch, UsbDoPCarrierBridgeRejectionCode.PROFILE_FORMAT_MISMATCH)

        val runtimeFrameMismatch = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport.copy(bytesPerRuntimeFrame = 7),
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )
        assertBridgeRejected(runtimeFrameMismatch, UsbDoPCarrierBridgeRejectionCode.FRAME_GEOMETRY_MISMATCH)
    }

    @Test
    fun capacityEvidenceAndTransportMaxMismatchIsRejectedBeforeP5() {
        val fixture = fixture(rateHz = 176_400, maxBytesPerServiceInterval = 300)
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport.copy(dataMaxBytesPerServiceInterval = 301),
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )

        assertBridgeRejected(result, UsbDoPCarrierBridgeRejectionCode.CAPACITY_EVIDENCE_MISMATCH)
    }

    @Test
    fun exactNonIntegerServiceFrequencyRatioIsPreservedWithoutMicrosConversion() {
        val fixture = fixture(
            rateHz = 176_400,
            maxBytesPerServiceInterval = 20_000,
            interval = 8,
        )
        assertEquals(UsbExactRatio(2, 125), fixture.transport.dataServicePeriodSeconds)

        val bridged = plan(
            fixture,
            DsdCarrierSourceFacts(2_822_400L, 2),
        )
        val ready = bridged.result as DoPCarrierPlanningResult.Ready

        assertEquals(2L, bridged.pcmFacts.servicePeriodNumeratorSeconds)
        assertEquals(125L, bridged.pcmFacts.servicePeriodDenominatorSeconds)
        assertEquals(2_823L, ready.plan.maxRuntimeFramesPerServiceInterval)
        assertEquals(16_938L, ready.plan.requiredMaxBytesPerServiceInterval)
    }

    @Test
    fun validP3FactsWithWrongDopRateReachP5RuntimeRatePolicy() {
        val bridged = plan(
            fixture(rateHz = 192_000),
            DsdCarrierSourceFacts(2_822_400L, 2),
        )
        val rejected = bridged.result as DoPCarrierPlanningResult.Rejected

        assertEquals(DsdCarrierRejectionCode.RUNTIME_RATE_MISMATCH, rejected.rejection.code)
    }

    @Test
    fun fourByteCarrierWithoutExplicitPlacementEvidenceRemainsP5PackingUnproven() {
        val bridged = plan(
            fixture(
                rateHz = 176_400,
                encoding = UsbPcmEncoding.PCM_24_IN_32,
                maxBytesPerServiceInterval = 400,
            ),
            DsdCarrierSourceFacts(2_822_400L, 2),
        )
        val rejected = bridged.result as DoPCarrierPlanningResult.Rejected

        assertEquals(DsdCarrierRejectionCode.DOP_PACKING_UNPROVEN, rejected.rejection.code)
    }

    @Test
    fun explicitFourBytePackingEvidencePassesOnlyWithMatchingGeometry() {
        val fixture = fixture(
            rateHz = 176_400,
            encoding = UsbPcmEncoding.PCM_24_IN_32,
            maxBytesPerServiceInterval = 400,
        )

        val ready = plan(
            fixture,
            DsdCarrierSourceFacts(2_822_400L, 2),
            ProvenDoPPackingEvidence(DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED),
        ).result as DoPCarrierPlanningResult.Ready
        assertEquals(DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED, ready.plan.packing)
        assertEquals(8, ready.plan.bytesPerRuntimeFrame)

        val wrongGeometry = plan(
            fixture,
            DsdCarrierSourceFacts(2_822_400L, 2),
            ProvenDoPPackingEvidence(DoPCarrierPacking.PACKED_24_LE),
        ).result as DoPCarrierPlanningResult.Rejected
        assertEquals(DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH, wrongGeometry.rejection.code)
    }

    @Test
    fun transportServicePeriodDisagreeingWithProfileIsRejectedBeforeP5() {
        val fixture = fixture(rateHz = 176_400)
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport.copy(dataServicePeriodSeconds = UsbExactRatio(1, 1_000)),
            source = DsdCarrierSourceFacts(2_822_400L, 2),
        )

        assertBridgeRejected(result, UsbDoPCarrierBridgeRejectionCode.SERVICE_PERIOD_MISMATCH)
    }

    private data class Fixture(
        val decision: UsbFormatDecision.Accepted,
        val transport: UsbTransportConfig,
    )

    private fun fixture(
        rateHz: Int,
        encoding: UsbPcmEncoding = UsbPcmEncoding.PCM_24_PACKED,
        channelCount: Int = 2,
        maxBytesPerServiceInterval: Int = 2_000,
        interval: Int = 1,
    ): Fixture {
        val (subslotBytes, bitResolution) = when (encoding) {
            UsbPcmEncoding.PCM_16 -> 2 to 16
            UsbPcmEncoding.PCM_24_PACKED -> 3 to 24
            UsbPcmEncoding.PCM_24_IN_32 -> 4 to 24
            UsbPcmEncoding.PCM_32 -> 4 to 32
        }
        val frameBytes = subslotBytes * channelCount
        val profile = UsbAudioStreamingProfile(
            interfaceNumber = 2,
            alternateSetting = 1,
            endpointAddress = 0x03,
            feedbackEndpointAddress = null,
            feedbackMaxPacketBytes = null,
            feedbackInterval = null,
            channelCount = channelCount,
            encoding = encoding,
            subslotBytes = subslotBytes,
            bitResolution = bitResolution,
            sampleRates = UsbSampleRateSupport.Fixed(rateHz),
            maxPacketBytes = maxBytesPerServiceInterval,
            interval = interval,
            syncMode = UsbEndpointSyncMode.SYNCHRONOUS,
            feedbackPlan = UsbFeedbackPlan(UsbFeedbackMode.NONE),
            capacityEvidence = UsbEndpointCapacityEvidence(
                maxPacketBytes = maxBytesPerServiceInterval,
                bytesPerAudioFrame = frameBytes,
                maxFramesPerServiceInterval = maxBytesPerServiceInterval / frameBytes,
            ),
        )
        val format = UsbPcmFormat(
            sampleRateHz = rateHz,
            channelCount = channelCount,
            encoding = encoding,
        )
        val decision = UsbFormatDecision.Accepted(
            requestedFormat = format,
            deviceFormat = format,
            streamingProfile = profile,
            signalExact = true,
        )
        val transport = (
            UsbTransportConfigBuilder.build(decision, UsbBusSpeed.HIGH) as UsbTransportConfigResult.Ready
            ).config
        return Fixture(decision, transport)
    }

    private fun plan(
        fixture: Fixture,
        source: DsdCarrierSourceFacts,
        packingEvidence: ProvenDoPPackingEvidence? = null,
    ): UsbDoPCarrierBridgeResult.PlannerResult {
        val result = UsbDoPCarrierBridge.planDoP(
            decision = fixture.decision,
            transport = fixture.transport,
            source = source,
            packingEvidence = packingEvidence,
        )
        assertTrue(result is UsbDoPCarrierBridgeResult.PlannerResult)
        return result as UsbDoPCarrierBridgeResult.PlannerResult
    }

    private fun assertBridgeRejected(
        result: UsbDoPCarrierBridgeResult,
        expected: UsbDoPCarrierBridgeRejectionCode,
    ) {
        assertTrue(result is UsbDoPCarrierBridgeResult.Rejected)
        assertEquals(expected, (result as UsbDoPCarrierBridgeResult.Rejected).rejection.code)
    }
}
