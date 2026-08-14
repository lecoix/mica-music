package com.mica.music.media.usbprototype

import java.nio.ByteBuffer
import com.mica.music.media.dsd.DirectDsdMonotonicClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDoPIdleProbeSupportTest {
    @Test
    fun nativeSinkForwardsOneAlignedOfferAndReturnsNativeCountUnchanged() {
        val captured = mutableListOf<ByteArray>()
        val sink = UsbDoPIdleNativeSink(
            bytesPerRuntimeFrame = 6,
            nativeWrite = UsbExactCarrierNativeWrite { buffer, length ->
                val bytes = ByteArray(length)
                buffer.duplicate().get(bytes)
                captured += bytes
                6
            },
        )
        val source = ByteArray(18) { index -> (index + 1).toByte() }

        val accepted = sink.writeCarrierFrames(source, offset = 6, byteCount = 12)

        assertEquals(6, accepted)
        assertEquals(1, captured.size)
        assertArrayEquals(source.copyOfRange(6, 18), captured.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nativeSinkRejectsNonFrameAlignedOfferBeforeNativeCall() {
        var calls = 0
        val sink = UsbDoPIdleNativeSink(
            bytesPerRuntimeFrame = 6,
            nativeWrite = UsbExactCarrierNativeWrite { _: ByteBuffer, _: Int ->
                calls++
                0
            },
        )
        try {
            sink.writeCarrierFrames(ByteArray(12), offset = 0, byteCount = 7)
        } finally {
            assertEquals(0, calls)
        }
    }

    @Test
    fun timingObservationPreservesNativeCountAcceptedBytesAndPartialChronology() {
        val clock = FakeClock()
        val timing = DirectDsdWriteTimingRecorder(clock)
        var calls = 0
        val sink = UsbDoPIdleNativeSink(
            bytesPerRuntimeFrame = 6,
            timing = timing,
            nativeWrite = UsbExactCarrierNativeWrite { _: ByteBuffer, length: Int ->
                calls++
                clock.advance(7)
                length / 2
            },
        )

        val accepted = sink.writeCarrierFrames(ByteArray(24), offset = 6, byteCount = 12)
        val snapshot = timing.snapshotAndReset()

        assertEquals(6, accepted)
        assertEquals(1, calls)
        assertEquals(1, snapshot.sinkCalls)
        assertEquals(12, snapshot.sinkOfferedBytes)
        assertEquals(6, snapshot.sinkAcceptedBytes)
        assertEquals(1, snapshot.sinkPartialAccepts)
        assertEquals(0, snapshot.sinkZeroAccepts)
        assertEquals(1, snapshot.nativeWriteCalls)
        assertTrue(snapshot.nativeWriteTotalNs >= 7)
    }

    @Test
    fun timingObservationPreservesNativeException() {
        val clock = FakeClock()
        val timing = DirectDsdWriteTimingRecorder(clock)
        val sink = UsbDoPIdleNativeSink(
            bytesPerRuntimeFrame = 6,
            timing = timing,
            nativeWrite = UsbExactCarrierNativeWrite { _: ByteBuffer, _: Int ->
                clock.advance(3)
                error("native boom")
            },
        )

        val failure = runCatching {
            sink.writeCarrierFrames(ByteArray(12), offset = 0, byteCount = 12)
        }.exceptionOrNull()

        assertEquals("native boom", failure?.message)
        val snapshot = timing.snapshotAndReset()
        assertEquals(1, snapshot.nativeWriteCalls)
        assertEquals(0, snapshot.sinkCalls)
        assertTrue(snapshot.nativeWriteTotalNs >= 3)
    }

    @Test
    fun refillTargetIsAtLeastOneSecondUnlessRingCapacityIsSmaller() {
        assertEquals(
            176_400L,
            UsbDoPIdleProbePolicy.refillTargetFrames(
                bufferCapacityFrames = 352_800L,
                requiredPrefillFrames = 6_400L,
            ),
        )
        assertEquals(
            100_000L,
            UsbDoPIdleProbePolicy.refillTargetFrames(
                bufferCapacityFrames = 100_000L,
                requiredPrefillFrames = 6_400L,
            ),
        )
        assertEquals(
            200_000L,
            UsbDoPIdleProbePolicy.refillTargetFrames(
                bufferCapacityFrames = 352_800L,
                requiredPrefillFrames = 100_000L,
            ),
        )
    }

    private class FakeClock(private var now: Long = 0L) : DirectDsdMonotonicClock {
        override fun nanoTime(): Long = now
        fun advance(delta: Long) { now += delta }
    }
}
