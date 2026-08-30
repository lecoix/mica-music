package com.mica.music.data.scanner

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProbeBytesTest {
    @Test
    fun flacReadStopsAfterLastMetadataBlock() {
        val firstBlock = block(type = 0, isLast = false, payload = byteArrayOf(1, 2, 3))
        val lastBlock = block(type = 4, isLast = true, payload = byteArrayOf(4, 5))
        val metadata = FLAC + firstBlock + lastBlock
        val audioFrames = ByteArray(1024) { 0x55.toByte() }

        val actual = AudioProbeBytes.readFlacMetadata(ByteArrayInputStream(metadata + audioFrames))

        assertArrayEquals(metadata, actual)
    }

    @Test
    fun flacReadPreservesId3Prefix() {
        val id3Payload = byteArrayOf(9, 8, 7)
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
            synchsafe(id3Payload.size) + id3Payload
        val metadata = FLAC + block(type = 4, isLast = true, payload = byteArrayOf(1))

        val actual = AudioProbeBytes.readFlacMetadata(ByteArrayInputStream(id3 + metadata + ByteArray(64)))

        assertArrayEquals(id3 + metadata, actual)
    }

    @Test
    fun unrecognizedHeaderRetainsLegacyBoundedRead() {
        val bytes = "not-flac-data".toByteArray()

        val actual = AudioProbeBytes.readFlacMetadata(ByteArrayInputStream(bytes), maxBytes = 8)

        assertEquals(8, actual.size)
        assertArrayEquals(bytes.copyOf(8), actual)
    }

    @Test
    fun truncatedDeclaredMetadataFailsStrictly() {
        val truncated = FLAC + blockHeader(type = 4, isLast = true, length = 10) + byteArrayOf(1, 2)

        assertThrows(IOException::class.java) {
            AudioProbeBytes.readFlacMetadata(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun randomAccessId3ReadHandlesShortReadsAndStopsAtDeclaredTag() {
        val payload = ByteArray(37) { (it + 1).toByte() }
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            synchsafe(payload.size) + payload
        val source = FakeRandomAccessSource(id3 + ByteArray(4096) { 0x55.toByte() }, maxChunk = 7)

        val actual = AudioProbeBytes.readFastForLyricsOrThrow(source, "audio/mpeg", "Song.mp3")

        assertArrayEquals(id3, actual)
        assertTrue(source.reads.all { it.first < id3.size })
    }

    @Test
    fun randomAccessMp4ReadsOnlyMoovBoxAfterScanningHeaders() {
        val ftyp = box("ftyp", byteArrayOf(1, 2, 3, 4))
        val mdat = box("mdat", ByteArray(64) { 0x33 })
        val moov = box("moov", ByteArray(48) { 0x44 })
        val source = FakeRandomAccessSource(ftyp + mdat + moov + ByteArray(1024), maxChunk = 11)

        val actual = AudioProbeBytes.readFastForLyricsOrThrow(source, "audio/mp4", "Song.m4a")

        assertArrayEquals(moov, actual)
        assertTrue(source.reads.any { it.first == (ftyp.size + mdat.size).toLong() })
    }

    private fun block(type: Int, isLast: Boolean, payload: ByteArray): ByteArray =
        blockHeader(type, isLast, payload.size) + payload

    private fun blockHeader(type: Int, isLast: Boolean, length: Int): ByteArray = byteArrayOf(
        (type or if (isLast) 0x80 else 0).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private class FakeRandomAccessSource(
        private val bytes: ByteArray,
        private val maxChunk: Int,
    ) : AudioProbeRandomAccessSource {
        override val sizeBytes: Long = bytes.size.toLong()
        val reads = mutableListOf<Pair<Long, Int>>()

        override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, maxChunk, bytes.size - fileOffset.toInt())
            reads += fileOffset to count
            bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }
    }

    private companion object {
        val FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    }
}
