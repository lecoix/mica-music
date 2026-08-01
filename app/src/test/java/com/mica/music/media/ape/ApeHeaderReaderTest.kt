package com.mica.music.media.ape

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApeHeaderReaderTest {

    @Test
    fun legacy3970_parsesDecoderContractAndFrameTable() {
        val bytes = legacy3970Fixture()

        val format = ApeHeaderReader.read(extractorInput(bytes))

        assertEquals(3_970, format.fileVersion)
        assertEquals(2_000, format.compressionType)
        assertEquals(16, format.bitsPerSample)
        assertEquals(2, format.channelCount)
        assertEquals(44_100, format.sampleRateHz)
        assertEquals(1, format.frames.size)
        assertEquals(36L, format.frames.single().position)
        assertEquals(4, format.frames.single().sizeBytes)
        assertEquals(1_000L, format.frames.single().blocks)
        assertEquals(1_000L * 1_000_000L / 44_100L, format.durationUs)
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x0F, 0xD0.toByte(), 0x07, 0x20, 0x00),
            format.decoderInitializationData,
        )
    }

    @Test
    fun modern3990_parsesTwoFramesWhenInputLengthIsUnknown() {
        val bytes = modern3990Fixture()

        val format = ApeHeaderReader.read(extractorInput(bytes, C.LENGTH_UNSET.toLong()))

        assertEquals(3_990, format.fileVersion)
        assertEquals(24, format.bitsPerSample)
        assertEquals(1, format.channelCount)
        assertEquals(48_000, format.sampleRateHz)
        assertEquals(2, format.frames.size)
        assertEquals(84L, format.frames[0].position)
        assertEquals(88L, format.frames[1].position)
        assertEquals(4, format.frames[0].sizeBytes)
        assertEquals(4, format.frames[1].sizeBytes)
        assertEquals(73_728L, format.frames[0].blocks)
        assertEquals(1_000L, format.frames[1].blocks)
    }

    @Test
    fun legacy3970_withLeadingId3v2_adjustsFramePositions() {
        val ape = legacy3970Fixture()
        val id3 = byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            4,
            0,
            0,
            0,
            0,
            0,
            4,
            1,
            2,
            3,
            4,
        )
        val bytes = id3 + ape

        assertTrue(ApeHeaderReader.sniff(extractorInput(bytes)))
        val format = ApeHeaderReader.read(extractorInput(bytes))

        assertEquals(50L, format.frames.single().position)
        assertEquals(4, format.frames.single().sizeBytes)
    }

    @Test
    fun sniff_rejectsInvalidOrOversizedLeadingId3v2() {
        val invalidSynchsafe = ByteArray(10).also {
            "ID3".toByteArray().copyInto(it)
            it[6] = 0x80.toByte()
        }
        val oversized = ByteArray(10).also {
            "ID3".toByteArray().copyInto(it)
            it[6] = 0x7F
            it[7] = 0x7F
            it[8] = 0x7F
            it[9] = 0x7F
        }

        assertFalse(ApeHeaderReader.sniff(extractorInput(invalidSynchsafe)))
        assertFalse(ApeHeaderReader.sniff(extractorInput(oversized)))
    }

    @Test
    fun sniff_rejectsUnsupported3800AndRandomBytes() {
        val legacy3800 = legacy3970Fixture().also {
            it[4] = 0xD8.toByte()
            it[5] = 0x0E
        }

        assertFalse(ApeHeaderReader.sniffHeader(legacy3800))
        assertFalse(ApeHeaderReader.sniffHeader(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(ApeHeaderReader.sniffHeader(legacy3970Fixture()))
    }

    @Test
    fun read_rejectsFrameCountAbovePlaybackBoundBeforeAllocatingTable() {
        val bytes = legacy3970Fixture(totalFrames = ApeHeaderReader.MAX_FRAMES + 1)

        val error = assertThrows(IOException::class.java) {
            ApeHeaderReader.read(extractorInput(bytes))
        }

        assertTrue(error.message.orEmpty().contains("frame count"))
    }

    @Test
    fun read_rejectsSeekTableShorterThanFrameCount() {
        val bytes = modern3990Fixture(seekTableLength = 4)

        val error = assertThrows(IOException::class.java) {
            ApeHeaderReader.read(extractorInput(bytes))
        }

        assertTrue(error.message.orEmpty().contains("fewer entries"))
    }

    @Test
    fun read_rejectsFrameStartingBeyondKnownInputLength() {
        val bytes = modern3990Fixture(secondFramePosition = 1_000)

        assertThrows(EOFException::class.java) {
            ApeHeaderReader.read(extractorInput(bytes))
        }
    }

    @Test
    fun read_rejectsDecoderInvalidCompressionLevel() {
        val bytes = legacy3970Fixture().also {
            it[6] = 0xDC.toByte()
            it[7] = 0x05
        }

        val error = assertThrows(IOException::class.java) {
            ApeHeaderReader.read(extractorInput(bytes))
        }

        assertTrue(error.message.orEmpty().contains("compression level"))
    }

    @Test
    fun read_rejectsFinalFrameBlocksAboveBlocksPerFrame() {
        val bytes = modern3990Fixture().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(60, 73_729)
        }

        val error = assertThrows(IOException::class.java) {
            ApeHeaderReader.read(extractorInput(bytes))
        }

        assertTrue(error.message.orEmpty().contains("audio parameters"))
    }

    private fun legacy3970Fixture(totalFrames: Int = 1): ByteArray =
        littleEndianBuffer(40).apply {
            put("MAC ".toByteArray())
            putShort(3_970.toShort())
            putShort(2_000.toShort())
            putShort(32) // MAC_FORMAT_FLAG_CREATE_WAV_HEADER
            putShort(2)
            putInt(44_100)
            putInt(0)
            putInt(0)
            putInt(totalFrames)
            putInt(1_000)
            putInt(36)
            putInt(0x1234_5678)
        }.array()

    private fun modern3990Fixture(
        seekTableLength: Int = 8,
        secondFramePosition: Int = 88,
    ): ByteArray =
        littleEndianBuffer(92).apply {
            put("MAC ".toByteArray())
            putShort(3_990.toShort())
            putShort(0)
            putInt(52)
            putInt(24)
            putInt(seekTableLength)
            putInt(0)
            putInt(8)
            putInt(0)
            putInt(0)
            put(ByteArray(16))
            putShort(3_000.toShort())
            putShort(0)
            putInt(73_728)
            putInt(1_000)
            putInt(2)
            putShort(24)
            putShort(1)
            putInt(48_000)
            putInt(84)
            if (seekTableLength >= 8) putInt(secondFramePosition)
            while (position() < capacity()) put(0x5A)
        }.array()

    private fun extractorInput(
        bytes: ByteArray,
        length: Long = bytes.size.toLong(),
    ): ExtractorInput {
        var position = 0
        val reader = DataReader { target, offset, requestedLength ->
            if (position >= bytes.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val count = minOf(requestedLength, bytes.size - position)
                bytes.copyInto(target, offset, position, position + count)
                position += count
                count
            }
        }
        return DefaultExtractorInput(reader, 0L, length)
    }

    private fun littleEndianBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
