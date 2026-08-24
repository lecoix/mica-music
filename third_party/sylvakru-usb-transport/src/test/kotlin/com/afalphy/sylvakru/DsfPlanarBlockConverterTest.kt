package com.afalphy.sylvakru

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DsfPlanarBlockConverterTest {
    @Test
    fun `planar stereo becomes byte interleaved`() {
        val converter = DsfPlanarBlockConverter(channels = 2, lsbFirst = false)

        assertArrayEquals(
            byteArrayOf(0x01, 0x11, 0x02, 0x12, 0x03, 0x13),
            converter.convert(byteArrayOf(0x01, 0x02, 0x03, 0x11, 0x12, 0x13)),
        )
    }

    @Test
    fun `bitsPerSample one reverses each DSF byte like reference reader`() {
        val converter = DsfPlanarBlockConverter(channels = 2, lsbFirst = true)

        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x40, 0xC0.toByte(), 0x20),
            converter.convert(byteArrayOf(0x01, 0x03, 0x02, 0x04)),
        )
    }
}
