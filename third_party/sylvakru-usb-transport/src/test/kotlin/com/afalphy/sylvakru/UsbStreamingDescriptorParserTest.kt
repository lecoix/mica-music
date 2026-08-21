package com.afalphy.sylvakru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStreamingDescriptorParserTest {
    @Test
    fun `UAC1 type I descriptor exposes channels subslot and resolution`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 2, 1, 1, 0x01, 0x02, 0, 0,
                7, 0x24, 0x01, 4, 1, 1, 0,
                11, 0x24, 0x02, 1, 2, 4, 32, 1, 0x80.toByte(), 0xBB.toByte(), 0,
            ),
        )

        val format = formats.getValue(2 to 1)
        assertEquals(0, format.protocol)
        assertEquals(2, format.channels)
        assertEquals(4, format.subslotSize)
        assertEquals(32, format.bitResolution)
        assertFalse(format.isRawData)
    }

    @Test
    fun `UAC2 type I descriptor exposes bmFormats and channel count`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 3, 2, 1, 0x01, 0x02, 0x20, 0x20,
                16, 0x24, 0x01, 5, 0, 1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0,
                6, 0x24, 0x02, 1, 4, 32,
            ),
        )

        val format = formats.getValue(3 to 2)
        assertEquals(0x20, format.protocol)
        assertEquals(1, format.bmFormats)
        assertEquals(2, format.channels)
        assertEquals(4, format.subslotSize)
        assertEquals(32, format.bitResolution)
        assertFalse(format.isRawData)
    }

    @Test
    fun `UAC2 RAW_DATA descriptor remains framing unproven`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 3, 3, 1, 0x01, 0x02, 0x20, 0x20,
                16, 0x24, 0x01, 5, 0, 1, 0, 0, 0, 0x80.toByte(), 2, 0, 0, 0, 0, 0,
                6, 0x24, 0x02, 1, 4, 32,
            ),
        )

        val format = formats.getValue(3 to 3)
        assertTrue(format.isRawData)
        assertEquals(
            NativeCandidate.FramingUnproven,
            UsbStreamingTargetResolver.classifyNativeCandidate(
                hasRawData = format.isRawData,
                quirk = DacQuirk(),
            ),
        )
    }

    @Test
    fun `truncated descriptor tail is ignored without publishing partial format`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 2, 1, 1, 0x01, 0x02, 0, 0,
                11, 0x24, 0x02, 1, 2, 4,
            ),
        )

        assertTrue(formats.isEmpty())
    }
}
