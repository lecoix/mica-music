package com.afalphy.sylvakru

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("uac1-format-descriptor:48000", format.sampleRateCapability?.description)
        assertTrue(checkNotNull(format.sampleRateCapability).supports(48_000))
        assertFalse(checkNotNull(format.sampleRateCapability).supports(44_100))
    }

    @Test
    fun `UAC1 discrete sample rates are parsed as advertised capability`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 2, 1, 1, 0x01, 0x02, 0, 0,
                14, 0x24, 0x02, 1, 2, 4, 32, 2,
                0x44, 0xAC.toByte(), 0,
                0, 0x77, 0x01,
            ),
        )

        val capability = checkNotNull(formats.getValue(2 to 1).sampleRateCapability)
        assertTrue(capability.supports(44_100))
        assertTrue(capability.supports(96_000))
        assertFalse(capability.supports(48_000))
    }

    @Test
    fun `UAC1 continuous sample rate range accepts values inside advertised bounds`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 2, 1, 1, 0x01, 0x02, 0, 0,
                14, 0x24, 0x02, 1, 2, 4, 32, 0,
                0x44, 0xAC.toByte(), 0,
                0, 0xEE.toByte(), 0x02,
            ),
        )

        val capability = checkNotNull(formats.getValue(2 to 1).sampleRateCapability)
        assertTrue(capability.supports(44_100))
        assertTrue(capability.supports(48_000))
        assertTrue(capability.supports(192_000))
        assertFalse(capability.supports(384_000))
    }

    @Test
    fun `UAC2 type I descriptor exposes bmFormats and channel count`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 3, 2, 1, 0x01, 0x02, 0x20, 7,
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
    fun `UAC2 RAW_DATA descriptor infers u32le from four byte subslot`() {
        val formats = UsbStreamingTargetResolver.parseStreamingFormatInfo(
            byteArrayOf(
                9, 0x04, 3, 3, 1, 0x01, 0x02, 0x20, 8,
                16, 0x24, 0x01, 5, 0, 1, 0, 0, 0, 0x80.toByte(), 2, 0, 0, 0, 0, 0,
                6, 0x24, 0x02, 1, 4, 32,
            ),
        )

        val format = formats.getValue(3 to 3)
        assertTrue(format.isRawData)
        assertEquals(
            NativeCandidate.Proven("u32le"),
            UsbStreamingTargetResolver.classifyNativeCandidate(
                rawDataSubslotSizes = listOfNotNull(format.subslotSize),
                quirk = DacQuirk(),
            ),
        )
    }

    @Test
    fun `UAC2 clock GET_RANGE response exposes min max resolution capabilities`() {
        val data = ByteBuffer.allocate(26)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2.toShort())
            .putInt(44_100)
            .putInt(176_400)
            .putInt(44_100)
            .putInt(48_000)
            .putInt(192_000)
            .putInt(48_000)
            .array()

        val capability = checkNotNull(
            UsbStreamingTargetResolver.parseUac2ClockRangeResponse(data, data.size),
        )
        assertTrue(capability.supports(44_100))
        assertTrue(capability.supports(132_300))
        assertTrue(capability.supports(96_000))
        assertFalse(capability.supports(50_000))
        assertEquals(
            "uac2-clock-range:44100-176400/44100|48000-192000/48000",
            capability.description,
        )
    }

    @Test
    fun `truncated UAC2 clock GET_RANGE is capability unknown not partial truth`() {
        val data = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2.toShort())
            .putInt(44_100)
            .putInt(44_100)
            .putInt(0)
            .array()

        assertNull(UsbStreamingTargetResolver.parseUac2ClockRangeResponse(data, data.size))
    }

    @Test
    fun `UAC2 defers target alt while resetAlt only adds explicit preconfigure alt zero`() {
        assertEquals(
            UsbStreamingActivationPlan(false, false),
            UsbStreamingTargetResolver.streamingActivationPlan(isUac2 = false, resetAltQuirk = false),
        )
        assertEquals(
            UsbStreamingActivationPlan(false, false),
            UsbStreamingTargetResolver.streamingActivationPlan(isUac2 = false, resetAltQuirk = true),
        )
        assertEquals(
            UsbStreamingActivationPlan(true, false),
            UsbStreamingTargetResolver.streamingActivationPlan(isUac2 = true, resetAltQuirk = false),
        )
        assertEquals(
            UsbStreamingActivationPlan(true, true),
            UsbStreamingTargetResolver.streamingActivationPlan(isUac2 = true, resetAltQuirk = true),
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
