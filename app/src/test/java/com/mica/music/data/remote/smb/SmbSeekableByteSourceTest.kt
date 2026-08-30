package com.mica.music.data.remote.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSeekableByteSourceTest {
    @Test
    fun readAtFillsRequestedWindowAcrossShortSmbReads() {
        val file = ChunkedFile(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), maxChunk = 2)
        val source = SmbSeekableByteSource(file)
        val output = ByteArray(6)

        val read = source.readAt(1, output, 0, output.size)

        assertEquals(6, read)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), output)
        assertTrue(file.readCalls >= 3)
        source.close()
        assertTrue(file.closed)
    }

    @Test
    fun readAtClampsAtKnownEofInsteadOfRequestingPastFile() {
        val file = ChunkedFile(byteArrayOf(0, 1, 2, 3), maxChunk = 1)
        val source = SmbSeekableByteSource(file)
        val output = ByteArray(8)

        val read = source.readAt(2, output, 0, output.size)

        assertEquals(2, read)
        assertEquals(2, output[0].toInt())
        assertEquals(3, output[1].toInt())
        assertEquals(-1, source.readAt(4, output, 0, 1))
    }

    private class ChunkedFile(
        private val bytes: ByteArray,
        private val maxChunk: Int,
    ) : SmbRandomAccessFile {
        override val length: Long = bytes.size.toLong()
        var readCalls = 0
        var closed = false

        override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, maxChunk, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
