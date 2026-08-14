package com.mica.music.media.dsd

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoPRuntimeBulkAlignedFastPathTest {

    @Test
    fun eligibleBulkMatchesForcedGranularForDsd64Through256AndProvenPackings() {
        val rates = listOf(2_822_400L, 5_644_800L, 11_289_600L)
        val packings = listOf(
            DoPCarrierPacking.PACKED_24_LE,
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED,
        )

        for (rate in rates) {
            for (packing in packings) {
                val plan = plan(rate, packing)
                val source = ByteArray(2 * 10) { index -> (index * 41 + 7).toByte() }

                val bulk = DoPRuntimePipeline(plan)
                val bulkOutput = ByteArray(5 * plan.bytesPerRuntimeFrame)
                val bulkResult = bulk.writeBytes(source, destination = bulkOutput)

                val granular = DoPRuntimePipeline(plan)
                val granularOutput = collectOneByteFragments(granular, source)

                assertEquals(source.size, bulkResult.canonicalBytesConsumed)
                assertEquals(10, bulkResult.canonicalFramesCompleted)
                assertEquals(5, bulkResult.runtimeFramesPacked)
                assertEquals(5, bulkResult.runtimeFramesFullyEmitted)
                assertEquals(bulkOutput.size, bulkResult.carrierBytesEmitted)
                assertArrayEquals("rate=$rate packing=$packing", granularOutput, bulkOutput)
                assertEquals("rate=$rate packing=$packing", granular.accounting(), bulk.accounting())
                assertEquals(DoPEncoder.MARKER_B, bulk.accounting().nextMarker)
                assertFalse(bulk.hasPendingOutputOrCarry())
            }
        }
    }

    @Test
    fun consecutiveEligibleBulkChunksPreserveMarkerPhaseAndChronology() {
        val plan = plan(5_644_800L, DoPCarrierPacking.PACKED_24_LE)
        val first = ByteArray(2 * 10) { index -> (index * 17 + 3).toByte() }
        val second = ByteArray(2 * 6) { index -> (index * 29 + 11).toByte() }
        val pipeline = DoPRuntimePipeline(plan)

        val firstOut = ByteArray(5 * plan.bytesPerRuntimeFrame)
        val firstResult = pipeline.writeBytes(first, destination = firstOut)
        assertEquals(firstOut.size, firstResult.carrierBytesEmitted)
        assertEquals(DoPEncoder.MARKER_B, pipeline.accounting().nextMarker)

        val secondOut = ByteArray(3 * plan.bytesPerRuntimeFrame)
        val secondResult = pipeline.writeBytes(second, destination = secondOut)
        assertEquals(secondOut.size, secondResult.carrierBytesEmitted)
        assertEquals(DoPEncoder.MARKER_A, pipeline.accounting().nextMarker)

        val expected = directDoP(first + second, plan)
        assertArrayEquals(expected, firstOut + secondOut)
        assertEquals(16L, pipeline.accounting().canonicalFramesConsumed)
        assertEquals(8L, pipeline.accounting().runtimeFramesPacked)
        assertEquals(8L, pipeline.accounting().runtimeFramesFullyEmitted)
    }

    @Test
    fun alignedNonZeroSourceAndDestinationOffsetsRemainByteExact() {
        val plan = plan(5_644_800L, DoPCarrierPacking.PACKED_24_LE)
        val payload = ByteArray(2 * 8) { index -> (index * 43 + 17).toByte() }
        val source = byteArrayOf(0x55, 0x66, 0x77, 0x22) + payload + byteArrayOf(0x33, 0x44)
        val destination = ByteArray(3 + 4 * plan.bytesPerRuntimeFrame + 5) { 0x6A.toByte() }
        val pipeline = DoPRuntimePipeline(plan)

        val result = pipeline.writeBytes(
            source = source,
            sourceOffset = 4,
            sourceByteCount = payload.size,
            destination = destination,
            destinationOffset = 3,
            destinationByteCount = 4 * plan.bytesPerRuntimeFrame,
        )

        assertEquals(payload.size, result.canonicalBytesConsumed)
        assertArrayEquals(directDoP(payload, plan), destination.copyOfRange(3, 3 + result.carrierBytesEmitted))
        assertTrue(destination.copyOfRange(0, 3).all { it == 0x6A.toByte() })
        assertTrue(destination.copyOfRange(3 + result.carrierBytesEmitted, destination.size).all { it == 0x6A.toByte() })
    }

    @Test
    fun partialCanonicalFrameForcesGranularCallWithoutReorderingLaterBytes() {
        val plan = plan(5_644_800L, DoPCarrierPacking.PACKED_24_LE)
        val source = ByteArray(2 * 8) { index -> (index * 13 + 5).toByte() }
        val pipeline = DoPRuntimePipeline(plan)
        val actual = ByteArrayOutputStream()

        val firstDestination = ByteArray(plan.bytesPerRuntimeFrame * 4)
        val first = pipeline.writeBytes(
            source = source,
            sourceOffset = 0,
            sourceByteCount = 1,
            destination = firstDestination,
        )
        assertEquals(1, first.canonicalBytesConsumed)
        assertEquals(1, pipeline.accounting().pendingPartialCanonicalFrameBytes)

        val secondDestination = ByteArray(plan.bytesPerRuntimeFrame * 4)
        val second = pipeline.writeBytes(
            source = source,
            sourceOffset = 1,
            sourceByteCount = source.size - 1,
            destination = secondDestination,
        )
        actual.write(secondDestination, 0, second.carrierBytesEmitted)

        assertEquals(source.size - 1, second.canonicalBytesConsumed)
        assertEquals(0, pipeline.accounting().pendingPartialCanonicalFrameBytes)
        assertFalse(pipeline.accounting().hasPendingCanonicalHalfFrame)
        assertArrayEquals(directDoP(source, plan), actual.toByteArray())
    }

    @Test
    fun pendingPackedTailPreventsBulkFromOvertakingCommittedCarrierBytes() {
        val plan = plan(5_644_800L, DoPCarrierPacking.PACKED_24_LE)
        val oldSource = byteArrayOf(0x10, 0x20, 0x11, 0x21)
        val newSource = ByteArray(2 * 4) { index -> (index * 19 + 9).toByte() }
        val pipeline = DoPRuntimePipeline(plan)
        val actual = ByteArrayOutputStream()

        val firstByte = ByteArray(1)
        val oldWrite = pipeline.writeBytes(oldSource, destination = firstByte)
        actual.write(firstByte, 0, oldWrite.carrierBytesEmitted)
        assertEquals(1, oldWrite.carrierBytesEmitted)
        assertEquals(plan.bytesPerRuntimeFrame - 1, pipeline.accounting().pendingPackedCarrierBytes)

        val nextDestination = ByteArray(plan.bytesPerRuntimeFrame * 3)
        val next = pipeline.writeBytes(newSource, destination = nextDestination)
        actual.write(nextDestination, 0, next.carrierBytesEmitted)
        assertEquals(newSource.size, next.canonicalBytesConsumed)
        assertEquals(0, pipeline.accounting().pendingPackedCarrierBytes)

        assertArrayEquals(directDoP(oldSource + newSource, plan), actual.toByteArray())
    }

    @Test
    fun insufficientDestinationCapacityKeepsExistingPartialConsumptionSemantics() {
        val plan = plan(5_644_800L, DoPCarrierPacking.PACKED_24_LE)
        val source = ByteArray(2 * 8) { index -> (index * 23 + 1).toByte() }
        val pipeline = DoPRuntimePipeline(plan)
        val firstOut = ByteArray(plan.bytesPerRuntimeFrame)

        val first = pipeline.writeBytes(source, destination = firstOut)
        assertEquals(4, first.canonicalBytesConsumed)
        assertEquals(2, first.canonicalFramesCompleted)
        assertEquals(1, first.runtimeFramesPacked)
        assertEquals(plan.bytesPerRuntimeFrame, first.carrierBytesEmitted)
        assertEquals(0, pipeline.accounting().pendingPackedCarrierBytes)

        val remainder = ByteArray(3 * plan.bytesPerRuntimeFrame)
        val second = pipeline.writeBytes(
            source = source,
            sourceOffset = first.canonicalBytesConsumed,
            sourceByteCount = source.size - first.canonicalBytesConsumed,
            destination = remainder,
        )
        assertEquals(source.size - first.canonicalBytesConsumed, second.canonicalBytesConsumed)
        assertArrayEquals(directDoP(source, plan), firstOut + remainder)
    }

    @Test
    fun oddCanonicalFrameChunkFallsBackAndDrainPreservesContentChronology() {
        val plan = plan(2_822_400L, DoPCarrierPacking.PACKED_24_LE)
        val source = ByteArray(2 * 3) { index -> (index * 31 + 7).toByte() }
        val pipeline = DoPRuntimePipeline(plan)
        val first = ByteArray(plan.bytesPerRuntimeFrame)

        val write = pipeline.writeBytes(source, destination = first)
        assertEquals(4, write.canonicalBytesConsumed)
        assertEquals(2, write.canonicalFramesCompleted)
        assertEquals(1, write.runtimeFramesPacked)

        val second = ByteArray(plan.bytesPerRuntimeFrame)
        val tail = pipeline.writeBytes(
            source = source,
            sourceOffset = write.canonicalBytesConsumed,
            sourceByteCount = source.size - write.canonicalBytesConsumed,
            destination = second,
        )
        assertEquals(2, tail.canonicalBytesConsumed)
        assertEquals(1, tail.canonicalFramesCompleted)
        assertEquals(0, tail.carrierBytesEmitted)
        assertTrue(pipeline.accounting().hasPendingCanonicalHalfFrame)

        val drained = ByteArray(plan.bytesPerRuntimeFrame)
        val drain = pipeline.drainEndOfSource(drained)
        assertTrue(drain.completedPendingHalfFrameWithIdle)
        assertArrayEquals(directDoP(source, plan, drain = true), first + drained)
        assertFalse(pipeline.hasPendingOutputOrCarry())
    }

    @Test
    fun gapAndResetsAfterBulkKeepExistingMarkerAndAccountingContracts() {
        val plan = plan(2_822_400L, DoPCarrierPacking.PACKED_24_LE)
        val pipeline = DoPRuntimePipeline(plan)
        val source = ByteArray(2 * 6) { index -> (index * 7 + 3).toByte() }
        val content = ByteArray(3 * plan.bytesPerRuntimeFrame)
        pipeline.writeBytes(source, destination = content)
        assertEquals(DoPEncoder.MARKER_B, pipeline.accounting().nextMarker)

        val gapBytes = ByteArray(2 * plan.bytesPerRuntimeFrame)
        val gap = pipeline.writeGapFrames(2, gapBytes)
        assertEquals(2, gap.gapFramesAccepted)
        assertEquals(2, gap.pureIdleFramesPacked)
        assertEquals(DoPEncoder.MARKER_B, pipeline.accounting().nextMarker)
        assertEquals(3L, pipeline.accounting().contentRuntimeFramesPacked)
        assertEquals(2L, pipeline.accounting().idleRuntimeFramesPacked)

        val sourceReset = pipeline.resetSourceForRetainedCarrier(DoPDiscontinuity.NEW_SOURCE_GENERATION)
        assertEquals(DoPEncoder.MARKER_B, sourceReset.markerBeforeReset)
        assertEquals(DoPEncoder.MARKER_B, sourceReset.markerAfterReset)

        val carrierReset = pipeline.resetCarrierSession(DoPCarrierSessionReset.RECONFIGURE)
        assertEquals(DoPEncoder.MARKER_B, carrierReset.markerBeforeReset)
        assertEquals(DoPEncoder.MARKER_A, carrierReset.markerAfterReset)
    }

    @Test
    fun oneShotEligibleChunkAndArbitraryFragmentsConvergeAfterFullDrain() {
        val plan = plan(11_289_600L, DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED)
        val source = ByteArray(2 * 64) { index -> (index * 37 + 13).toByte() }

        val bulk = DoPRuntimePipeline(plan)
        val bulkOut = ByteArray(32 * plan.bytesPerRuntimeFrame)
        val bulkWrite = bulk.writeBytes(source, destination = bulkOut)
        assertEquals(source.size, bulkWrite.canonicalBytesConsumed)

        val fragmented = DoPRuntimePipeline(plan)
        val fragmentedOut = collectArbitraryFragments(fragmented, source)

        assertArrayEquals(bulkOut, fragmentedOut)
        assertEquals(bulk.accounting(), fragmented.accounting())
        assertFalse(bulk.hasPendingOutputOrCarry())
        assertFalse(fragmented.hasPendingOutputOrCarry())
    }

    private fun collectOneByteFragments(pipeline: DoPRuntimePipeline, source: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var sourceOffset = 0
        while (sourceOffset < source.size) {
            val destination = ByteArray(maxOf(1, pipeline.plan.bytesPerRuntimeFrame - 1))
            val result = pipeline.writeBytes(
                source = source,
                sourceOffset = sourceOffset,
                sourceByteCount = 1,
                destination = destination,
            )
            out.write(destination, 0, result.carrierBytesEmitted)
            sourceOffset += result.canonicalBytesConsumed
            assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
        }
        flushPending(pipeline, out)
        assertFalse(pipeline.hasPendingOutputOrCarry())
        return out.toByteArray()
    }

    private fun collectArbitraryFragments(pipeline: DoPRuntimePipeline, source: ByteArray): ByteArray {
        val inputPattern = intArrayOf(1, 7, 2, 11, 3, 5, 13, 4)
        val outputPattern = intArrayOf(1, 3, 2, 7, 5, pipeline.plan.bytesPerRuntimeFrame - 1)
        val out = ByteArrayOutputStream()
        var sourceOffset = 0
        var inputIndex = 0
        var outputIndex = 0
        while (sourceOffset < source.size) {
            val requested = minOf(inputPattern[inputIndex++ % inputPattern.size], source.size - sourceOffset)
            val destination = ByteArray(outputPattern[outputIndex++ % outputPattern.size])
            val result = pipeline.writeBytes(
                source = source,
                sourceOffset = sourceOffset,
                sourceByteCount = requested,
                destination = destination,
            )
            out.write(destination, 0, result.carrierBytesEmitted)
            sourceOffset += result.canonicalBytesConsumed
            assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
        }
        flushPending(pipeline, out)
        if (pipeline.accounting().hasPendingCanonicalHalfFrame) {
            val destination = ByteArray(pipeline.plan.bytesPerRuntimeFrame)
            val drain = pipeline.drainEndOfSource(destination)
            out.write(destination, 0, drain.carrierBytesEmitted)
        }
        flushPending(pipeline, out)
        return out.toByteArray()
    }

    private fun flushPending(pipeline: DoPRuntimePipeline, out: ByteArrayOutputStream) {
        while (pipeline.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(maxOf(1, pipeline.plan.bytesPerRuntimeFrame - 1))
            val flushed = pipeline.flushPendingOutput(destination)
            out.write(destination, 0, flushed.carrierBytesEmitted)
            assertTrue(flushed.carrierBytesEmitted > 0)
        }
    }

    private fun directDoP(source: ByteArray, plan: DoPCarrierPlan, drain: Boolean = false): ByteArray {
        require(source.size % plan.channelCount == 0)
        val frameCount = source.size / plan.channelCount
        val encoder = DoPEncoder(plan.channelCount)
        val words = IntArray(((frameCount + 1) / 2) * plan.channelCount)
        var produced = encoder.encodeFrames(source, frameCount = frameCount, destinationWords = words)
        if (drain && encoder.hasPendingHalfFrame()) {
            produced += encoder.drain(words, produced * plan.channelCount)
        }
        return ByteArray(produced * plan.bytesPerRuntimeFrame).also { destination ->
            DoPEncoder.packWords(
                words = words,
                wordCount = produced * plan.channelCount,
                packing = plan.packing,
                destination = destination,
            )
        }
    }

    private fun plan(rate: Long, packing: DoPCarrierPacking): DoPCarrierPlan {
        val runtimeRate = rate / 16L
        val maxFrames = (runtimeRate + 7_999L) / 8_000L
        val bytesPerFrame = 2 * packing.bytesPerChannel
        return DoPCarrierPlan(
            dsdBitRateHz = rate,
            channelCount = 2,
            runtimeFrameRateHz = runtimeRate,
            bytesPerRuntimeFrame = bytesPerFrame,
            packing = packing,
            maxRuntimeFramesPerServiceInterval = maxFrames,
            requiredMaxBytesPerServiceInterval = maxFrames * bytesPerFrame.toLong(),
        )
    }
}
