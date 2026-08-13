package com.mica.music.media.usb

import com.mica.music.media.dsd.DoPCarrierPacking
import com.mica.music.media.dsd.DoPCarrierPlan
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.DoPCarrierSession
import com.mica.music.media.dsd.DoPCarrierSessionReset
import com.mica.music.media.dsd.DoPDiscontinuity
import com.mica.music.media.dsd.DoPEncoder
import com.mica.music.media.dsd.DoPGapBlockedReason
import com.mica.music.media.dsd.DsdCarrierPlanner
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsd.ProvenDoPPackingEvidence
import com.mica.music.media.dsd.ProvenPcmStreamingFacts
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactCarrierFeederTest {
    @Test
    fun fullSinkAcceptanceMatchesDirectP5OutputByteForByte() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = ByteArray(80) { index -> (index * 17 + 3).toByte() }
        val expected = directContentOutput(plan, source)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, _ -> offered }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        driveContent(feeder, source)
        drainFeeder(feeder)

        assertArrayEquals(expected, sink.bytes())
        assertEquals(source.size.toLong(), feeder.accounting().canonicalBytesConsumed)
        assertEquals(0, feeder.snapshot().stagedCarrierBytes.size)
    }

    @Test
    fun repeatedZeroSinkResponsesRetainExactStagingAndDoNotOverproduce() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7f)
        val expected = directContentOutput(plan, source)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, call ->
            if (call <= 4) 0 else offered
        }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val first = feeder.writeContentBytes(source)
        assertEquals(source.size, first.canonicalBytesConsumed)
        assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, first.status)
        assertArrayEquals(expected, feeder.snapshot().stagedCarrierBytes)
        val accountingAfterGeneration = feeder.accounting()

        repeat(3) {
            val blocked = feeder.writeContentBytes(byteArrayOf(1, 2, 3, 4))
            assertEquals(0, blocked.canonicalBytesConsumed)
            assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, blocked.status)
            assertEquals(accountingAfterGeneration, feeder.accounting())
            assertArrayEquals(expected, feeder.snapshot().stagedCarrierBytes)
        }

        drainFeeder(feeder)
        assertArrayEquals(expected, sink.bytes())
        assertEquals(source.size.toLong(), feeder.accounting().canonicalBytesConsumed)
    }

    @Test
    fun alignedOneFrameAndMixedPrefixWritesPreserveExactOrder() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = ByteArray(160) { index -> (index * 31 + 9).toByte() }
        val expected = directContentOutput(plan, source)
        val frame = plan.bytesPerRuntimeFrame
        val scriptFrames = intArrayOf(1, 2, 1, 3, 1, 4, 2, 1)
        val sink = CollectingSink(frame) { offered, call ->
            val offeredFrames = offered / frame
            val requestedFrames = scriptFrames[(call - 1) % scriptFrames.size]
            minOf(offeredFrames, requestedFrames) * frame
        }
        val feeder = ExactCarrierFeeder(
            session = DoPCarrierSession(plan),
            sink = sink,
            stagingFrameCapacity = 8,
        )

        driveContent(feeder, source)
        drainFeeder(feeder)

        assertArrayEquals(expected, sink.bytes())
        assertTrue(sink.offeredCounts.all { it % frame == 0 })
        assertTrue(sink.acceptedCounts.filter { it > 0 }.all { it % frame == 0 })
    }

    @Test
    fun invalidNonAlignedSinkAcceptanceFailsClosedAndRetainsAuditableBytes() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val expected = directContentOutput(plan, source)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { _, _ -> 1 }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val result = feeder.writeContentBytes(source)

        assertEquals(ExactCarrierFeedStatus.FAILED, result.status)
        assertEquals(
            ExactCarrierFeederContractErrorCode.SINK_NON_FRAME_ALIGNED_ACCEPTANCE,
            result.error?.code,
        )
        assertArrayEquals(expected, feeder.snapshot().stagedCarrierBytes)
        assertArrayEquals(ByteArray(0), sink.bytes())
        val retried = feeder.pump()
        assertEquals(ExactCarrierFeedStatus.FAILED, retried.status)
        assertArrayEquals(expected, feeder.snapshot().stagedCarrierBytes)
    }

    @Test
    fun invalidNegativeSinkAcceptanceFailsClosedWithoutDiscard() {
        assertInvalidAcceptance(
            response = -1,
            expectedCode = ExactCarrierFeederContractErrorCode.SINK_NEGATIVE_ACCEPTANCE,
        )
    }

    @Test
    fun invalidOverAcceptanceFailsClosedWithoutDiscard() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        assertInvalidAcceptance(
            response = plan.bytesPerRuntimeFrame * 2,
            expectedCode = ExactCarrierFeederContractErrorCode.SINK_OVER_ACCEPTANCE,
        )
    }

    @Test
    fun canonicalBytesAreConsumedOnceWhileCarrierRetriesManyTimes() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, call ->
            if (call <= 6) 0 else offered
        }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val generated = feeder.writeContentBytes(source)
        assertEquals(source.size, generated.canonicalBytesConsumed)
        assertEquals(source.size.toLong(), feeder.accounting().canonicalBytesConsumed)

        repeat(5) {
            val retry = feeder.writeContentBytes(source)
            assertEquals(0, retry.canonicalBytesConsumed)
            assertEquals(source.size.toLong(), feeder.accounting().canonicalBytesConsumed)
        }
        drainFeeder(feeder)
        assertEquals(source.size.toLong(), feeder.accounting().canonicalBytesConsumed)
        assertArrayEquals(directContentOutput(plan, source), sink.bytes())
    }

    @Test
    fun stagedContentBlocksNewContentGapAndResetUntilDrained() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { _, _ -> 0 }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)
        val firstSource = byteArrayOf(1, 2, 3, 4)

        val generated = feeder.writeContentBytes(firstSource)
        assertEquals(firstSource.size, generated.canonicalBytesConsumed)
        val before = feeder.accounting()
        val stagedBefore = feeder.snapshot().stagedCarrierBytes
        assertTrue(stagedBefore.isNotEmpty())

        val contentBlocked = feeder.writeContentBytes(byteArrayOf(9, 10, 11, 12))
        assertEquals(0, contentBlocked.canonicalBytesConsumed)
        assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, contentBlocked.status)

        val gapBlocked = feeder.writeGapFrames(3)
        assertEquals(0, gapBlocked.gapFramesAccepted)
        assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, gapBlocked.status)

        val sourceReset = feeder.resetSource(DoPDiscontinuity.SEEK)
        assertFalse(sourceReset.applied)
        assertEquals(ExactCarrierResetBlockedReason.STAGED_CARRIER_BYTES, sourceReset.blockedReason)

        val carrierReset = feeder.resetCarrier(DoPCarrierSessionReset.RECONFIGURE)
        assertFalse(carrierReset.applied)
        assertEquals(ExactCarrierResetBlockedReason.STAGED_CARRIER_BYTES, carrierReset.blockedReason)

        assertEquals(before, feeder.accounting())
        assertArrayEquals(stagedBefore, feeder.snapshot().stagedCarrierBytes)
    }

    @Test
    fun pendingRealHalfFramePrecedesIdleThroughShortWritesAndExplicitFlush() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val frame = plan.bytesPerRuntimeFrame
        val sink = CollectingSink(frame) { offered, _ -> minOf(frame, offered) }
        val session = DoPCarrierSession(plan)
        val feeder = ExactCarrierFeeder(
            session = session,
            sink = sink,
            stagingFrameCapacity = 3,
            upstreamEmissionChunkBytes = frame * 2 - 1,
        )

        // One canonical channel-frame becomes P5's pending real half-frame and emits no carrier yet.
        val content = feeder.writeContentBytes(byteArrayOf(0x12, 0x34))
        assertEquals(2, content.canonicalBytesConsumed)
        assertEquals(0, content.carrierBytesCapturedFromSession)

        val firstGap = feeder.writeGapFrames(3)
        assertEquals(2, firstGap.gapFramesAccepted)
        assertEquals(frame, firstGap.sinkBytesAccepted)
        assertEquals(frame - 1, feeder.snapshot().stagedCarrierBytes.size)
        assertEquals(1, feeder.snapshot().upstreamPendingPackedCarrierBytes)

        // Only explicit flushCarrierOutput may complete the second accepted frame.
        val flushPump = feeder.pump()
        assertEquals(1, flushPump.carrierBytesFlushedFromSession)
        assertEquals(frame, flushPump.sinkBytesAccepted)
        assertEquals(0, feeder.snapshot().upstreamPendingPackedCarrierBytes)
        assertEquals(0, feeder.snapshot().stagedCarrierBytes.size)

        val remainingGap = feeder.writeGapFrames(1)
        assertEquals(1, remainingGap.gapFramesAccepted)
        drainFeeder(feeder)

        val direct = DirectReference(plan)
        direct.content(byteArrayOf(0x12, 0x34))
        direct.gap(3)
        assertArrayEquals(direct.bytes(), sink.bytes())
        assertEquals(3L, feeder.accounting().runtimeFramesPacked)
        assertEquals(1L, feeder.accounting().contentRuntimeFramesPacked)
        assertEquals(2L, feeder.accounting().idleRuntimeFramesPacked)
    }

    @Test
    fun partialCanonicalGapBlockPropagatesWithoutSynthesisThenSourceResetAllowsGap() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, _ -> offered }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val partial = feeder.writeContentBytes(byteArrayOf(0x55))
        assertEquals(1, partial.canonicalBytesConsumed)
        assertEquals(0, partial.carrierBytesCapturedFromSession)
        val beforeGap = feeder.accounting()

        val blocked = feeder.writeGapFrames(2)
        assertEquals(ExactCarrierFeedStatus.BLOCKED, blocked.status)
        assertEquals(DoPGapBlockedReason.PARTIAL_CANONICAL_FRAME, blocked.blockedReason)
        assertEquals(0, blocked.gapFramesAccepted)
        assertEquals(0, blocked.carrierBytesCapturedFromSession)
        assertArrayEquals(ByteArray(0), sink.bytes())
        assertEquals(beforeGap, feeder.accounting())

        val reset = feeder.resetSource(DoPDiscontinuity.SEEK)
        assertTrue(reset.applied)
        assertEquals(1, reset.reset?.discardedCanonicalSourceBytes)
        assertEquals(reset.reset?.markerBeforeReset, reset.reset?.markerAfterReset)

        val gap = feeder.writeGapFrames(2)
        assertEquals(2, gap.gapFramesAccepted)
        drainFeeder(feeder)
        assertEquals(DoPEncoder.MARKER_A, markerAt(plan, sink.bytes(), frameIndex = 0))
    }

    @Test
    fun gapBudgetIsAcceptedByP5IndependentlyFromSinkCompletion() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { _, _ -> 0 }
        val feeder = ExactCarrierFeeder(
            session = DoPCarrierSession(plan),
            sink = sink,
            stagingFrameCapacity = 2,
        )

        val generated = feeder.writeGapFrames(5)
        assertEquals(2, generated.gapFramesAccepted)
        assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, generated.status)
        assertEquals(2L, feeder.accounting().runtimeFramesPacked)
        assertEquals(plan.bytesPerRuntimeFrame * 2, feeder.snapshot().stagedCarrierBytes.size)

        val retryWhileStaged = feeder.writeGapFrames(3)
        assertEquals(0, retryWhileStaged.gapFramesAccepted)
        assertEquals(ExactCarrierFeedStatus.BACKPRESSURED, retryWhileStaged.status)
        assertEquals(2L, feeder.accounting().runtimeFramesPacked)
    }

    @Test
    fun zeroFrameGapIsStrictNoOpAndNeverUsedAsFlush() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val frame = plan.bytesPerRuntimeFrame
        val sink = CollectingSink(frame) { _, _ -> 0 }
        val feeder = ExactCarrierFeeder(
            session = DoPCarrierSession(plan),
            sink = sink,
            stagingFrameCapacity = 2,
            upstreamEmissionChunkBytes = frame - 1,
        )

        val content = feeder.writeContentBytes(byteArrayOf(1, 2, 3, 4))
        // writeContent emitted frame-1 bytes, then feeder's explicit flush completed the P5 frame.
        assertEquals(frame, content.carrierBytesCapturedFromSession)
        assertEquals(frame, feeder.snapshot().stagedCarrierBytes.size)
        assertEquals(0, feeder.snapshot().upstreamPendingPackedCarrierBytes)
        val stagedBefore = feeder.snapshot().stagedCarrierBytes
        val accountingBefore = feeder.accounting()
        val sinkCallsBefore = sink.callCount

        val zero = feeder.writeGapFrames(0)

        assertEquals(ExactCarrierFeedStatus.NO_PROGRESS, zero.status)
        assertEquals(0, zero.gapFramesAccepted)
        assertEquals(0, zero.carrierBytesCapturedFromSession)
        assertEquals(sinkCallsBefore, sink.callCount)
        assertArrayEquals(stagedBefore, feeder.snapshot().stagedCarrierBytes)
        assertEquals(accountingBefore, feeder.accounting())
    }

    @Test
    fun carrierResetAfterDrainRestartsMarkerAt05() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, _ -> offered }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        driveContent(feeder, byteArrayOf(1, 2, 3, 4))
        drainFeeder(feeder)
        assertEquals(DoPEncoder.MARKER_A, markerAt(plan, sink.bytes(), 0))

        val reset = feeder.resetCarrier(DoPCarrierSessionReset.NEW_CARRIER_SESSION)
        assertTrue(reset.applied)
        assertEquals(DoPEncoder.MARKER_A, reset.reset?.markerAfterReset)

        driveContent(feeder, byteArrayOf(5, 6, 7, 8))
        drainFeeder(feeder)
        assertEquals(DoPEncoder.MARKER_A, markerAt(plan, sink.bytes(), 1))
    }

    @Test
    fun retainedCarrierSourceResetPreservesMarkerAndDiscardAccounting() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, _ -> offered }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        // One canonical frame leaves a real pending half-frame with no emitted carrier bytes.
        val content = feeder.writeContentBytes(byteArrayOf(0x21, 0x43))
        assertEquals(2, content.canonicalBytesConsumed)
        val markerBefore = feeder.accounting().nextMarker

        val reset = feeder.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)
        assertTrue(reset.applied)
        assertEquals(2, reset.reset?.discardedCanonicalSourceBytes)
        assertTrue(reset.reset?.discardedPendingCanonicalHalfFrame == true)
        assertEquals(markerBefore, reset.reset?.markerBeforeReset)
        assertEquals(markerBefore, reset.reset?.markerAfterReset)
        assertEquals(2L, feeder.accounting().canonicalSourceBytesDiscardedAtReset)
    }

    @Test
    fun allProvenDoPPackingsRemainByteIdenticalThroughFeeder() {
        for (packing in listOf(
            DoPCarrierPacking.PACKED_24_LE,
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED,
            DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED,
        )) {
            val plan = plan(packing)
            val source = ByteArray(96) { index -> (index * 13 + packing.ordinal * 19).toByte() }
            val expected = directContentOutput(plan, source)
            val sink = CollectingSink(plan.bytesPerRuntimeFrame) { offered, call ->
                val frames = offered / plan.bytesPerRuntimeFrame
                if (call % 3 == 0) 0 else minOf(frames, 2) * plan.bytesPerRuntimeFrame
            }
            val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)
            driveContent(feeder, source)
            drainFeeder(feeder)
            assertArrayEquals("packing=$packing", expected, sink.bytes())
        }
    }

    @Test
    fun sinkFrameGeometryMismatchPoisonsFeederBeforeP5Consumption() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame + 1) { offered, _ -> offered }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val result = feeder.writeContentBytes(byteArrayOf(1, 2, 3, 4))

        assertEquals(ExactCarrierFeedStatus.FAILED, result.status)
        assertEquals(
            ExactCarrierFeederContractErrorCode.SINK_FRAME_SIZE_MISMATCH,
            result.error?.code,
        )
        assertEquals(0L, feeder.accounting().canonicalBytesConsumed)
        assertArrayEquals(ByteArray(0), feeder.snapshot().stagedCarrierBytes)
    }

    @Test
    fun fixedSeedTenThousandOperationProjectionMatchesDirectChronology() {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val reference = DirectReference(plan)
        val randomSink = RandomAlignedSink(plan.bytesPerRuntimeFrame, seed = 0x51a7eL)
        val feeder = ExactCarrierFeeder(
            session = DoPCarrierSession(plan),
            sink = randomSink,
            stagingFrameCapacity = 8,
            upstreamEmissionChunkBytes = plan.bytesPerRuntimeFrame * 3 - 1,
        )
        val operations = Random(0x3d5d17L)

        repeat(10_000) { operationIndex ->
            when (operations.nextInt(10)) {
                in 0..5 -> {
                    val canonicalFrames = if (operations.nextBoolean()) 1 else 2
                    val source = ByteArray(canonicalFrames * plan.channelCount) { byteIndex ->
                        (operationIndex * 37 + byteIndex * 23 + 11).toByte()
                    }
                    reference.content(source)
                    driveContent(feeder, source)
                    drainFeeder(feeder)
                }
                in 6..8 -> {
                    val frames = operations.nextInt(3) + 1
                    reference.gap(frames)
                    driveGap(feeder, frames)
                    drainFeeder(feeder)
                }
                else -> {
                    drainFeeder(feeder)
                    val directReset = reference.resetSource(DoPDiscontinuity.SEEK)
                    val feederReset = feeder.resetSource(DoPDiscontinuity.SEEK)
                    assertTrue(feederReset.applied)
                    assertEquals(
                        directReset.discardedCanonicalSourceBytes,
                        feederReset.reset?.discardedCanonicalSourceBytes,
                    )
                    assertEquals(directReset.markerAfterReset, feederReset.reset?.markerAfterReset)
                }
            }
        }
        drainFeeder(feeder)

        assertArrayEquals(reference.bytes(), randomSink.bytes())
        val expected = reference.accounting()
        val actual = feeder.accounting()
        assertEquals(expected.canonicalBytesConsumed, actual.canonicalBytesConsumed)
        assertEquals(expected.canonicalFramesConsumed, actual.canonicalFramesConsumed)
        assertEquals(expected.runtimeFramesPacked, actual.runtimeFramesPacked)
        assertEquals(expected.runtimeFramesFullyEmitted, actual.runtimeFramesFullyEmitted)
        assertEquals(expected.carrierBytesEmitted, actual.carrierBytesEmitted)
        assertEquals(expected.canonicalSourceBytesDiscardedAtReset, actual.canonicalSourceBytesDiscardedAtReset)
        assertEquals(expected.contentRuntimeFramesPacked, actual.contentRuntimeFramesPacked)
        assertEquals(expected.idleRuntimeFramesPacked, actual.idleRuntimeFramesPacked)
        assertEquals(expected.nextMarker, actual.nextMarker)
        assertEquals(expected.lastPackedMarker, actual.lastPackedMarker)
        assertNull(feeder.snapshot().contractError)
        assertTrue(randomSink.zeroAcceptCount > 0)
        assertTrue(randomSink.shortAcceptCount > 0)
    }

    private fun assertInvalidAcceptance(
        response: Int,
        expectedCode: ExactCarrierFeederContractErrorCode,
    ) {
        val plan = plan(DoPCarrierPacking.PACKED_24_LE)
        val source = byteArrayOf(1, 2, 3, 4)
        val expected = directContentOutput(plan, source)
        val sink = CollectingSink(plan.bytesPerRuntimeFrame) { _, _ -> response }
        val feeder = ExactCarrierFeeder(DoPCarrierSession(plan), sink)

        val result = feeder.writeContentBytes(source)

        assertEquals(ExactCarrierFeedStatus.FAILED, result.status)
        assertEquals(expectedCode, result.error?.code)
        assertArrayEquals(expected, feeder.snapshot().stagedCarrierBytes)
        assertArrayEquals(ByteArray(0), sink.bytes())
    }

    private fun driveContent(feeder: ExactCarrierFeeder, source: ByteArray) {
        var offset = 0
        var guard = 0
        while (offset < source.size) {
            check(++guard < 100_000) { "content feeder made no bounded progress" }
            val result = feeder.writeContentBytes(
                source = source,
                sourceOffset = offset,
                sourceByteCount = source.size - offset,
            )
            check(result.status != ExactCarrierFeedStatus.FAILED) { result.error.toString() }
            offset += result.canonicalBytesConsumed
            if (result.canonicalBytesConsumed == 0 && result.status == ExactCarrierFeedStatus.NO_PROGRESS) {
                error("content feeder made no progress with source remaining")
            }
        }
    }

    private fun driveGap(feeder: ExactCarrierFeeder, frames: Int) {
        var remaining = frames
        var guard = 0
        while (remaining > 0) {
            check(++guard < 100_000) { "gap feeder made no bounded progress" }
            val result = feeder.writeGapFrames(remaining)
            check(result.status != ExactCarrierFeedStatus.FAILED) { result.error.toString() }
            check(result.blockedReason == null) { "unexpected gap block=${result.blockedReason}" }
            remaining -= result.gapFramesAccepted
            if (result.gapFramesAccepted == 0 && result.status == ExactCarrierFeedStatus.NO_PROGRESS) {
                error("gap feeder made no progress with budget remaining")
            }
        }
    }

    private fun drainFeeder(feeder: ExactCarrierFeeder) {
        var guard = 0
        while (true) {
            val snapshot = feeder.snapshot()
            if (snapshot.stagedCarrierBytes.isEmpty() && snapshot.upstreamPendingPackedCarrierBytes == 0) {
                return
            }
            check(++guard < 100_000) { "staging did not drain" }
            val pump = feeder.pump()
            check(pump.status != ExactCarrierFeedStatus.FAILED) { pump.error.toString() }
        }
    }

    private fun directContentOutput(plan: DoPCarrierPlan, source: ByteArray): ByteArray {
        val reference = DirectReference(plan)
        reference.content(source)
        return reference.bytes()
    }

    private fun markerAt(plan: DoPCarrierPlan, bytes: ByteArray, frameIndex: Int): Int {
        val frameOffset = frameIndex * plan.bytesPerRuntimeFrame
        val channelOffset = frameOffset
        return when (plan.packing) {
            DoPCarrierPacking.PACKED_24_LE -> bytes[channelOffset + 2].toInt() and 0xff
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED -> bytes[channelOffset + 3].toInt() and 0xff
            DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED -> bytes[channelOffset + 2].toInt() and 0xff
        }
    }

    private fun plan(
        packing: DoPCarrierPacking,
        channelCount: Int = 2,
    ): DoPCarrierPlan {
        val source = DsdCarrierSourceFacts(
            dsdBitRateHz = 2_822_400L,
            channelCount = channelCount,
        )
        val pcm = ProvenPcmStreamingFacts(
            runtimeFrameRateHz = 176_400L,
            channelCount = channelCount,
            subslotBytesPerChannel = packing.bytesPerChannel,
            bitResolution = 24,
            bytesPerRuntimeFrame = packing.bytesPerChannel * channelCount,
            maxBytesPerServiceInterval = 4_096,
            servicePeriodNumeratorSeconds = 1L,
            servicePeriodDenominatorSeconds = 8_000L,
        )
        val evidence = if (packing == DoPCarrierPacking.PACKED_24_LE) {
            null
        } else {
            ProvenDoPPackingEvidence(packing)
        }
        val result = DsdCarrierPlanner.planDoP(source, pcm, evidence)
        assertTrue(result is DoPCarrierPlanningResult.Ready)
        return (result as DoPCarrierPlanningResult.Ready).plan
    }

    private class CollectingSink(
        override val bytesPerRuntimeFrame: Int,
        private val response: (offeredBytes: Int, call: Int) -> Int,
    ) : ExactCarrierFrameSink {
        private val output = ByteArrayOutputStream()
        val offeredCounts = mutableListOf<Int>()
        val acceptedCounts = mutableListOf<Int>()
        var callCount = 0
            private set

        override fun writeCarrierFrames(source: ByteArray, offset: Int, byteCount: Int): Int {
            callCount++
            offeredCounts += byteCount
            val accepted = response(byteCount, callCount)
            acceptedCounts += accepted
            if (accepted in 1..byteCount && accepted % bytesPerRuntimeFrame == 0) {
                output.write(source, offset, accepted)
            }
            return accepted
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    private class RandomAlignedSink(
        override val bytesPerRuntimeFrame: Int,
        seed: Long,
    ) : ExactCarrierFrameSink {
        private val random = Random(seed)
        private val output = ByteArrayOutputStream()
        var zeroAcceptCount = 0
            private set
        var shortAcceptCount = 0
            private set

        override fun writeCarrierFrames(source: ByteArray, offset: Int, byteCount: Int): Int {
            check(byteCount > 0 && byteCount % bytesPerRuntimeFrame == 0)
            val offeredFrames = byteCount / bytesPerRuntimeFrame
            if (random.nextInt(5) == 0) {
                zeroAcceptCount++
                return 0
            }
            val acceptedFrames = random.nextInt(offeredFrames) + 1
            if (acceptedFrames < offeredFrames) shortAcceptCount++
            val accepted = acceptedFrames * bytesPerRuntimeFrame
            output.write(source, offset, accepted)
            return accepted
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    private class DirectReference(
        private val plan: DoPCarrierPlan,
    ) {
        private val session = DoPCarrierSession(plan)
        private val output = ByteArrayOutputStream()
        private val buffer = ByteArray(plan.bytesPerRuntimeFrame * 64)

        fun content(source: ByteArray) {
            var offset = 0
            var guard = 0
            while (offset < source.size) {
                check(++guard < 10_000)
                val result = session.writeContentBytes(
                    source = source,
                    sourceOffset = offset,
                    sourceByteCount = source.size - offset,
                    destination = buffer,
                )
                if (result.carrierBytesEmitted > 0) {
                    output.write(buffer, 0, result.carrierBytesEmitted)
                }
                offset += result.canonicalBytesConsumed
                check(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
            }
            flushPackedTail()
        }

        fun gap(frameCount: Int) {
            var remaining = frameCount
            var guard = 0
            while (remaining > 0) {
                check(++guard < 10_000)
                val result = session.writeGapFrames(remaining, buffer)
                check(result.blockedReason == null)
                if (result.carrierBytesEmitted > 0) {
                    output.write(buffer, 0, result.carrierBytesEmitted)
                }
                remaining -= result.gapFramesAccepted
                check(result.gapFramesAccepted > 0 || result.carrierBytesEmitted > 0)
            }
            flushPackedTail()
        }

        fun resetSource(reason: DoPDiscontinuity) = session.resetSource(reason)

        fun accounting() = session.accounting()

        fun bytes(): ByteArray = output.toByteArray()

        private fun flushPackedTail() {
            var guard = 0
            while (session.accounting().pendingPackedCarrierBytes > 0) {
                check(++guard < 10_000)
                val result = session.flushCarrierOutput(buffer)
                check(result.carrierBytesEmitted > 0)
                output.write(buffer, 0, result.carrierBytesEmitted)
            }
        }
    }
}
