package com.mica.music.media.dsf

import com.mica.music.media.dsd.ByteSourceIdentity
import com.mica.music.media.dsd.DsdContainerReaders
import com.mica.music.media.dsd.SeekableByteSource
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DsfExtractorPacketCanonicalizerTest {
    @Test
    fun planarExtractorPacketsMatchP5ReaderAcrossFullAndFinalBlockLsbFirst() {
        assertExtractorPacketsMatchReader(bitsPerSample = 1)
    }

    private fun assertExtractorPacketsMatchReader(bitsPerSample: Int) {
        val canonical = byteArrayOf(
            0x80.toByte(), 0x40,
            0x81.toByte(), 0x41,
            0x82.toByte(), 0x42,
            0x83.toByte(), 0x43,
            0x84.toByte(), 0x44,
            0x85.toByte(), 0x45,
        )
        val bytes = buildFixture(canonical, bitsPerSample, blockSize = 4)
        val format = DsfHeaderReader.parse(
            bytes.copyOfRange(0, 28),
            bytes.copyOfRange(28, 80),
            bytes.copyOfRange(80, 92),
        )
        val facts = DsfExtractorPacketFacts.fromFormat(format)

        val fromExtractorPackets = ByteArrayOutputStream()
        var frame = 0
        val totalFrames = canonical.size / format.channelCount
        while (frame < totalFrames) {
            val validFrames = minOf(format.blockSizePerChannel, totalFrames - frame)
            val blockIndex = frame / format.blockSizePerChannel
            val blockBase = format.dataPayloadOffset + blockIndex * format.blockAlign
            val packet = ByteArray(validFrames * format.channelCount)
            repeat(format.channelCount) { channel ->
                val source = (blockBase + channel * format.blockSizePerChannel).toInt()
                bytes.copyInto(
                    packet,
                    destinationOffset = channel * validFrames,
                    startIndex = source,
                    endIndex = source + validFrames,
                )
            }
            fromExtractorPackets.write(DsfExtractorPacketCanonicalizer.canonicalize(packet, facts = facts))
            frame += validFrames
        }

        DsdContainerReaders.open(TestByteSource(bytes)).use { reader ->
            val fromReader = ByteArray(canonical.size)
            assertEquals(totalFrames, reader.readFrames(fromReader, maxFrames = totalFrames))
            assertArrayEquals(canonical, fromReader)
            assertArrayEquals(fromReader, fromExtractorPackets.toByteArray())
        }
    }
}

private class TestByteSource(private val bytes: ByteArray) : SeekableByteSource {
    override val length: Long = bytes.size.toLong()
    override val identity: ByteSourceIdentity = ByteSourceIdentity("extractor-canonicalizer-fixture")

    override fun readAt(position: Long, destination: ByteArray, destinationOffset: Int, byteCount: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position.toInt())
        bytes.copyInto(destination, destinationOffset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}

private fun buildFixture(canonical: ByteArray, bitsPerSample: Int, blockSize: Int): ByteArray {
    val channels = 2
    val frames = canonical.size / channels
    val blockCount = (frames + blockSize - 1) / blockSize
    val payload = ByteArray(blockCount * blockSize * channels)
    repeat(blockCount) { block ->
        repeat(channels) { channel ->
            repeat(blockSize) { inBlock ->
                val frame = block * blockSize + inBlock
                if (frame < frames) {
                    val canonicalByte = canonical[frame * channels + channel]
                    payload[(block * channels + channel) * blockSize + inBlock] =
                        if (bitsPerSample == 1) reverseFixtureBits(canonicalByte) else canonicalByte
                }
            }
        }
    }
    val result = ByteArray(92 + payload.size)
    "DSD ".toByteArray().copyInto(result, 0)
    putU64Le(result, 4, 28L)
    putU64Le(result, 12, result.size.toLong())
    putU64Le(result, 20, 0L)
    "fmt ".toByteArray().copyInto(result, 28)
    putU64Le(result, 32, 52L)
    putU32Le(result, 40, 1)
    putU32Le(result, 44, 0)
    putU32Le(result, 48, 2)
    putU32Le(result, 52, channels)
    putU32Le(result, 56, 2_822_400)
    putU32Le(result, 60, bitsPerSample)
    putU64Le(result, 64, frames.toLong() * 8L)
    putU32Le(result, 72, blockSize)
    putU32Le(result, 76, 0)
    "data".toByteArray().copyInto(result, 80)
    putU64Le(result, 84, payload.size.toLong() + 12L)
    payload.copyInto(result, 92)
    return result
}

private fun reverseFixtureBits(value: Byte): Byte =
    (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()

private fun putU32Le(bytes: ByteArray, offset: Int, value: Int) {
    repeat(4) { index -> bytes[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte() }
}

private fun putU64Le(bytes: ByteArray, offset: Int, value: Long) {
    repeat(8) { index -> bytes[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte() }
}
