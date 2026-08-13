package com.mica.music.media.dsd

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoPCarrierSessionContinuityTest {

    @Test
    fun packed24StereoIdleUses69AndAlternatesMarkersForShortOddEvenAndLongRuns() {
        for (frameCount in listOf(1, 2, 3, 4, 257)) {
            val plan = packed24Plan(channelCount = 2)
            val session = DoPCarrierSession(plan)
            val output = emitIdle(session, frameCount, intArrayOf(1, 2, 5, 3, 7))

            assertEquals(frameCount * plan.bytesPerRuntimeFrame, output.size)
            assertIdlePayloadAndMarkers(
                payload = output,
                plan = plan,
                initialMarker = DoPEncoder.MARKER_A,
                frameCount = frameCount,
            )
            val accounting = session.accounting()
            assertEquals(0L, accounting.contentRuntimeFramesPacked)
            assertEquals(frameCount.toLong(), accounting.idleRuntimeFramesPacked)
            assertEquals(0L, accounting.contentCarrierBytesEmitted)
            assertEquals(output.size.toLong(), accounting.idleCarrierBytesEmitted)
            assertEquals(
                if (frameCount % 2 == 0) DoPEncoder.MARKER_A else DoPEncoder.MARKER_B,
                accounting.nextMarker,
            )
        }
    }

    @Test
    fun explicit32BitIdlePackingPreservesEvidenceChosenSlotPlacement() {
        for (packing in listOf(
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED,
            DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED,
        )) {
            val plan = explicit32Plan(packing, channelCount = 2)
            val session = DoPCarrierSession(plan)
            val output = emitIdle(session, frameCount = 5, outputChunkSizes = intArrayOf(1, 3, 2, 7))

            assertIdlePayloadAndMarkers(output, plan, DoPEncoder.MARKER_A, frameCount = 5)
            assertEquals(5L, session.accounting().idleRuntimeFramesPacked)
            assertEquals(0L, session.accounting().contentRuntimeFramesPacked)
        }
    }

    @Test
    fun contentIdleContentUsesOneContinuousMarkerSequenceForOddAndEvenIdleCounts() {
        for (idleCount in listOf(1, 2, 5, 6)) {
            val plan = packed24Plan(channelCount = 2)
            val session = DoPCarrierSession(plan)
            val output = ByteArrayOutputStream()
            output.write(emitContent(session, byteArrayOf(0x10, 0x20, 0x11, 0x21), intArrayOf(2, 1, 4)))
            output.write(emitIdle(session, idleCount, intArrayOf(1, 2, 3, 4)))
            output.write(emitContent(session, byteArrayOf(0x12, 0x22, 0x13, 0x23), intArrayOf(1, 5, 2)))

            val markers = markers(output.toByteArray(), plan)
            assertEquals(idleCount + 2, markers.size)
            assertAlternating(markers, DoPEncoder.MARKER_A)
            assertEquals(2L, session.accounting().contentRuntimeFramesPacked)
            assertEquals(idleCount.toLong(), session.accounting().idleRuntimeFramesPacked)
        }
    }

    @Test
    fun repeatedPauseLikeIdleCallsPreservePhaseAcrossFragmentedOutput() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val output = ByteArrayOutputStream()

        repeat(9) { call ->
            output.write(emitIdle(session, frameCount = 1 + (call % 3), outputChunkSizes = intArrayOf(1, 1, 2)))
        }

        val totalFrames = (0 until 9).sumOf { 1 + (it % 3) }
        val payload = output.toByteArray()
        assertEquals(totalFrames * plan.bytesPerRuntimeFrame, payload.size)
        assertAlternating(markers(payload, plan), DoPEncoder.MARKER_A)
        assertEquals(totalFrames.toLong(), session.accounting().idleRuntimeFramesPacked)
        assertEquals(payload.size.toLong(), session.accounting().idleCarrierBytesEmitted)
    }

    @Test
    fun pendingRealHalfFrameOneGapFrameCompletesMixedContentBeforePureIdle() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val firstHalf = byteArrayOf(0x10, 0x20)

        val first = session.writeContentBytes(firstHalf, destination = ByteArray(32))
        assertEquals(0, first.runtimeFramesPacked)
        assertTrue(session.accounting().hasPendingCanonicalHalfFrame)

        val destination = ByteArray(plan.bytesPerRuntimeFrame)
        val gap = session.writeGapFrames(frameCount = 1, destination = destination)

        assertEquals(1, gap.requestedGapFrames)
        assertEquals(1, gap.gapFramesAccepted)
        assertTrue(gap.completedPendingHalfFrameWithIdle)
        assertEquals(0, gap.pureIdleFramesPacked)
        assertEquals(plan.bytesPerRuntimeFrame, gap.carrierBytesEmitted)
        assertEquals(1, gap.runtimeFramesFullyEmitted)
        assertEquals(null, gap.blockedReason)
        assertArrayEquals(
            byteArrayOf(
                DSD_IDLE_BYTE, 0x10, 0x05,
                DSD_IDLE_BYTE, 0x20, 0x05,
            ),
            destination,
        )
        assertFalse(session.accounting().hasPendingCanonicalHalfFrame)
        assertEquals(1L, session.accounting().contentRuntimeFramesPacked)
        assertEquals(0L, session.accounting().idleRuntimeFramesPacked)
        assertEquals(DoPEncoder.MARKER_B, session.accounting().nextMarker)
    }

    @Test
    fun pendingRealHalfFrameGapTailIsChronologicalAndResumeStartsFresh() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val firstHalf = byteArrayOf(0x10, 0x20)
        session.writeContentBytes(firstHalf, destination = ByteArray(32))

        val destination = ByteArray(plan.bytesPerRuntimeFrame * 4)
        val gap = session.writeGapFrames(frameCount = 4, destination = destination)
        assertEquals(4, gap.gapFramesAccepted)
        assertTrue(gap.completedPendingHalfFrameWithIdle)
        assertEquals(3, gap.pureIdleFramesPacked)
        assertEquals(listOf(0x05, 0xFA, 0x05, 0xFA), markers(destination, plan))
        assertEquals(DSD_IDLE_BYTE, destination[0])
        assertEquals(0x10.toByte(), destination[1])
        assertEquals(DSD_IDLE_BYTE, destination[3])
        assertEquals(0x20.toByte(), destination[4])
        assertIdlePayloadAndMarkers(
            payload = destination.copyOfRange(plan.bytesPerRuntimeFrame, destination.size),
            plan = plan,
            initialMarker = DoPEncoder.MARKER_B,
            frameCount = 3,
        )

        val resumed = emitContent(
            session,
            byteArrayOf(0x11, 0x21, 0x12, 0x22),
            intArrayOf(1, 5, 2),
        )
        assertEquals(listOf(0x05), markers(resumed, plan))
        assertArrayEquals(
            byteArrayOf(
                0x12, 0x11, 0x05,
                0x22, 0x21, 0x05,
            ),
            resumed,
        )
        assertFalse(session.accounting().hasPendingCanonicalHalfFrame)
        assertEquals(2L, session.accounting().contentRuntimeFramesPacked)
        assertEquals(3L, session.accounting().idleRuntimeFramesPacked)
    }

    @Test
    fun pendingContentHalfFrameEofDrainThenIdleUsesExactNextMarker() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val firstHalf = byteArrayOf(0x10, 0x20)
        val scratch = ByteArray(32)
        val write = session.writeContentBytes(firstHalf, destination = scratch)
        assertEquals(firstHalf.size, write.canonicalBytesConsumed)
        assertEquals(0, write.runtimeFramesPacked)
        assertTrue(session.accounting().hasPendingCanonicalHalfFrame)

        val drained = finishSource(session, intArrayOf(1, 2, 1, 5))
        val idle = emitIdle(session, frameCount = 3, outputChunkSizes = intArrayOf(2, 1, 4))
        val payload = drained + idle

        val frameMarkers = markers(payload, plan)
        assertEquals(listOf(0x05, 0xFA, 0x05, 0xFA), frameMarkers)
        // Packed24 LE: newer byte, older byte, marker. EOF completes newer byte with 0x69.
        assertEquals(DSD_IDLE_BYTE, drained[0])
        assertEquals(0x10.toByte(), drained[1])
        assertEquals(DSD_IDLE_BYTE, drained[3])
        assertEquals(0x20.toByte(), drained[4])
        assertEquals(1L, session.accounting().contentRuntimeFramesPacked)
        assertEquals(3L, session.accounting().idleRuntimeFramesPacked)
    }

    @Test
    fun retainedCarrierSeekDiscardsStaleSourceStateWithoutRestartingMarker() {
        val plan = packed24Plan(channelCount = 3)
        val session = DoPCarrierSession(plan)
        val output = ByteArrayOutputStream()

        output.write(emitContent(
            session,
            byteArrayOf(
                0x10, 0x20, 0x30,
                0x11, 0x21, 0x31,
            ),
            intArrayOf(2, 1, 8),
        ))
        assertEquals(DoPEncoder.MARKER_B, session.accounting().nextMarker)

        // One complete canonical frame becomes the DoP half-frame; one more byte is a partial 3ch frame.
        val stale = byteArrayOf(0x40, 0x50, 0x60, 0x41)
        val staleResult = session.writeContentBytes(stale, destination = ByteArray(32))
        assertEquals(stale.size, staleResult.canonicalBytesConsumed)
        assertTrue(session.accounting().hasPendingCanonicalHalfFrame)
        assertEquals(1, session.accounting().pendingPartialCanonicalFrameBytes)

        val reset = session.resetSource(DoPDiscontinuity.SEEK)
        assertEquals(1, reset.discardedPartialCanonicalFrameBytes)
        assertTrue(reset.discardedPendingCanonicalHalfFrame)
        assertEquals(4, reset.discardedCanonicalSourceBytes)
        assertEquals(DoPEncoder.MARKER_B, reset.markerBeforeReset)
        assertEquals(DoPEncoder.MARKER_B, reset.markerAfterReset)
        assertFalse(session.accounting().hasPendingCanonicalHalfFrame)
        assertEquals(0, session.accounting().pendingPartialCanonicalFrameBytes)

        output.write(emitContent(
            session,
            byteArrayOf(
                0x12, 0x22, 0x32,
                0x13, 0x23, 0x33,
            ),
            intArrayOf(1, 2, 7),
        ))
        assertEquals(listOf(0x05, 0xFA), markers(output.toByteArray(), plan))
        assertEquals(4L, session.accounting().canonicalSourceBytesDiscardedAtReset)
    }

    @Test
    fun sameCarrierNextSourcePreservesPhaseButExplicitReconfigureRestarts05() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val output = ByteArrayOutputStream()

        output.write(emitIdle(session, 1, intArrayOf(1, 4))) // 05 -> next FA
        val sourceReset = session.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)
        assertEquals(DoPEncoder.MARKER_B, sourceReset.markerAfterReset)
        output.write(emitContent(session, byteArrayOf(1, 11, 2, 12), intArrayOf(2, 3))) // FA
        assertEquals(listOf(0x05, 0xFA), markers(output.toByteArray(), plan))

        val carrierReset = session.resetCarrier(DoPCarrierSessionReset.RECONFIGURE)
        assertEquals(DoPEncoder.MARKER_A, carrierReset.markerAfterReset)
        val afterReconfigure = emitIdle(session, 1, intArrayOf(1, 2, 4))
        assertEquals(listOf(0x05), markers(afterReconfigure, plan))
        assertEquals(DoPEncoder.MARKER_B, session.accounting().nextMarker)
        assertEquals(DoPEncoder.MARKER_A, session.accounting().lastPackedMarker)
    }

    @Test
    fun retainedSourceResetDiscardsPendingPackedBytesButDoesNotSilentlyRestartPhase() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val source = byteArrayOf(0x10, 0x20, 0x11, 0x21)
        val oneByte = ByteArray(1)
        val write = session.writeContentBytes(source, destination = oneByte)
        assertEquals(1, write.carrierBytesEmitted)
        assertEquals(plan.bytesPerRuntimeFrame - 1, session.accounting().pendingPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_B, session.accounting().nextMarker)

        val reset = session.resetSource(DoPDiscontinuity.SEEK)
        assertEquals(plan.bytesPerRuntimeFrame - 1, reset.discardedPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_B, reset.markerBeforeReset)
        assertEquals(DoPEncoder.MARKER_B, reset.markerAfterReset)
        assertEquals(0, session.accounting().pendingPackedCarrierBytes)
    }

    @Test
    fun retainedSourceResetPreservesPendingIdleCarrierBecauseItDoesNotBelongToOldSource() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val firstByte = ByteArray(1)
        val idleStart = session.writeGapFrames(frameCount = 1, destination = firstByte)
        assertEquals(1, idleStart.gapFramesAccepted)
        assertEquals(1, idleStart.carrierBytesEmitted)
        assertEquals(plan.bytesPerRuntimeFrame - 1, session.accounting().pendingPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_B, session.accounting().nextMarker)

        val reset = session.resetSource(DoPDiscontinuity.NEW_SOURCE_GENERATION)
        assertEquals(0, reset.discardedPackedCarrierBytes)
        assertEquals(plan.bytesPerRuntimeFrame - 1, session.accounting().pendingPackedCarrierBytes)
        assertEquals(DoPEncoder.MARKER_B, reset.markerAfterReset)

        val remainder = emitIdle(session, frameCount = 0, outputChunkSizes = intArrayOf(1, 2, 4))
        val wholeIdleFrame = firstByte + remainder
        assertIdlePayloadAndMarkers(wholeIdleFrame, plan, DoPEncoder.MARKER_A, frameCount = 1)

        val content = emitContent(session, byteArrayOf(1, 11, 2, 12), intArrayOf(1, 3, 5))
        assertEquals(listOf(0xFA), markers(content, plan))
    }

    @Test
    fun partialCanonicalFrameBlocksGapUntilExplicitRetainedCarrierSourceReset() {
        val plan = packed24Plan(channelCount = 3)
        val session = DoPCarrierSession(plan)
        val source = byteArrayOf(0x10, 0x20)
        val write = session.writeContentBytes(source, destination = ByteArray(32))
        assertEquals(source.size, write.canonicalBytesConsumed)
        assertEquals(0, write.canonicalFramesCompleted)
        assertEquals(2, session.accounting().pendingPartialCanonicalFrameBytes)

        val beforeGap = session.accounting()
        val destination = ByteArray(plan.bytesPerRuntimeFrame * 3) { 0x7E }
        val blocked = session.writeGapFrames(frameCount = 3, destination = destination)
        assertEquals(3, blocked.requestedGapFrames)
        assertEquals(0, blocked.gapFramesAccepted)
        assertFalse(blocked.completedPendingHalfFrameWithIdle)
        assertEquals(0, blocked.pureIdleFramesPacked)
        assertEquals(0, blocked.carrierBytesEmitted)
        assertEquals(0, blocked.runtimeFramesFullyEmitted)
        assertEquals(DoPGapBlockedReason.PARTIAL_CANONICAL_FRAME, blocked.blockedReason)
        assertEquals(beforeGap, session.accounting())
        assertTrue(destination.all { it == 0x7E.toByte() })

        val reset = session.resetSource(DoPDiscontinuity.SEEK)
        assertEquals(2, reset.discardedPartialCanonicalFrameBytes)
        assertEquals(2, reset.discardedCanonicalSourceBytes)
        assertEquals(DoPEncoder.MARKER_A, reset.markerAfterReset)

        val afterReset = ByteArray(plan.bytesPerRuntimeFrame * 2)
        val gap = session.writeGapFrames(frameCount = 2, destination = afterReset)
        assertEquals(2, gap.gapFramesAccepted)
        assertFalse(gap.completedPendingHalfFrameWithIdle)
        assertEquals(2, gap.pureIdleFramesPacked)
        assertIdlePayloadAndMarkers(afterReset, plan, DoPEncoder.MARKER_A, frameCount = 2)
    }

    @Test
    fun pendingPackedContentFinishesBeforeAnyNewGapIdle() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val source = byteArrayOf(0x10, 0x20, 0x11, 0x21)
        val firstByte = ByteArray(1)
        val write = session.writeContentBytes(source, destination = firstByte)
        assertEquals(1, write.runtimeFramesPacked)
        assertEquals(1, write.carrierBytesEmitted)
        assertEquals(plan.bytesPerRuntimeFrame - 1, session.accounting().pendingPackedCarrierBytes)

        val tailAndGap = ByteArray(plan.bytesPerRuntimeFrame * 2 - 1)
        val gap = session.writeGapFrames(frameCount = 1, destination = tailAndGap)
        assertEquals(1, gap.gapFramesAccepted)
        assertEquals(1, gap.pureIdleFramesPacked)
        assertFalse(gap.completedPendingHalfFrameWithIdle)
        assertEquals(2, gap.runtimeFramesFullyEmitted)

        val payload = firstByte + tailAndGap
        assertEquals(listOf(0x05, 0xFA), markers(payload, plan))
        assertArrayEquals(
            byteArrayOf(
                0x11, 0x10, 0x05,
                0x21, 0x20, 0x05,
            ),
            payload.copyOfRange(0, plan.bytesPerRuntimeFrame),
        )
        assertIdlePayloadAndMarkers(
            payload.copyOfRange(plan.bytesPerRuntimeFrame, payload.size),
            plan,
            DoPEncoder.MARKER_B,
            frameCount = 1,
        )
    }

    @Test
    fun pendingPackedIdleFinishesBeforeNextGapFrameWithContinuousMarker() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val firstByte = ByteArray(1)
        val first = session.writeGapFrames(frameCount = 1, destination = firstByte)
        assertEquals(1, first.gapFramesAccepted)
        assertEquals(1, first.pureIdleFramesPacked)
        assertEquals(plan.bytesPerRuntimeFrame - 1, session.accounting().pendingPackedCarrierBytes)

        val tailAndNext = ByteArray(plan.bytesPerRuntimeFrame * 2 - 1)
        val second = session.writeGapFrames(frameCount = 1, destination = tailAndNext)
        assertEquals(1, second.gapFramesAccepted)
        assertEquals(1, second.pureIdleFramesPacked)
        assertEquals(2, second.runtimeFramesFullyEmitted)

        val payload = firstByte + tailAndNext
        assertIdlePayloadAndMarkers(payload, plan, DoPEncoder.MARKER_A, frameCount = 2)
    }

    @Test
    fun zeroFrameGapIsStrictNoOpEvenWithPendingPackedContent() {
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val source = byteArrayOf(0x10, 0x20, 0x11, 0x21)
        session.writeContentBytes(source, destination = ByteArray(1))
        val before = session.accounting()
        val destination = ByteArray(32) { 0x55 }

        val result = session.writeGapFrames(frameCount = 0, destination = destination)

        assertEquals(0, result.requestedGapFrames)
        assertEquals(0, result.gapFramesAccepted)
        assertEquals(0, result.carrierBytesEmitted)
        assertEquals(null, result.blockedReason)
        assertEquals(before, session.accounting())
        assertTrue(destination.all { it == 0x55.toByte() })
    }

    @Test
    fun mixedGapCompletionRemainsByteExactForBothProven32BitPlacements() {
        for (packing in listOf(
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED,
            DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED,
        )) {
            val plan = explicit32Plan(packing, channelCount = 2)
            val session = DoPCarrierSession(plan)
            session.writeContentBytes(byteArrayOf(0x10, 0x20), destination = ByteArray(32))
            val output = ByteArray(plan.bytesPerRuntimeFrame)
            val gap = session.writeGapFrames(frameCount = 1, destination = output)
            assertTrue(gap.completedPendingHalfFrameWithIdle)
            assertEquals(0, gap.pureIdleFramesPacked)
            assertEquals(listOf(DoPEncoder.MARKER_A), markers(output, plan))

            for (channel in 0 until 2) {
                val base = channel * packing.bytesPerChannel
                val real = if (channel == 0) 0x10.toByte() else 0x20.toByte()
                when (packing) {
                    DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED -> {
                        assertEquals(0, output[base].toInt())
                        assertEquals(DSD_IDLE_BYTE, output[base + 1])
                        assertEquals(real, output[base + 2])
                        assertEquals(DoPEncoder.MARKER_A, output[base + 3].toInt() and 0xff)
                    }
                    DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED -> {
                        assertEquals(DSD_IDLE_BYTE, output[base])
                        assertEquals(real, output[base + 1])
                        assertEquals(DoPEncoder.MARKER_A, output[base + 2].toInt() and 0xff)
                        assertEquals(0, output[base + 3].toInt())
                    }
                    DoPCarrierPacking.PACKED_24_LE -> error("not a 32-bit packing")
                }
            }
        }
    }

    @Test
    fun fixedSeedMoreThanTenThousandCarrierFramesHaveNoMarkerSkipsAcrossContentIdleAndSourceResets() {
        val random = Random(0xD05E5510L)
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val payload = ByteArrayOutputStream()
        var expectedMarker = DoPEncoder.MARKER_A
        var emittedFrames = 0
        var sourceResets = 0

        repeat(12_500) { index ->
            when (random.nextInt(10)) {
                0 -> {
                    // Discard a real pending DoP half-frame. Marker must not move.
                    val half = byteArrayOf(random.nextInt(256).toByte(), random.nextInt(256).toByte())
                    val sink = ByteArray(32)
                    val result = session.writeContentBytes(half, destination = sink)
                    assertEquals(0, result.runtimeFramesPacked)
                    val reset = session.resetSource(
                        if (index % 2 == 0) DoPDiscontinuity.SEEK else DoPDiscontinuity.NEW_SOURCE_GENERATION,
                    )
                    assertTrue(reset.discardedPendingCanonicalHalfFrame)
                    assertEquals(expectedMarker, reset.markerAfterReset)
                    sourceResets++
                }
                1 -> {
                    // Discard an incomplete multichannel canonical frame before it reaches encoder.
                    val partial = byteArrayOf(random.nextInt(256).toByte())
                    val result = session.writeContentBytes(partial, destination = ByteArray(32))
                    assertEquals(0, result.canonicalFramesCompleted)
                    val reset = session.resetSource(DoPDiscontinuity.SEEK)
                    assertEquals(1, reset.discardedPartialCanonicalFrameBytes)
                    assertEquals(expectedMarker, reset.markerAfterReset)
                    sourceResets++
                }
                in 2..5 -> {
                    val content = byteArrayOf(
                        random.nextInt(256).toByte(), random.nextInt(256).toByte(),
                        random.nextInt(256).toByte(), random.nextInt(256).toByte(),
                    )
                    val bytes = emitContentRandomlyFragmented(session, content, random)
                    payload.write(bytes)
                    assertEquals(expectedMarker, markers(bytes, plan).single())
                    expectedMarker = toggle(expectedMarker)
                    emittedFrames++
                }
                else -> {
                    val bytes = emitIdleRandomlyFragmented(session, random)
                    payload.write(bytes)
                    assertEquals(expectedMarker, markers(bytes, plan).single())
                    expectedMarker = toggle(expectedMarker)
                    emittedFrames++
                }
            }
        }

        assertTrue(emittedFrames > 10_000)
        val allMarkers = markers(payload.toByteArray(), plan)
        assertEquals(emittedFrames, allMarkers.size)
        assertAlternating(allMarkers, DoPEncoder.MARKER_A)
        assertEquals(expectedMarker, session.accounting().nextMarker)
        assertEquals(
            session.accounting().runtimeFramesPacked,
            session.accounting().contentRuntimeFramesPacked + session.accounting().idleRuntimeFramesPacked,
        )
        assertEquals(
            session.accounting().carrierBytesEmitted,
            session.accounting().contentCarrierBytesEmitted + session.accounting().idleCarrierBytesEmitted,
        )
        assertTrue(sourceResets > 1_000)
        assertTrue(session.accounting().canonicalSourceBytesDiscardedAtReset > 0L)
    }

    @Test
    fun fixedSeedGapProjectionPreservesMarkersAndRealSourceChronologyBeyondTenThousandFrames() {
        val random = Random(0xD05E6008L)
        val plan = packed24Plan(channelCount = 2)
        val session = DoPCarrierSession(plan)
        val payload = ByteArrayOutputStream()
        val expectedRealSource = ByteArrayOutputStream()
        var pendingExpectedCanonicalFrame: ByteArray? = null

        repeat(12_000) { index ->
            when (random.nextInt(10)) {
                0, 1 -> {
                    val reset = session.resetSource(
                        if (index % 2 == 0) DoPDiscontinuity.SEEK else DoPDiscontinuity.NEW_SOURCE_GENERATION,
                    )
                    if (pendingExpectedCanonicalFrame != null) {
                        assertTrue(reset.discardedPendingCanonicalHalfFrame)
                        pendingExpectedCanonicalFrame = null
                    } else {
                        assertFalse(reset.discardedPendingCanonicalHalfFrame)
                    }
                }
                in 2..5 -> {
                    val frameCount = 1 + random.nextInt(3)
                    val source = ByteArray(frameCount * plan.channelCount) { nextProjectionSourceByte(random) }
                    for (frame in 0 until frameCount) {
                        val canonicalFrame = source.copyOfRange(
                            frame * plan.channelCount,
                            (frame + 1) * plan.channelCount,
                        )
                        val pending = pendingExpectedCanonicalFrame
                        if (pending == null) {
                            pendingExpectedCanonicalFrame = canonicalFrame
                        } else {
                            expectedRealSource.write(pending)
                            expectedRealSource.write(canonicalFrame)
                            pendingExpectedCanonicalFrame = null
                        }
                    }
                    payload.write(emitContentRandomlyFragmented(session, source, random))
                }
                else -> {
                    val gapFrames = 1 + random.nextInt(3)
                    pendingExpectedCanonicalFrame?.let {
                        expectedRealSource.write(it)
                        pendingExpectedCanonicalFrame = null
                    }
                    payload.write(emitGapRandomlyFragmented(session, gapFrames, random))
                }
            }
        }

        pendingExpectedCanonicalFrame?.let {
            expectedRealSource.write(it)
            pendingExpectedCanonicalFrame = null
            payload.write(emitGapRandomlyFragmented(session, frameCount = 1, random = random))
        }

        val carrier = payload.toByteArray()
        val projectedMarkers = markers(carrier, plan)
        assertTrue(projectedMarkers.size > 10_000)
        assertAlternating(projectedMarkers, DoPEncoder.MARKER_A)
        assertArrayEquals(expectedRealSource.toByteArray(), realCanonicalSourceChronology(carrier, plan))
        assertEquals(0, session.accounting().pendingPartialCanonicalFrameBytes)
        assertFalse(session.accounting().hasPendingCanonicalHalfFrame)
    }

    private fun emitContent(
        session: DoPCarrierSession,
        source: ByteArray,
        outputChunkSizes: IntArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var sourceOffset = 0
        var chunkIndex = 0
        while (sourceOffset < source.size || session.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(outputChunkSizes[chunkIndex++ % outputChunkSizes.size])
            val remaining = source.size - sourceOffset
            val result = session.writeContentBytes(
                source = source,
                sourceOffset = sourceOffset,
                sourceByteCount = remaining,
                destination = destination,
            )
            out.write(destination, 0, result.carrierBytesEmitted)
            sourceOffset += result.canonicalBytesConsumed
            assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
        }
        return out.toByteArray()
    }

    private fun emitContentRandomlyFragmented(
        session: DoPCarrierSession,
        source: ByteArray,
        random: Random,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var sourceOffset = 0
        while (sourceOffset < source.size || session.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(1 + random.nextInt(session.plan.bytesPerRuntimeFrame + 2))
            val remaining = source.size - sourceOffset
            val requested = if (remaining == 0) 0 else minOf(1 + random.nextInt(remaining), remaining)
            val result = session.writeContentBytes(
                source = source,
                sourceOffset = sourceOffset,
                sourceByteCount = requested,
                destination = destination,
            )
            out.write(destination, 0, result.carrierBytesEmitted)
            sourceOffset += result.canonicalBytesConsumed
            assertTrue(result.canonicalBytesConsumed > 0 || result.carrierBytesEmitted > 0)
        }
        return out.toByteArray()
    }

    private fun emitIdle(
        session: DoPCarrierSession,
        frameCount: Int,
        outputChunkSizes: IntArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = frameCount
        var chunkIndex = 0
        while (remaining > 0 || session.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(outputChunkSizes[chunkIndex++ % outputChunkSizes.size])
            if (remaining > 0) {
                val result = session.writeGapFrames(remaining, destination)
                assertEquals(null, result.blockedReason)
                out.write(destination, 0, result.carrierBytesEmitted)
                remaining -= result.gapFramesAccepted
                assertTrue(result.gapFramesAccepted > 0 || result.carrierBytesEmitted > 0)
            } else {
                val result = session.flushCarrierOutput(destination)
                out.write(destination, 0, result.carrierBytesEmitted)
                assertTrue(result.carrierBytesEmitted > 0)
            }
        }
        return out.toByteArray()
    }

    private fun emitIdleRandomlyFragmented(session: DoPCarrierSession, random: Random): ByteArray =
        emitGapRandomlyFragmented(session, frameCount = 1, random = random)

    private fun emitGapRandomlyFragmented(
        session: DoPCarrierSession,
        frameCount: Int,
        random: Random,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = frameCount
        while (remaining > 0 || session.accounting().pendingPackedCarrierBytes > 0) {
            val destination = ByteArray(1 + random.nextInt(session.plan.bytesPerRuntimeFrame + 2))
            if (remaining > 0) {
                val result = session.writeGapFrames(remaining, destination)
                assertEquals(null, result.blockedReason)
                out.write(destination, 0, result.carrierBytesEmitted)
                remaining -= result.gapFramesAccepted
                assertTrue(result.gapFramesAccepted > 0 || result.carrierBytesEmitted > 0)
            } else {
                val result = session.flushCarrierOutput(destination)
                out.write(destination, 0, result.carrierBytesEmitted)
                assertTrue(result.carrierBytesEmitted > 0)
            }
        }
        return out.toByteArray()
    }

    private fun nextProjectionSourceByte(random: Random): Byte {
        var value = random.nextInt(256)
        if (value == (DSD_IDLE_BYTE.toInt() and 0xff)) value = 0x68
        return value.toByte()
    }

    private fun realCanonicalSourceChronology(payload: ByteArray, plan: DoPCarrierPlan): ByteArray {
        require(plan.packing == DoPCarrierPacking.PACKED_24_LE)
        require(payload.size % plan.bytesPerRuntimeFrame == 0)
        val source = ByteArrayOutputStream()
        var frameOffset = 0
        while (frameOffset < payload.size) {
            var olderIsIdle = true
            var newerIsIdle = true
            val older = ByteArray(plan.channelCount)
            val newer = ByteArray(plan.channelCount)
            for (channel in 0 until plan.channelCount) {
                val base = frameOffset + channel * plan.packing.bytesPerChannel
                newer[channel] = payload[base]
                older[channel] = payload[base + 1]
                olderIsIdle = olderIsIdle && older[channel] == DSD_IDLE_BYTE
                newerIsIdle = newerIsIdle && newer[channel] == DSD_IDLE_BYTE
            }
            if (!(olderIsIdle && newerIsIdle)) {
                assertFalse("content/mixed frame cannot have idle as the older canonical frame", olderIsIdle)
                source.write(older)
                if (!newerIsIdle) source.write(newer)
            }
            frameOffset += plan.bytesPerRuntimeFrame
        }
        return source.toByteArray()
    }

    private fun finishSource(session: DoPCarrierSession, outputChunkSizes: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        var chunkIndex = 0
        while (session.hasPendingOutputOrCarry()) {
            val destination = ByteArray(outputChunkSizes[chunkIndex++ % outputChunkSizes.size])
            val result = session.finishSource(destination)
            out.write(destination, 0, result.carrierBytesEmitted)
            assertTrue(result.carrierBytesEmitted > 0 || result.completedPendingHalfFrameWithIdle)
        }
        return out.toByteArray()
    }

    private fun markers(payload: ByteArray, plan: DoPCarrierPlan): List<Int> {
        require(payload.size % plan.bytesPerRuntimeFrame == 0)
        val markerOffset = when (plan.packing) {
            DoPCarrierPacking.PACKED_24_LE -> 2
            DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED -> 3
            DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED -> 2
        }
        val markers = ArrayList<Int>(payload.size / plan.bytesPerRuntimeFrame)
        var frameOffset = 0
        while (frameOffset < payload.size) {
            val marker = payload[frameOffset + markerOffset].toInt() and 0xff
            for (channel in 1 until plan.channelCount) {
                assertEquals(
                    marker,
                    payload[frameOffset + channel * plan.packing.bytesPerChannel + markerOffset].toInt() and 0xff,
                )
            }
            markers += marker
            frameOffset += plan.bytesPerRuntimeFrame
        }
        return markers
    }

    private fun assertIdlePayloadAndMarkers(
        payload: ByteArray,
        plan: DoPCarrierPlan,
        initialMarker: Int,
        frameCount: Int,
    ) {
        assertEquals(frameCount * plan.bytesPerRuntimeFrame, payload.size)
        var expectedMarker = initialMarker
        for (frame in 0 until frameCount) {
            val frameOffset = frame * plan.bytesPerRuntimeFrame
            for (channel in 0 until plan.channelCount) {
                val offset = frameOffset + channel * plan.packing.bytesPerChannel
                when (plan.packing) {
                    DoPCarrierPacking.PACKED_24_LE -> {
                        assertEquals(DSD_IDLE_BYTE, payload[offset])
                        assertEquals(DSD_IDLE_BYTE, payload[offset + 1])
                        assertEquals(expectedMarker, payload[offset + 2].toInt() and 0xff)
                    }
                    DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED -> {
                        assertEquals(0, payload[offset].toInt())
                        assertEquals(DSD_IDLE_BYTE, payload[offset + 1])
                        assertEquals(DSD_IDLE_BYTE, payload[offset + 2])
                        assertEquals(expectedMarker, payload[offset + 3].toInt() and 0xff)
                    }
                    DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED -> {
                        assertEquals(DSD_IDLE_BYTE, payload[offset])
                        assertEquals(DSD_IDLE_BYTE, payload[offset + 1])
                        assertEquals(expectedMarker, payload[offset + 2].toInt() and 0xff)
                        assertEquals(0, payload[offset + 3].toInt())
                    }
                }
            }
            expectedMarker = toggle(expectedMarker)
        }
    }

    private fun assertAlternating(markers: List<Int>, initialMarker: Int) {
        var expected = initialMarker
        for (marker in markers) {
            assertEquals(expected, marker)
            expected = toggle(expected)
        }
    }

    private fun toggle(marker: Int): Int =
        if (marker == DoPEncoder.MARKER_A) DoPEncoder.MARKER_B else DoPEncoder.MARKER_A

    private fun packed24Plan(channelCount: Int): DoPCarrierPlan = plan(
        packing = DoPCarrierPacking.PACKED_24_LE,
        channelCount = channelCount,
    )

    private fun explicit32Plan(packing: DoPCarrierPacking, channelCount: Int): DoPCarrierPlan =
        plan(packing = packing, channelCount = channelCount)

    private fun plan(packing: DoPCarrierPacking, channelCount: Int): DoPCarrierPlan {
        val source = DsdCarrierSourceFacts(dsdBitRateHz = 2_822_400L, channelCount = channelCount)
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
}
