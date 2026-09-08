package com.mica.music.data.scanner

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverColorExtractorTest {

    @Test
    fun streamedDecodeDoesNotReadWholeUriPayload() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val payload = png + ByteArray(2 * 1024 * 1024) { 0x5A.toByte() }
        val totalRead = AtomicLong(0)
        var openCount = 0

        val decoded = CoverColorExtractor.decodeSampledFromStream {
            openCount += 1
            CountingInputStream(ByteArrayInputStream(payload), totalRead)
        }

        assertNotNull(decoded)
        assertTrue("bounds + decode should reopen the stream", openCount >= 2)
        assertTrue(
            "streamed image decode should not materialize the full URI payload; read=" + totalRead.get(),
            totalRead.get() < payload.size / 2,
        )
    }

    private class CountingInputStream(
        input: InputStream,
        private val totalRead: AtomicLong,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) totalRead.incrementAndGet()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) totalRead.addAndGet(count.toLong())
            return count
        }
    }
}
