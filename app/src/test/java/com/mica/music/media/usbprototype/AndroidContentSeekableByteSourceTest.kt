package com.mica.music.media.usbprototype

import android.os.ParcelFileDescriptor
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidContentSeekableByteSourceTest {
    @Test
    fun positionalReadsShortEofIdentityAndDoubleCloseAreDeterministic() {
        val file = File.createTempFile("mica-content-source", ".bin")
        try {
            val bytes = ByteArray(32) { it.toByte() }
            file.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val source = AndroidContentSeekableByteSource.fromParcelFileDescriptor(
                pfd = pfd,
                stableId = "content://media/external/file/42",
                generation = 77L,
                knownLength = bytes.size.toLong(),
            )

            assertEquals(bytes.size.toLong(), source.length)
            assertEquals("content://media/external/file/42", source.identity.stableId)
            assertEquals(77L, source.identity.generation)

            val destination = ByteArray(10) { 0x55 }
            assertEquals(4, source.readAt(5L, destination, 2, 4))
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), destination.copyOfRange(2, 6))

            val short = ByteArray(8)
            assertEquals(2, source.readAt(30L, short, 0, short.size))
            assertArrayEquals(byteArrayOf(30, 31), short.copyOfRange(0, 2))
            assertEquals(-1, source.readAt(32L, short, 0, 1))
            assertEquals(0, source.readAt(12L, short, 0, 0))

            source.close()
            source.close()
            assertThrows(IllegalStateException::class.java) {
                source.readAt(0L, ByteArray(1), 0, 1)
            }
        } finally {
            file.delete()
        }
    }
}