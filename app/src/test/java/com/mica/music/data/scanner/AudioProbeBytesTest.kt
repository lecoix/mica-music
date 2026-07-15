package com.mica.music.data.scanner

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    private companion object {
        val FLAC = "fLaC".toByteArray(Charsets.US_ASCII)
    }
}
