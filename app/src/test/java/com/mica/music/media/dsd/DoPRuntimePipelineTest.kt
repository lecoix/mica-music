package com.mica.music.media.dsd

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DoPRuntimePipelineTest {

    @Test
    fun dsd64ThroughDsd512PipelineMatchesDirectEncoderAndPacking() {
        val rates = listOf(2_822_400L, 5_644_800L, 11_289_600L, 22_579_200L)
        val source = ByteArray(24) { index -> (index * 17 + 3).toByte() }

        for (rate in rates) {
            val plan = packed24Plan(rate, channelCount = 2)
            val expected = directDoP(source, channelCount = 2, plan.packing, drain = false)
            val actual = collectPipeline(
                pipeline = DoPRuntimePipeline(plan),
                source = source,
                inputChunkFrames = intArrayOf(12),
                outputChunkBytes = intArrayOf(4096),
                drainEndOfSource = false,
            )

            assertArrayEquals("DSD rate=$rate", expected, actual)
        }
    }

    @Test
    fun oneByteOutputAndOddCanonicalInputMatchesOneShotIncludingFinalIdleCompletion() {
        val plan = packed24Plan(2_822_400L, channelCount = 2)
        val source = ByteArray(202) { index -> (index * 31 + 7).toByte() } // 101 canonical frames
        val expected = directDoP(source, channelCount = 2, plan.packing, drain = true)
        val actual = collectPipeline(
            pipeline = DoPRuntimePipeline(plan),
            source = source,
            inputChunkFrames = intArrayOf(1, 5, 3, 7, 1, 9),
            outputChunkBytes = intArrayOf(1),
            drainEndOfSource = true,
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun oneByteCanonicalInputChunksPreserveChannelFramesCarryAndMarkerPhase() {
        val plan = packed24Plan(2_822_400L, channelCount = 3)
        val source = ByteArray(3 * 33) { index -> (index * 23 + 5).toByte() }
        val expected = directDoP(source, channelCount = 3, plan.packing, drain = true)
        val pipeline = DoPRuntimePipeline(plan)
        val actual = ByteArrayOutputStream()
        var sourceByte = 0

        while (sourceByte < source.size) {
            val destination = ByteArray(32)
            val result = pipeline.writeBytes(
                source = source,
                sourceOffset = sourceByte,
                sourceByteCount = 1,
                destination = destination,
            )
            actual.write(destination, 0, result.carrierBytesEmitted)
            sourceByte += result.canonicalBytesConsumed
            assertEquals(1, result.canonicalBytesConsumed)
        }
        while (pipeline.hasPendingOutputOrCarry()) {
            val destination = ByteArray(1)
            val result = pipeline.drainEndOfSource(destination)
            actual.write(destination, 0, result.carrierBytesEmitted)
            assertTrue(result.carrierBytesEmitted > 0 || result.completedPendingHalfFrameWithIdle)
        }

        assertArrayEquals(expected, actual.toByteArray())
        assertEquals(source.size.toLong(), pipeline.accounting().canonicalBytesConsumed)
        assertEquals(33L, pipeline.accounting().canonicalFramesConsumed)
        assertEquals(0, pipeline.accounting().pendingPartialCanonicalFrameBytes)
    }

    @Test
    fun arbitraryOutputBufferFragmentationDoesNotChangePayloadOrMarkerPhase() {
        val plan = packed24Plan(5_644_800L, channelCount = 2)
        val source = ByteArray(400) { index -> (index * 13 + 11).toByte() }
        val expected = directDoP(source, channelCount = 2, plan.packing, drain = false)
        val pipeline = DoPRuntimePipeline(plan)
        val actual = collectPipeline(
            pipeline = pipeline,
            source = source,
            inputChunkFrames = intArrayOf(11, 1, 8, 3, 17, 2),
            outputChunkBytes = intArrayOf(1, 2, 5, 3, 7, 4),
            drainEndOfSource = false,
        )

        assertArrayEquals(expected, actual)
        val accounting = pipeline.accounting()
        assertEquals(DoPEncoder.MARKER_A, accounting.nextMarker)
        assertEquals(100L, accounting.runtimeFramesPacked)
        assertEquals(100L, accounting.runtimeFramesFullyEmitted)
        assertEquals(expected.size.toLong(), accounting.carrierBytesEmitted)
        assertEquals(0, accounting.pendingPackedCarrierBytes)
        assertEquals(0L, accounting.carrierBytesDiscardedAtDiscontinuity)
    }

    @Test
    fun stereoAndThreeChannelPipelineGeometryMatchesExistingEncoder() {
        for (channels in listOf(2, 3)) {
            val plan = packed24Plan(2_822_400L, channelCount = channels)
            val source = ByteArray(channels * 20) { index -> (index * 9 + channels).toByte() }
            val expected = directDoP(source, channels, plan.packing, drain = false)
            val actual = collectPipeline(
                pipeline = DoPRuntimePipeline(plan),
                source = source,
                inputChunkFrames = intArrayOf(1, 4, 2, 5),
                outputChunkBytes = intArrayOf(1, plan.bytesPerRuntimeFrame - 1, 2),
                drainEndOfSource = false,
            )

            assertArrayEquals("channels=$channels", expected, actual)
            assertEquals(10 * plan.bytesPerRuntimeFrame, actual.size)
        }
    }

    @Test
    fun explicitFourByteCarrierPlanUsesItsProvenPackingWithoutGeometryDrift() {
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(2_822_400L, channelCount = 2),
            pcm = ProvenPcmStreamingFacts(
                runtimeFrameRateHz = 176_400L,
                channelCount = 2,
                subslotBytesPerChannel = 4,
                bitResolution = 24,
                bytesPerRuntimeFrame = 8,
                maxBytesPerServiceInterval = 4_096,
                servicePeriodNumeratorSeconds = 1L,
                servicePeriodDenominatorSeconds = 8_000L,
            ),
            packingEvidence = ProvenDoPPackingEvidence(DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED),
        )
        val plan = (result as DoPCarrierPlanningResult.Ready).plan
        val source = ByteArray(40) { index -> (index * 7 + 1).toByte() }
        val expected = directDoP(source, 2, plan.packing, drain = false)
        val actual = collectPipeline(
            pipeline = DoPRuntimePipeline(plan),
            source = source,
            inputChunkFrames = intArrayOf(3, 1, 5),
            outputChunkBytes = intArrayOf(1, 3, 7, 2),
            drainEndOfSource = false,
        )

        assertEquals(8, plan.bytesPerRuntimeFrame)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun pauseResumeWithoutEmissionPreservesCarryAndMarkerState() {
        val plan = packed24Plan(2_822_400L, channelCount = 2)
        val pipeline = DoPRuntimePipeline(plan)
        val source = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
            0x12, 0x22,
            0x13, 0x23,
        )
        val first = ByteArray(plan.bytesPerRuntimeFrame * 2)
        val firstResult = pipeline.write(source, frameCount = 3, destination = first)
        assertEquals(3, firstResult.canonicalFramesConsumed)
        assertEquals(plan.bytesPerRuntimeFrame, firstResult.carrierBytesEmitted)

        val paused = pipeline.accounting()
        assertTrue(paused.hasPendingCanonicalHalfFrame)
        assertEquals(DoPEncoder.MARKER_B, paused.nextMarker)

        // Pause/resume has no pipeline call and therefore no state transition.
        val resumed = pipeline.accounting()
        assertEquals(paused, resumed)

        val second = ByteArray(plan.bytesPerRuntimeFrame)
        val secondResult = pipeline.write(
            source = source,
            sourceOffset = 6,
            frameCount = 1,
            destination = second,
        )
        assertEquals(1, secondResult.canonicalFramesConsumed)
        assertEquals(plan.bytesPerRuntimeFrame, secondResult.carrierBytesEmitted)

        val expected = directDoP(source, channelCount = 2, plan.packing, drain = false)
        assertArrayEquals(expected, first.copyOf(plan.bytesPerRuntimeFrame) + second)
        assertEquals(DoPEncoder.MARKER_A, pipeline.accounting().nextMarker)
    }

    @Test
    fun seekResetDiscardsPendingHalfFrameAndRestartsAtMarker05() {
        val plan = packed24Plan(2_822_400L, channelCount = 2)
        val pipeline = DoPRuntimePipeline(plan)
        val stale = byteArrayOf(0x70, 0x71)
        val noOutputExpected = ByteArray(8)
        val staleResult = pipeline.write(stale, frameCount = 1, destination = noOutputExpected)
        assertEquals(1, staleResult.canonicalFramesConsumed)
        assertEquals(0, staleResult.carrierBytesEmitted)
        assertTrue(pipeline.accounting().hasPendingCanonicalHalfFrame)

        val reset = pipeline.resetForDiscontinuity(DoPDiscontinuity.SEEK)
        assertEquals(0, reset.discardedPartialCanonicalFrameBytes)
        assertTrue(reset.discardedPendingCanonicalHalfFrame)
        assertEquals(0, reset.discardedPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_A, pipeline.accounting().nextMarker)
        assertFalse(pipeline.accounting().hasPendingCanonicalHalfFrame)

        val fresh = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
        )
        val output = ByteArray(plan.bytesPerRuntimeFrame)
        val freshResult = pipeline.write(fresh, frameCount = 2, destination = output)
        assertEquals(1, freshResult.runtimeFramesPacked)

        assertArrayEquals(directDoP(fresh, 2, plan.packing, drain = false), output)
        assertEquals(0x11.toByte(), output[0])
        assertEquals(0x10.toByte(), output[1])
        assertEquals(DoPEncoder.MARKER_A.toByte(), output[2])
    }

    @Test
    fun newGenerationResetAccountsForBufferedCarrierBytesItDiscards() {
        val plan = packed24Plan(2_822_400L, channelCount = 2)
        val pipeline = DoPRuntimePipeline(plan)
        val source = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
        )
        val oneByte = ByteArray(1)
        val write = pipeline.write(source, frameCount = 2, destination = oneByte)
        assertEquals(1, write.carrierBytesEmitted)
        assertEquals(plan.bytesPerRuntimeFrame - 1, pipeline.accounting().pendingPackedCarrierBytes)

        val reset = pipeline.resetForDiscontinuity(DoPDiscontinuity.NEW_SOURCE_GENERATION)
        assertEquals(0, reset.discardedPartialCanonicalFrameBytes)
        assertFalse(reset.discardedPendingCanonicalHalfFrame)
        assertEquals(plan.bytesPerRuntimeFrame - 1, reset.discardedPackedCarrierBytes)

        val accounting = pipeline.accounting()
        assertEquals((plan.bytesPerRuntimeFrame - 1).toLong(), accounting.carrierBytesDiscardedAtDiscontinuity)
        assertEquals(0, accounting.pendingPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_A, accounting.nextMarker)
        assertAccountingConserved(plan, accounting)
    }

    @Test
    fun seekResetDiscardsIncompleteCanonicalChannelFrameBeforeItCanReachEncoder() {
        val plan = packed24Plan(2_822_400L, channelCount = 3)
        val pipeline = DoPRuntimePipeline(plan)
        val staleByte = byteArrayOf(0x55)
        val sink = ByteArray(8)
        val partial = pipeline.writeBytes(
            source = staleByte,
            sourceByteCount = 1,
            destination = sink,
        )
        assertEquals(1, partial.canonicalBytesConsumed)
        assertEquals(0, partial.canonicalFramesCompleted)
        assertEquals(1, pipeline.accounting().pendingPartialCanonicalFrameBytes)

        val reset = pipeline.resetForDiscontinuity(DoPDiscontinuity.SEEK)
        assertEquals(1, reset.discardedPartialCanonicalFrameBytes)
        assertFalse(reset.discardedPendingCanonicalHalfFrame)
        assertEquals(DoPEncoder.MARKER_A, pipeline.accounting().nextMarker)

        val fresh = byteArrayOf(
            0x10, 0x20, 0x30,
            0x11, 0x21, 0x31,
        )
        val output = ByteArray(plan.bytesPerRuntimeFrame)
        val freshResult = pipeline.writeBytes(fresh, destination = output)
        assertEquals(fresh.size, freshResult.canonicalBytesConsumed)
        assertArrayEquals(directDoP(fresh, 3, plan.packing, drain = false), output)
    }

    @Test
    fun endOfSourceDrainUsesIdleOnlyForPendingHalfAndNeverInventsIdleFrames() {
        val plan = packed24Plan(2_822_400L, channelCount = 2)
        val pipeline = DoPRuntimePipeline(plan)
        val source = byteArrayOf(0x10, 0x20)
        val scratch = ByteArray(plan.bytesPerRuntimeFrame)
        val write = pipeline.write(source, frameCount = 1, destination = scratch)
        assertEquals(0, write.carrierBytesEmitted)

        val drained = ByteArray(plan.bytesPerRuntimeFrame)
        val drain = pipeline.drainEndOfSource(drained)
        assertTrue(drain.completedPendingHalfFrameWithIdle)
        assertEquals(plan.bytesPerRuntimeFrame, drain.carrierBytesEmitted)
        assertArrayEquals(
            directDoP(source, channelCount = 2, plan.packing, drain = true),
            drained,
        )
        assertEquals(DSD_IDLE_BYTE, drained[0])
        assertEquals(DSD_IDLE_BYTE, drained[3])

        val unsolicited = ByteArray(plan.bytesPerRuntimeFrame)
        val secondDrain = pipeline.drainEndOfSource(unsolicited)
        assertFalse(secondDrain.completedPendingHalfFrameWithIdle)
        assertEquals(0, secondDrain.runtimeFramesPacked)
        assertEquals(0, secondDrain.carrierBytesEmitted)
        assertFalse(pipeline.hasPendingOutputOrCarry())
    }

    @Test
    fun malformedPlanGeometryFailsBeforeAnyPipelineOutput() {
        val malformed = packed24Plan(2_822_400L, channelCount = 2).copy(
            bytesPerRuntimeFrame = 8,
        )

        try {
            DoPRuntimePipeline(malformed)
            fail("expected malformed DoP plan rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("bytes/runtime-frame"))
        }
    }

    @Test
    fun fixedSeedStressPreservesBytesAcrossSplitsAndDiscardsOddCarryAtResets() {
        val random = Random(0xD0_5004L)
        val plan = packed24Plan(11_289_600L, channelCount = 2)
        val pipeline = DoPRuntimePipeline(plan)
        val allExpected = ByteArrayOutputStream()
        val allActual = ByteArrayOutputStream()
        var resets = 0

        repeat(500) {
            val segmentFrames = 1 + random.nextInt(19)
            val source = ByteArray(segmentFrames * 2) { random.nextInt(256).toByte() }
            val expected = directDoP(source, channelCount = 2, plan.packing, drain = false)
            allExpected.write(expected)

            var consumedBytes = 0
            while (consumedBytes < source.size) {
                val requestedBytes = minOf(1 + random.nextInt(13), source.size - consumedBytes)
                val output = ByteArray(1 + random.nextInt(plan.bytesPerRuntimeFrame + 3))
                val result = pipeline.writeBytes(
                    source = source,
                    sourceOffset = consumedBytes,
                    sourceByteCount = requestedBytes,
                    destination = output,
                )
                allActual.write(output, 0, result.carrierBytesEmitted)
                consumedBytes += result.canonicalBytesConsumed
                assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
            }

            while (pipeline.accounting().pendingPackedCarrierBytes > 0) {
                val output = ByteArray(1 + random.nextInt(plan.bytesPerRuntimeFrame))
                val result = pipeline.write(
                    source = ByteArray(0),
                    frameCount = 0,
                    destination = output,
                )
                allActual.write(output, 0, result.carrierBytesEmitted)
                assertTrue(result.carrierBytesEmitted > 0)
            }

            val reset = pipeline.resetForDiscontinuity(
                if (resets % 2 == 0) DoPDiscontinuity.SEEK else DoPDiscontinuity.NEW_SOURCE_GENERATION,
            )
            assertEquals(0, reset.discardedPartialCanonicalFrameBytes)
            assertEquals(segmentFrames % 2 == 1, reset.discardedPendingCanonicalHalfFrame)
            assertEquals(0, reset.discardedPackedCarrierBytes)
            assertEquals(DoPEncoder.MARKER_A, pipeline.accounting().nextMarker)
            resets++
        }

        assertEquals(500, resets)
        assertArrayEquals(allExpected.toByteArray(), allActual.toByteArray())
        assertAccountingConserved(plan, pipeline.accounting())
    }

    private fun collectPipeline(
        pipeline: DoPRuntimePipeline,
        source: ByteArray,
        inputChunkFrames: IntArray,
        outputChunkBytes: IntArray,
        drainEndOfSource: Boolean,
    ): ByteArray {
        require(inputChunkFrames.isNotEmpty() && inputChunkFrames.all { it > 0 })
        require(outputChunkBytes.isNotEmpty() && outputChunkBytes.all { it > 0 })
        val out = ByteArrayOutputStream()
        val totalFrames = source.size / pipeline.plan.channelCount
        var consumedFrames = 0
        var inputIndex = 0
        var outputIndex = 0

        while (consumedFrames < totalFrames) {
            val requestedFrames = minOf(inputChunkFrames[inputIndex++ % inputChunkFrames.size], totalFrames - consumedFrames)
            val destination = ByteArray(outputChunkBytes[outputIndex++ % outputChunkBytes.size])
            val result = pipeline.write(
                source = source,
                sourceOffset = consumedFrames * pipeline.plan.channelCount,
                frameCount = requestedFrames,
                destination = destination,
            )
            out.write(destination, 0, result.carrierBytesEmitted)
            consumedFrames += result.canonicalFramesConsumed
            assertTrue(result.canonicalFramesConsumed > 0 || result.carrierBytesEmitted > 0)
        }

        if (drainEndOfSource) {
            while (pipeline.hasPendingOutputOrCarry()) {
                val destination = ByteArray(outputChunkBytes[outputIndex++ % outputChunkBytes.size])
                val result = pipeline.drainEndOfSource(destination)
                out.write(destination, 0, result.carrierBytesEmitted)
                assertTrue(
                    result.carrierBytesEmitted > 0 ||
                        result.completedPendingHalfFrameWithIdle,
                )
            }
        } else {
            while (pipeline.accounting().pendingPackedCarrierBytes > 0) {
                val destination = ByteArray(outputChunkBytes[outputIndex++ % outputChunkBytes.size])
                val result = pipeline.write(ByteArray(0), frameCount = 0, destination = destination)
                out.write(destination, 0, result.carrierBytesEmitted)
                assertTrue(result.carrierBytesEmitted > 0)
            }
        }

        assertAccountingConserved(pipeline.plan, pipeline.accounting())
        return out.toByteArray()
    }

    private fun directDoP(
        source: ByteArray,
        channelCount: Int,
        packing: DoPCarrierPacking,
        drain: Boolean,
    ): ByteArray {
        require(source.size % channelCount == 0)
        val frameCount = source.size / channelCount
        val encoder = DoPEncoder(channelCount = channelCount)
        val maxCarrierFrames = (frameCount + 1) / 2
        val words = IntArray(maxCarrierFrames * channelCount)
        var produced = encoder.encodeFrames(
            source = source,
            frameCount = frameCount,
            destinationWords = words,
        )
        if (drain && encoder.hasPendingHalfFrame()) {
            produced += encoder.drain(words, destinationWordOffset = produced * channelCount)
        }
        val output = ByteArray(produced * channelCount * packing.bytesPerChannel)
        DoPEncoder.packWords(
            words = words,
            wordCount = produced * channelCount,
            packing = packing,
            destination = output,
        )
        return output
    }

    private fun packed24Plan(dsdBitRateHz: Long, channelCount: Int): DoPCarrierPlan {
        val runtimeRate = dsdBitRateHz / 16L
        val result = DsdCarrierPlanner.planDoP(
            source = DsdCarrierSourceFacts(dsdBitRateHz, channelCount),
            pcm = ProvenPcmStreamingFacts(
                runtimeFrameRateHz = runtimeRate,
                channelCount = channelCount,
                subslotBytesPerChannel = 3,
                bitResolution = 24,
                bytesPerRuntimeFrame = channelCount * 3,
                maxBytesPerServiceInterval = 4_096,
                servicePeriodNumeratorSeconds = 1L,
                servicePeriodDenominatorSeconds = 8_000L,
            ),
        )
        assertTrue(result is DoPCarrierPlanningResult.Ready)
        return (result as DoPCarrierPlanningResult.Ready).plan
    }

    private fun assertAccountingConserved(plan: DoPCarrierPlan, accounting: DoPPipelineAccounting) {
        val packedBytes = accounting.runtimeFramesPacked * plan.bytesPerRuntimeFrame.toLong()
        assertEquals(
            packedBytes,
            accounting.carrierBytesEmitted +
                accounting.carrierBytesDiscardedAtDiscontinuity +
                accounting.pendingPackedCarrierBytes.toLong(),
        )
        assertTrue(accounting.runtimeFramesFullyEmitted <= accounting.runtimeFramesPacked)
    }
}
