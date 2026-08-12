package com.mica.music.media.dsd

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdPayloadEncodersTest {

    @Test
    fun dopThreeChannelGoldenVectorKeepsOneMarkerPhasePerCarrierFrame() {
        val source = byteArrayOf(
            0x10, 0x20, 0x30,
            0x11, 0x21, 0x31,
            0x12, 0x22, 0x32,
            0x13, 0x23, 0x33,
        )
        val encoder = DoPEncoder(channelCount = 3)
        val words = IntArray(6)

        assertEquals(2, encoder.encodeFrames(source, frameCount = 4, destinationWords = words))
        assertArrayEquals(
            intArrayOf(
                0x051011, 0x052021, 0x053031,
                0xFA1213, 0xFA2223, 0xFA3233,
            ),
            words,
        )
    }

    @Test
    fun nativeThreeChannelU16LeGroupsChronologicalDsdBytesPerChannel() {
        val source = byteArrayOf(
            0x10, 0x20, 0x30,
            0x11, 0x21, 0x31,
            0x12, 0x22, 0x32,
            0x13, 0x23, 0x33,
        )
        val encoder = NativeDsdEncoder(channelCount = 3, framing = NativeDsdFraming.U16_LE)
        val output = ByteArray(source.size)

        assertEquals(2, encoder.encodeFrames(source, frameCount = 4, destination = output))
        assertArrayEquals(
            byteArrayOf(
                0x11, 0x10, 0x21, 0x20, 0x31, 0x30,
                0x13, 0x12, 0x23, 0x22, 0x33, 0x32,
            ),
            output,
        )
    }

    @Test
    fun dopGoldenVectorAlternatesMarkerOncePerCarrierFrameAcrossChannels() {
        val source = byteArrayOf(
            0x80.toByte(), 0x40,
            0x81.toByte(), 0x41,
            0x82.toByte(), 0x42,
            0x83.toByte(), 0x43,
        )
        val encoder = DoPEncoder(channelCount = 2)
        val words = IntArray(4)

        assertEquals(2, encoder.encodeFrames(source, frameCount = 4, destinationWords = words))

        assertArrayEquals(
            intArrayOf(0x058081, 0x054041, 0xFA8283, 0xFA4243),
            words,
        )
        assertEquals(DoPEncoder.MARKER_A, encoder.marker)
    }

    @Test
    fun dopCarriesOddByteFrameAcrossWritesAndDrainUsesOnlyDsdIdle() {
        val encoder = DoPEncoder(channelCount = 2)
        val first = byteArrayOf(0x10, 0x20)
        val next = byteArrayOf(
            0x11, 0x21,
            0x12, 0x22,
        )
        val words = IntArray(4)

        assertEquals(0, encoder.encodeFrames(first, frameCount = 1, destinationWords = words))
        assertTrue(encoder.hasPendingHalfFrame())
        assertEquals(1, encoder.encodeFrames(next, frameCount = 2, destinationWords = words))
        assertArrayEquals(intArrayOf(0x051011, 0x052021), words.copyOfRange(0, 2))
        assertTrue(encoder.hasPendingHalfFrame())

        assertEquals(1, encoder.drain(words, destinationWordOffset = 2))
        assertArrayEquals(intArrayOf(0xFA1269, 0xFA2269), words.copyOfRange(2, 4))
        assertFalse(encoder.hasPendingHalfFrame())
        assertEquals(DoPEncoder.MARKER_A, encoder.marker)
    }

    @Test
    fun dopCarrierPackingIsAnExplicitContractFor24And32BitSlots() {
        val words = intArrayOf(0x058081, 0xFA4243)

        val packed24 = ByteArray(6)
        assertEquals(
            6,
            DoPEncoder.packWords(words, packing = DoPCarrierPacking.PACKED_24_LE, destination = packed24),
        )
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x05, 0x43, 0x42, 0xFA.toByte()),
            packed24,
        )

        val msbAligned32 = ByteArray(8)
        DoPEncoder.packWords(
            words,
            packing = DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED,
            destination = msbAligned32,
        )
        assertArrayEquals(
            byteArrayOf(0, 0x81.toByte(), 0x80.toByte(), 0x05, 0, 0x43, 0x42, 0xFA.toByte()),
            msbAligned32,
        )

        val lsbAligned32 = ByteArray(8)
        DoPEncoder.packWords(
            words,
            packing = DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED,
            destination = lsbAligned32,
        )
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x05, 0, 0x43, 0x42, 0xFA.toByte(), 0),
            lsbAligned32,
        )
    }

    @Test
    fun nativeGoldenVectorsOnlyGroupAndReorderCanonicalDsdBytes() {
        val source = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
            0x12, 0x22,
            0x13, 0x23,
        )

        assertNative(
            source,
            NativeDsdFraming.U8,
            byteArrayOf(0x10, 0x20, 0x11, 0x21, 0x12, 0x22, 0x13, 0x23),
            expectedRuntimeFrames = 4,
        )
        assertNative(
            source,
            NativeDsdFraming.U16_LE,
            byteArrayOf(0x11, 0x10, 0x21, 0x20, 0x13, 0x12, 0x23, 0x22),
            expectedRuntimeFrames = 2,
        )
        assertNative(
            source,
            NativeDsdFraming.U32_LE,
            byteArrayOf(0x13, 0x12, 0x11, 0x10, 0x23, 0x22, 0x21, 0x20),
            expectedRuntimeFrames = 1,
        )
        assertNative(
            source,
            NativeDsdFraming.U32_BE,
            byteArrayOf(0x10, 0x11, 0x12, 0x13, 0x20, 0x21, 0x22, 0x23),
            expectedRuntimeFrames = 1,
        )
    }

    @Test
    fun nativeCarryAndDrainPreserveChannelOrderAndUseIdlePadding() {
        val encoder = NativeDsdEncoder(channelCount = 2, framing = NativeDsdFraming.U32_LE)
        val output = ByteArray(8)

        assertEquals(0, encoder.encodeFrames(byteArrayOf(1, 11, 2, 12, 3, 13), frameCount = 3, destination = output))
        assertEquals(3, encoder.pendingByteFrames())
        assertEquals(1, encoder.drain(output))

        assertArrayEquals(
            byteArrayOf(0x69, 3, 2, 1, 0x69, 13, 12, 11),
            output,
        )
        assertEquals(0, encoder.pendingByteFrames())
    }

    @Test
    fun frameRateMathKeepsDsdSemanticRateSeparateFromUsbRuntimeRate() {
        assertEquals(176_400, DoPEncoder.carrierFrameRate(2_822_400))
        assertEquals(352_800, DoPEncoder.carrierFrameRate(5_644_800))
        assertEquals(352_800, NativeDsdEncoder.runtimeFrameRate(2_822_400, NativeDsdFraming.U8))
        assertEquals(176_400, NativeDsdEncoder.runtimeFrameRate(5_644_800, NativeDsdFraming.U32_LE))
    }

    @Test
    fun dopMarkerPhaseSurvivesTenThousandDeterministicallySplitByteFrames() {
        val random = Random(0x5D5D)
        val encoder = DoPEncoder(channelCount = 2)
        var expectedMarker = DoPEncoder.MARKER_A
        var pendingSourceFrame: ByteArray? = null
        var produced = 0

        repeat(10_000) {
            val frame = byteArrayOf(random.nextInt(256).toByte(), random.nextInt(256).toByte())
            val output = IntArray(2)
            val count = encoder.encodeFrames(frame, frameCount = 1, destinationWords = output)
            if (pendingSourceFrame == null) {
                assertEquals(0, count)
                pendingSourceFrame = frame
            } else {
                assertEquals(1, count)
                val older = pendingSourceFrame!!
                assertEquals(DoPEncoder.logicalWord(expectedMarker, older[0], frame[0]), output[0])
                assertEquals(DoPEncoder.logicalWord(expectedMarker, older[1], frame[1]), output[1])
                expectedMarker = if (expectedMarker == DoPEncoder.MARKER_A) DoPEncoder.MARKER_B else DoPEncoder.MARKER_A
                pendingSourceFrame = null
                produced++
            }
        }

        assertEquals(5_000, produced)
        assertEquals(expectedMarker, encoder.marker)
        assertFalse(encoder.hasPendingHalfFrame())
    }

    @Test
    fun nativeU32LeMatchesOneShotAcrossTenThousandDeterministicallySplitByteFrames() {
        val random = Random(0x445344)
        val source = ByteArray(20_000) { random.nextInt(256).toByte() }
        val oneShotEncoder = NativeDsdEncoder(channelCount = 2, framing = NativeDsdFraming.U32_LE)
        val expected = ByteArray(source.size)
        assertEquals(
            2_500,
            oneShotEncoder.encodeFrames(source, frameCount = 10_000, destination = expected),
        )

        val splitEncoder = NativeDsdEncoder(channelCount = 2, framing = NativeDsdFraming.U32_LE)
        val actual = ByteArray(source.size)
        var sourceFrame = 0
        var destinationOffset = 0
        while (sourceFrame < 10_000) {
            val frames = minOf(1 + random.nextInt(11), 10_000 - sourceFrame)
            val maxOutputFrames = (splitEncoder.pendingByteFrames() + frames) / 4
            val chunk = ByteArray(maxOutputFrames * 8)
            val produced = splitEncoder.encodeFrames(
                source = source,
                sourceOffset = sourceFrame * 2,
                frameCount = frames,
                destination = chunk,
            )
            chunk.copyInto(actual, destinationOffset, 0, produced * 8)
            destinationOffset += produced * 8
            sourceFrame += frames
        }

        assertEquals(source.size, destinationOffset)
        assertEquals(0, splitEncoder.pendingByteFrames())
        assertArrayEquals(expected, actual)
    }

    @Test
    fun streamInfoMathDoesNotOverflowAtLongMaxSampleCount() {
        val info = DsdStreamInfo(
            container = DsdContainerType.DSF,
            sampleRateHz = 2_822_400,
            channelCount = 2,
            sampleCountPerChannel = Long.MAX_VALUE,
            sourceBitOrder = DsdSourceBitOrder.MSB_FIRST,
        )

        assertEquals(Long.MAX_VALUE / 8L + 1L, info.byteFrameCount)
        assertEquals(3_267_918_096_958_183_038L, info.durationUs)
    }

    private fun assertNative(
        source: ByteArray,
        framing: NativeDsdFraming,
        expected: ByteArray,
        expectedRuntimeFrames: Int,
    ) {
        val encoder = NativeDsdEncoder(channelCount = 2, framing = framing)
        val output = ByteArray(expected.size)
        assertEquals(expectedRuntimeFrames, encoder.encodeFrames(source, frameCount = 4, destination = output))
        assertArrayEquals(expected, output)
    }
}
