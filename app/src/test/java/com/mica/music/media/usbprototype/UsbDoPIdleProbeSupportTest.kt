package com.mica.music.media.usbprototype

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
