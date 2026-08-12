package com.mica.music.media.dsd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdCarrierPlannerTest {

    @Test
    fun dopCarrierRateMathIsExactFromDsd64ThroughDsd512() {
        val cases = listOf(
            2_822_400L to 176_400L,
            5_644_800L to 352_800L,
            11_289_600L to 705_600L,
            22_579_200L to 1_411_200L,
        )

        for ((dsdRate, carrierRate) in cases) {
            val result = DsdCarrierPlanner.planDoP(
                source = DsdCarrierSourceFacts(dsdRate, channelCount = 2),
                pcm = packed24Facts(
                    runtimeFrameRateHz = carrierRate,
                    channelCount = 2,
                    maxBytesPerServiceInterval = 2_000,
                ),
            )
            val plan = (result as DoPCarrierPlanningResult.Ready).plan
            assertEquals(dsdRate, plan.dsdBitRateHz)
            assertEquals(carrierRate, plan.runtimeFrameRateHz)
            assertEquals(6, plan.bytesPerRuntimeFrame)
            assertEquals(DoPCarrierPacking.PACKED_24_LE, plan.packing)
        }
    }

    @Test
    fun dopStereoPacked24PlanMatchesEncoderAndSchedulerGeometry() {
        val plan = readyDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = packed24Facts(176_400L, channelCount = 2, maxBytesPerServiceInterval = 300),
        )
        assertEquals(23L, plan.maxRuntimeFramesPerServiceInterval)
        assertEquals(138L, plan.requiredMaxBytesPerServiceInterval)
        assertEquals(6, plan.bytesPerRuntimeFrame)

        val canonical = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
            0x12, 0x22,
            0x13, 0x23,
        )
        val words = IntArray(4)
        val encoder = DoPEncoder(channelCount = 2)
        val carrierFrames = encoder.encodeFrames(canonical, frameCount = 4, destinationWords = words)
        val payload = ByteArray(carrierFrames * plan.bytesPerRuntimeFrame)
        val payloadBytes = DoPEncoder.packWords(
            words = words,
            wordCount = carrierFrames * 2,
            packing = plan.packing,
            destination = payload,
        )

        assertEquals(2, carrierFrames)
        assertEquals(12, payloadBytes)
        assertEquals(carrierFrames * plan.bytesPerRuntimeFrame, payloadBytes)
    }

    @Test
    fun dopThreeChannelPacked24PlanMatchesExistingMultichannelEncoderGeometry() {
        val plan = readyDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 3),
            pcm = packed24Facts(176_400L, channelCount = 3, maxBytesPerServiceInterval = 400),
        )
        assertEquals(9, plan.bytesPerRuntimeFrame)
        assertEquals(207L, plan.requiredMaxBytesPerServiceInterval)

        val canonical = byteArrayOf(
            0x10, 0x20, 0x30,
            0x11, 0x21, 0x31,
            0x12, 0x22, 0x32,
            0x13, 0x23, 0x33,
        )
        val words = IntArray(6)
        val carrierFrames = DoPEncoder(channelCount = 3).encodeFrames(
            canonical,
            frameCount = 4,
            destinationWords = words,
        )
        val payload = ByteArray(carrierFrames * plan.bytesPerRuntimeFrame)
        val payloadBytes = DoPEncoder.packWords(
            words = words,
            wordCount = carrierFrames * 3,
            packing = plan.packing,
            destination = payload,
        )

        assertEquals(2, carrierFrames)
        assertEquals(18, payloadBytes)
    }

    @Test
    fun dopFourByteCarrierFailsClosedWithoutExplicitSlotPlacementEvidence() {
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = ProvenPcmStreamingFacts(
                runtimeFrameRateHz = 176_400L,
                channelCount = 2,
                subslotBytesPerChannel = 4,
                bitResolution = 24,
                bytesPerRuntimeFrame = 8,
                maxBytesPerServiceInterval = 400,
                servicePeriodNumeratorSeconds = 1,
                servicePeriodDenominatorSeconds = 8_000,
            ),
        )

        assertRejected(result, DsdCarrierRejectionCode.DOP_PACKING_UNPROVEN)
    }

    @Test
    fun dopFourByteCarrierCanBePlannedOnlyWithExplicitP5PackingEvidence() {
        val pcm = ProvenPcmStreamingFacts(
            runtimeFrameRateHz = 176_400L,
            channelCount = 2,
            subslotBytesPerChannel = 4,
            bitResolution = 24,
            bytesPerRuntimeFrame = 8,
            maxBytesPerServiceInterval = 400,
            servicePeriodNumeratorSeconds = 1,
            servicePeriodDenominatorSeconds = 8_000,
        )
        val plan = readyDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = pcm,
            packingEvidence = ProvenDoPPackingEvidence(DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED),
        )

        assertEquals(DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED, plan.packing)
        assertEquals(8, plan.bytesPerRuntimeFrame)
        assertEquals(184L, plan.requiredMaxBytesPerServiceInterval)
    }

    @Test
    fun dopIncompatiblePcmCarrierFailsClosedWithoutFallback() {
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = ProvenPcmStreamingFacts(
                runtimeFrameRateHz = 176_400L,
                channelCount = 2,
                subslotBytesPerChannel = 2,
                bitResolution = 16,
                bytesPerRuntimeFrame = 4,
                maxBytesPerServiceInterval = 200,
                servicePeriodNumeratorSeconds = 1,
                servicePeriodDenominatorSeconds = 8_000,
            ),
        )

        assertRejected(result, DsdCarrierRejectionCode.PCM_CARRIER_INCOMPATIBLE)
    }

    @Test
    fun dopCapacityMismatchFailsClosed() {
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(5_644_800L, channelCount = 2),
            pcm = packed24Facts(
                runtimeFrameRateHz = 352_800L,
                channelCount = 2,
                maxBytesPerServiceInterval = 269,
            ),
        )

        assertRejected(result, DsdCarrierRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT)
    }

    @Test
    fun dopSplitBoundariesKeepMarkerPhaseUnderTheSameCarrierPlan() {
        val plan = readyDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = packed24Facts(176_400L, channelCount = 2, maxBytesPerServiceInterval = 300),
        )
        val canonical = ByteArray(20) { index -> (index + 1).toByte() }

        val oneShotWords = IntArray(10)
        val oneShot = DoPEncoder(channelCount = 2)
        assertEquals(5, oneShot.encodeFrames(canonical, frameCount = 10, destinationWords = oneShotWords))

        val splitWords = IntArray(10)
        val split = DoPEncoder(channelCount = 2)
        assertEquals(0, split.encodeFrames(canonical, frameCount = 1, destinationWords = splitWords))
        assertEquals(
            2,
            split.encodeFrames(
                source = canonical,
                sourceOffset = 2,
                frameCount = 4,
                destinationWords = splitWords,
                destinationWordOffset = 0,
            ),
        )
        assertEquals(
            3,
            split.encodeFrames(
                source = canonical,
                sourceOffset = 10,
                frameCount = 5,
                destinationWords = splitWords,
                destinationWordOffset = 4,
            ),
        )

        assertArrayEquals(oneShotWords, splitWords)
        assertEquals(oneShot.marker, split.marker)
        assertEquals(DoPCarrierPacking.PACKED_24_LE, plan.packing)
    }

    @Test
    fun nativeCandidateRejectsWhenRawDataBitIsAbsent() {
        val result = DsdCarrierPlanner.planNativeCandidate(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            raw = sk02RawFacts(formatsBitmap = 0x00000001L),
        )

        assertRejected(result, DsdCarrierRejectionCode.RAW_DATA_ABSENT)
    }

    @Test
    fun sk02RawDataShapeRemainsExplicitFramingUnprovenCandidate() {
        val result = DsdCarrierPlanner.planNativeCandidate(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            raw = sk02RawFacts(),
        )
        val candidate = (result as NativeDsdCarrierPlanningResult.FramingUnproven).candidate

        assertEquals(88_200L, candidate.runtimeFrameRateHz)
        assertEquals(8, candidate.bytesPerRuntimeFrame)
        assertEquals(4, candidate.bytesPerChannelGroup)
        assertEquals(12L, candidate.maxRuntimeFramesPerServiceInterval)
        assertEquals(96L, candidate.requiredMaxBytesPerServiceInterval)
        assertFalse(candidate.framingProven)
    }

    @Test
    fun nativeCandidateRejectsWhenClockRateIsNotProven() {
        val result = DsdCarrierPlanner.planNativeCandidate(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            raw = sk02RawFacts(provenRuntimeFrameRateHz = null),
        )

        assertRejected(result, DsdCarrierRejectionCode.CLOCK_RATE_UNPROVEN)
    }

    @Test
    fun nativeCandidateRejectsClockRateMismatchInsteadOfChangingDsdRate() {
        val result = DsdCarrierPlanner.planNativeCandidate(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            raw = sk02RawFacts(provenRuntimeFrameRateHz = 176_400L),
        )

        assertRejected(result, DsdCarrierRejectionCode.CLOCK_RATE_MISMATCH)
    }

    private fun readyDoP(
        source: DsdCarrierSourceFacts,
        pcm: ProvenPcmStreamingFacts,
        packingEvidence: ProvenDoPPackingEvidence? = null,
    ): DoPCarrierPlan {
        val result = DsdCarrierPlanner.planDoP(source, pcm, packingEvidence)
        assertTrue(result is DoPCarrierPlanningResult.Ready)
        return (result as DoPCarrierPlanningResult.Ready).plan
    }

    private fun packed24Facts(
        runtimeFrameRateHz: Long,
        channelCount: Int,
        maxBytesPerServiceInterval: Int,
    ) = ProvenPcmStreamingFacts(
        runtimeFrameRateHz = runtimeFrameRateHz,
        channelCount = channelCount,
        subslotBytesPerChannel = 3,
        bitResolution = 24,
        bytesPerRuntimeFrame = channelCount * 3,
        maxBytesPerServiceInterval = maxBytesPerServiceInterval,
        servicePeriodNumeratorSeconds = 1,
        servicePeriodDenominatorSeconds = 8_000,
    )

    private fun sk02RawFacts(
        formatsBitmap: Long = 0x80000000L,
        provenRuntimeFrameRateHz: Long? = 88_200L,
    ) = NativeRawStreamingFacts(
        formatType = 0x01,
        formatsBitmap = formatsBitmap,
        channelCount = 2,
        subslotBytesPerChannel = 4,
        bitResolution = 32,
        bytesPerRuntimeFrame = 8,
        dataEndpointAddress = 0x03,
        maxBytesPerServiceInterval = 400,
        servicePeriodNumeratorSeconds = 1,
        servicePeriodDenominatorSeconds = 8_000,
        provenRuntimeFrameRateHz = provenRuntimeFrameRateHz,
    )

    private fun assertRejected(result: DoPCarrierPlanningResult, code: DsdCarrierRejectionCode) {
        assertTrue(result is DoPCarrierPlanningResult.Rejected)
        assertEquals(code, (result as DoPCarrierPlanningResult.Rejected).rejection.code)
    }

    private fun assertRejected(result: NativeDsdCarrierPlanningResult, code: DsdCarrierRejectionCode) {
        assertTrue(result is NativeDsdCarrierPlanningResult.Rejected)
        assertEquals(code, (result as NativeDsdCarrierPlanningResult.Rejected).rejection.code)
    }
}
