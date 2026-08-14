package com.mica.music.media.dsd

import com.mica.music.media.dsf.DsfExtractorPacketFacts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdRendererPumpTest {
    private val facts = DsfExtractorPacketFacts(
        sourceSampleRateHz = 5_644_800,
        channelCount = 2,
        sourceBitOrder = DsdSourceBitOrder.LSB_FIRST,
    )

    @Test
    fun zeroAndShortConsumptionRetainsExactCanonicalTailAndNeverDuplicates() {
        val transport = FakeSession(facts, intArrayOf(0, 2, 0, 3, 99))
        val pump = DirectDsdRendererPump(facts, transport)
        val planar = byteArrayOf(
            reverse(0x80.toByte()), reverse(0x81.toByte()), reverse(0x82.toByte()),
            reverse(0x40), reverse(0x41), reverse(0x42),
        )
        val expected = byteArrayOf(
            0x80.toByte(), 0x40,
            0x81.toByte(), 0x41,
            0x82.toByte(), 0x42,
        )

        pump.offerExtractorPacket(planar, timeUs = 12_345)
        assertFalse(pump.canAcceptPacket())
        assertEquals(0, pump.pump().canonicalBytesConsumed)
        assertEquals(2, pump.pump().canonicalBytesConsumed)
        assertEquals(0, pump.pump().canonicalBytesConsumed)
        assertEquals(3, pump.pump().canonicalBytesConsumed)
        assertEquals(1, pump.pump().canonicalBytesConsumed)

        assertTrue(pump.canAcceptPacket())
        assertArrayEquals(expected, transport.committed.toByteArray())
        assertEquals(5, transport.writeCalls)
        val snapshot = pump.snapshot()
        assertEquals(0, snapshot.pendingCanonicalBytes)
        assertEquals(1L, snapshot.offeredPacketCount)
        assertEquals(1L, snapshot.fullyConsumedPacketCount)
        assertEquals(expected.size.toLong(), snapshot.canonicalBytesCommitted)
        assertEquals(12_345L, snapshot.lastFullyConsumedPacketTimeUs)
    }

    @Test
    fun secondPacketCannotOvertakePendingCanonicalTail() {
        val transport = FakeSession(facts, intArrayOf(1))
        val pump = DirectDsdRendererPump(facts, transport)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1)
        pump.pump()

        var failed = false
        try {
            pump.offerExtractorPacket(byteArrayOf(reverse(0x11), reverse(0x21)), timeUs = 2)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(1, pump.snapshot().pendingCanonicalBytes)
    }

    @Test
    fun startupPrefillBecomesReadyWithoutArmThenExplicitStartArmsOnce() {
        val transport = FakeSession(
            facts,
            limits = intArrayOf(2, 99),
            startupReadyAfterBytes = 6,
        )
        val pump = DirectDsdRendererPump(facts, transport)
        pump.offerExtractorPacket(
            byteArrayOf(
                reverse(0x10), reverse(0x11), reverse(0x12),
                reverse(0x20), reverse(0x21), reverse(0x22),
            ),
            timeUs = 33,
        )

        assertFalse(pump.isStartupPrefillReady())
        assertFalse(pump.isPlaybackArmed())
        assertEquals(0, transport.armCalls)
        assertEquals(2, pump.pump().canonicalBytesConsumed)
        assertFalse(pump.isStartupPrefillReady())
        assertEquals(0, transport.armCalls)

        assertEquals(4, pump.pump().canonicalBytesConsumed)
        assertTrue(pump.isStartupPrefillReady())
        assertFalse(pump.isPlaybackArmed())
        assertEquals(0, transport.armCalls)

        pump.armPlayback()
        assertTrue(pump.isPlaybackArmed())
        assertEquals(1, transport.armCalls)
    }
    @Test
    fun armedPumpPauseGapRetainsPendingPacketAndResumeDrainsExactTail() {
        val transport = FakeSession(facts, intArrayOf(1, 99), startupReadyAfterBytes = 0)
        val pump = DirectDsdRendererPump(facts, transport)
        assertTrue(pump.isStartupPrefillReady())
        assertFalse(pump.isPlaybackArmed())

        pump.armPlayback()
        assertTrue(pump.isPlaybackArmed())
        assertEquals(1, transport.armCalls)

        val packet = byteArrayOf(reverse(0x10), reverse(0x20))
        pump.offerExtractorPacket(packet, timeUs = 44)
        assertEquals(1, pump.pump().canonicalBytesConsumed)
        assertEquals(1, pump.snapshot().pendingCanonicalBytes)

        pump.startPauseGapLiveness()
        assertTrue(transport.gapActive)
        assertEquals(1, transport.gapStartCalls)
        assertEquals(1, pump.snapshot().pendingCanonicalBytes)
        assertEquals(1, transport.writeCalls)

        pump.stopPauseGapLiveness()
        assertFalse(transport.gapActive)
        assertEquals(1, transport.gapStopCalls)
        assertEquals(1, pump.pump().canonicalBytesConsumed)
        assertEquals(0, pump.snapshot().pendingCanonicalBytes)
        assertArrayEquals(byteArrayOf(0x10, 0x20), transport.committed.toByteArray())
    }

    @Test
    fun rebuildQuiesceIsNoOpForContentAndStopsOnlyActiveGap() {
        val transport = FakeSession(facts, intArrayOf(), startupReadyAfterBytes = 0)
        val pump = DirectDsdRendererPump(facts, transport)
        pump.armPlayback()

        assertFalse(pump.quiescePauseGapForOutputRebuild())
        assertEquals(0, transport.gapStopCalls)

        pump.startPauseGapLiveness()
        assertTrue(transport.gapActive)
        assertTrue(pump.quiescePauseGapForOutputRebuild())
        assertFalse(transport.gapActive)
        assertEquals(1, transport.gapStopCalls)
    }

    @Test
    fun positionResetCloseDiscardsRendererPendingTailBeforeFreshPumpAcceptsTargetPacket() {
        val oldTransport = FakeSession(facts, intArrayOf(1), startupReadyAfterBytes = 0)
        val oldPump = DirectDsdRendererPump(facts, oldTransport)
        oldPump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 10)
        assertEquals(1, oldPump.pump().canonicalBytesConsumed)
        assertEquals(1, oldPump.snapshot().pendingCanonicalBytes)

        oldPump.close()
        assertEquals(1, oldTransport.closeCalls)
        assertArrayEquals(byteArrayOf(0x10), oldTransport.committed.toByteArray())

        val targetTransport = FakeSession(facts, intArrayOf(99), startupReadyAfterBytes = 0)
        val freshPump = DirectDsdRendererPump(facts, targetTransport)
        freshPump.offerExtractorPacket(byteArrayOf(reverse(0x30), reverse(0x40)), timeUs = 45_000_000)
        assertEquals(2, freshPump.pump().canonicalBytesConsumed)
        assertEquals(0, freshPump.snapshot().pendingCanonicalBytes)
        assertArrayEquals(byteArrayOf(0x30, 0x40), targetTransport.committed.toByteArray())
    }

    @Test
    fun closeAfterPauseResumeIsIdempotent() {
        val transport = FakeSession(facts, intArrayOf(), startupReadyAfterBytes = 0)
        val pump = DirectDsdRendererPump(facts, transport)
        pump.armPlayback()
        pump.startPauseGapLiveness()
        pump.stopPauseGapLiveness()

        pump.close()
        pump.close()
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun endRequiresTailDrainedAndSessionCleanThenCloseIsIdempotent() {
        val transport = FakeSession(facts, intArrayOf(0, 99), finishResults = ArrayDeque(listOf(false, true)))
        val pump = DirectDsdRendererPump(facts, transport)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 7)

        assertFalse(pump.signalEndOfStream())
        pump.pump()
        assertFalse(pump.signalEndOfStream())
        pump.pump()
        assertFalse(pump.signalEndOfStream())
        assertTrue(pump.signalEndOfStream())
        assertTrue(pump.isEnded())

        pump.close()
        pump.close()
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun retainedTrackTransitionDropsOnlyRendererOwnedTailAndRebindsFreshSourceFacts() {
        val transport = FakeSession(facts, intArrayOf(1, 99))
        val pump = DirectDsdRendererPump(facts, transport)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1)
        assertEquals(1, pump.pump().canonicalBytesConsumed)
        assertEquals(1, pump.snapshot().pendingCanonicalBytes)

        val newFacts = facts.copy(sourceBitOrder = DsdSourceBitOrder.MSB_FIRST)
        val (discarded, transition) = pump.transitionRetainedSource(newFacts)

        assertEquals(1, discarded)
        assertTrue(transition.feederPendingZero)
        assertTrue(transition.sourceResetApplied)
        assertEquals(1, transport.transitionCalls)
        assertEquals(newFacts, transport.lastTransitionFacts)
        assertEquals(newFacts, pump.facts)
        assertEquals(0, pump.snapshot().pendingCanonicalBytes)
        assertEquals(0L, pump.snapshot().offeredPacketCount)
        assertEquals(0L, pump.snapshot().canonicalBytesCommitted)

        pump.offerExtractorPacket(byteArrayOf(0x31, 0x41), timeUs = 2)
        assertEquals(2, pump.pump().canonicalBytesConsumed)
        assertArrayEquals(byteArrayOf(0x10, 0x31, 0x41), transport.committed.toByteArray())
    }

    @Test
    fun retainedTrackTransitionReopensPumpAfterCleanOldSourceEos() {
        val transport = FakeSession(facts, intArrayOf(99))
        val pump = DirectDsdRendererPump(facts, transport)
        assertTrue(pump.signalEndOfStream())
        assertTrue(pump.isEnded())

        val (discarded, transition) = pump.transitionRetainedSource(facts.copy())

        assertEquals(0, discarded)
        assertTrue(transition.sourceResetApplied)
        assertFalse(pump.isEnded())
        assertTrue(pump.canAcceptPacket())
        pump.offerExtractorPacket(byteArrayOf(reverse(0x51), reverse(0x61)), timeUs = 3)
        assertEquals(2, pump.pump().canonicalBytesConsumed)
        assertArrayEquals(byteArrayOf(0x51, 0x61), transport.committed.toByteArray())
    }

    @Test
    fun freshTrackTransitionDropsRendererTailAndRequiresCarrierResetPreparation() {
        val transport = FakeSession(facts, intArrayOf(1, 99))
        val pump = DirectDsdRendererPump(facts, transport)
        pump.offerExtractorPacket(byteArrayOf(reverse(0x10), reverse(0x20)), timeUs = 1)
        assertEquals(1, pump.pump().canonicalBytesConsumed)
        assertEquals(1, pump.snapshot().pendingCanonicalBytes)

        val (discarded, transition) =
            pump.prepareFreshTrackTransition(DoPCarrierSessionReset.RECONFIGURE)

        assertEquals(1, discarded)
        assertTrue(transition.feederPendingZero)
        assertTrue(transition.carrierResetApplied)
        assertEquals(1, transport.freshTransitionCalls)
        assertEquals(DoPCarrierSessionReset.RECONFIGURE, transport.lastFreshTransitionReason)
        assertEquals(0, pump.snapshot().pendingCanonicalBytes)
    }

    private class FakeSession(
        override val facts: DsfExtractorPacketFacts,
        private val limits: IntArray,
        private val finishResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
        private val startupReadyAfterBytes: Int = 0,
    ) : DirectDsdTransportSession {
        override var startupPrefillReady: Boolean = startupReadyAfterBytes == 0
            private set
        override var playbackArmed: Boolean = false
            private set
        val committed = java.io.ByteArrayOutputStream()
        var writeCalls = 0
        var armCalls = 0
        var gapStartCalls = 0
        var gapStopCalls = 0
        var gapActive = false
        var closeCalls = 0
        var transitionCalls = 0
        var lastTransitionFacts: DsfExtractorPacketFacts? = null
        var freshTransitionCalls = 0
        var lastFreshTransitionReason: DoPCarrierSessionReset? = null

        override fun writeCanonical(bytes: ByteArray, offset: Int, byteCount: Int): DirectDsdTransportWriteResult {
            check(!gapActive) { "CONTENT write while GAP owns transport" }
            val limit = limits.getOrElse(writeCalls) { Int.MAX_VALUE }
            writeCalls++
            val consumed = minOf(byteCount, limit)
            if (consumed > 0) committed.write(bytes, offset, consumed)
            if (committed.size() >= startupReadyAfterBytes) startupPrefillReady = true
            return DirectDsdTransportWriteResult(consumed)
        }

        override fun armPlayback() {
            check(startupPrefillReady) { "arm before startup ready" }
            check(!playbackArmed) { "already armed" }
            armCalls++
            playbackArmed = true
        }

        override fun startPauseGapLiveness() {
            check(playbackArmed) { "GAP before arm" }
            check(!gapActive) { "GAP already active" }
            gapStartCalls++
            gapActive = true
        }

        override fun stopPauseGapLiveness() {
            check(playbackArmed) { "GAP stop before arm" }
            check(gapActive) { "GAP not active" }
            gapStopCalls++
            gapActive = false
        }

        override fun quiescePauseGapForOutputRebuild(): Boolean {
            if (!gapActive) return false
            stopPauseGapLiveness()
            return true
        }

        override fun transitionRetainedSource(
            newFacts: DsfExtractorPacketFacts,
        ): DirectDsdRetainedSourceTransitionResult {
            check(!gapActive) { "retained transition while GAP active" }
            transitionCalls++
            lastTransitionFacts = newFacts
            return DirectDsdRetainedSourceTransitionResult(
                feederPendingZero = true,
                sourceResetApplied = true,
            )
        }

        override fun prepareFreshTrackTransition(
            reason: DoPCarrierSessionReset,
        ): DirectDsdFreshTransitionPreparationResult {
            freshTransitionCalls++
            lastFreshTransitionReason = reason
            return DirectDsdFreshTransitionPreparationResult(
                feederPendingZero = true,
                carrierResetApplied = true,
            )
        }

        override fun finishEndOfStream(): Boolean =
            if (finishResults.isEmpty()) true else finishResults.removeFirst()

        override fun close() {
            closeCalls++
        }
    }
    companion object {
        private fun reverse(value: Byte): Byte =
            (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()
    }
}
