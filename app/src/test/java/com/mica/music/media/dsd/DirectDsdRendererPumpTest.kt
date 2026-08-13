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

    private class FakeSession(
        override val facts: DsfExtractorPacketFacts,
        private val limits: IntArray,
        private val finishResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
    ) : DirectDsdTransportSession {
        override val playbackArmed: Boolean = true
        val committed = java.io.ByteArrayOutputStream()
        var writeCalls = 0
        var closeCalls = 0

        override fun writeCanonical(bytes: ByteArray, offset: Int, byteCount: Int): DirectDsdTransportWriteResult {
            val limit = limits.getOrElse(writeCalls) { Int.MAX_VALUE }
            writeCalls++
            val consumed = minOf(byteCount, limit)
            if (consumed > 0) committed.write(bytes, offset, consumed)
            return DirectDsdTransportWriteResult(consumed)
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
