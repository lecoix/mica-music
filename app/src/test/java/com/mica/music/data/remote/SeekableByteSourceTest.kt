package com.mica.music.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekableByteSourceTest {
    @Test
    fun adjacentSmallReadsShareOneProtocolReadWindow() {
        val delegate = FakeSource(ByteArray(64) { it.toByte() })
        val source = ReadAheadSeekableByteSource(delegate, readAheadBytes = 16)
        val first = ByteArray(4)
        val second = ByteArray(4)

        assertEquals(4, source.readAt(2, first, 0, first.size))
        assertEquals(4, source.readAt(6, second, 0, second.size))

        assertArrayEquals(byteArrayOf(2, 3, 4, 5), first)
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), second)
        assertEquals(listOf(ReadCall(2, 16)), delegate.calls)
        source.close()
        source.close()
        assertEquals(1, delegate.closeCount)
    }

    @Test
    fun readCrossingCachedWindowBoundaryIsFilledInsteadOfReturningArtificialShortRead() {
        val delegate = FakeSource(ByteArray(32) { it.toByte() })
        val source = ReadAheadSeekableByteSource(delegate, readAheadBytes = 8)
        val warmup = ByteArray(2)
        val crossing = ByteArray(6)

        assertEquals(2, source.readAt(0, warmup, 0, warmup.size))
        assertEquals(6, source.readAt(6, crossing, 0, crossing.size))

        assertArrayEquals(byteArrayOf(6, 7, 8, 9, 10, 11), crossing)
        assertEquals(listOf(ReadCall(0, 8), ReadCall(8, 8)), delegate.calls)
    }

    @Test
    fun seekOutsideWindowFetchesNewBoundedWindow() {
        val delegate = FakeSource(ByteArray(64) { it.toByte() })
        val source = ReadAheadSeekableByteSource(delegate, readAheadBytes = 8)
        val first = ByteArray(2)
        val tail = ByteArray(3)

        assertEquals(2, source.readAt(0, first, 0, first.size))
        assertEquals(3, source.readAt(40, tail, 0, tail.size))

        assertEquals(listOf(ReadCall(0, 8), ReadCall(40, 8)), delegate.calls)
        assertArrayEquals(byteArrayOf(40, 41, 42), tail)
    }

    @Test
    fun readAheadNeverRequestsPastDeclaredSize() {
        val delegate = FakeSource(ByteArray(10) { it.toByte() })
        val source = ReadAheadSeekableByteSource(delegate, readAheadBytes = 8)
        val output = ByteArray(4)

        assertEquals(2, source.readAt(8, output, 0, output.size))

        assertEquals(listOf(ReadCall(8, 2)), delegate.calls)
        assertArrayEquals(byteArrayOf(8, 9, 0, 0), output)
        assertTrue(source.readAt(10, output, 0, 1) < 0)
    }

    private data class ReadCall(val offset: Long, val length: Int)

    private class FakeSource(
        private val bytes: ByteArray,
    ) : SeekableByteSource {
        val calls = mutableListOf<ReadCall>()
        var closeCount = 0
        override val sizeBytes: Long = bytes.size.toLong()

        override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            calls += ReadCall(fileOffset, length)
            if (fileOffset >= bytes.size) return -1
            val count = minOf(length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, bufferOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun close() {
            closeCount++
        }
    }
}
