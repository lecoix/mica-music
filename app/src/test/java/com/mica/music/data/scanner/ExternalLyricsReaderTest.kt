package com.mica.music.data.scanner

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLyricsReaderTest {

    @Test
    fun externalLyricsInputIsBoundedAtTenMebibytes() {
        assertEquals(10 * 1024 * 1024, ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES)

        val exact = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            exact,
            ExternalLyricsReader.readBoundedLyricsBytes(ByteArrayInputStream(exact), maxBytes = 4),
        )
        assertNull(
            ExternalLyricsReader.readBoundedLyricsBytes(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                maxBytes = 4,
            ),
        )
    }
}
