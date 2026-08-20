package com.afalphy.sylvakru

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPcmIsoPacketizerTest {

    @Test
    fun `48k stereo 16-bit keeps reference packet size and bytes`() {
        val writes = mutableListOf<Write>()
        val pcm = ByteArray(48 * 2 * 2) { index -> (index and 0xff).toByte() }
        val packetizer = packetizer(
            sampleRate = 48_000,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 2,
            usbBitResolution = 16,
            writes = writes,
        )

        packetizer.write(pcm)
        packetizer.flush()

        assertEquals(1, writes.size)
        assertArrayEquals(intArrayOf(192), writes.single().packetLengths)
        assertArrayEquals(pcm, writes.single().data)
    }

    @Test
    fun `44k1 cadence distributes 441 frames across ten 1ms packets`() {
        val writes = mutableListOf<Write>()
        val pcm = ByteArray(441 * 2 * 2)
        val packetizer = packetizer(
            sampleRate = 44_100,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 2,
            usbBitResolution = 16,
            writes = writes,
        )

        packetizer.write(pcm)
        packetizer.flush()

        assertEquals(1, writes.size)
        val lengths = writes.single().packetLengths
        assertEquals(10, lengths.size)
        assertEquals(9, lengths.count { it == 44 * 4 })
        assertEquals(1, lengths.count { it == 45 * 4 })
        assertEquals(pcm.size, lengths.sum())
    }

    @Test
    fun `24-bit PCM is left aligned into 32-bit USB slots like reference`() {
        val writes = mutableListOf<Write>()
        val pcm = byteArrayOf(
            0x01, 0x02, 0x03,
            0x04, 0x05, 0x06,
        )
        val packetizer = packetizer(
            sampleRate = 48_000,
            inputBytesPerSample = 3,
            inputBitDepth = 24,
            usbBytesPerSample = 4,
            usbBitResolution = 32,
            writes = writes,
        )

        packetizer.write(pcm)
        packetizer.flush()

        val head = writes.single().data.copyOfRange(0, 8)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x01, 0x02, 0x03,
                0x00, 0x04, 0x05, 0x06,
            ),
            head,
        )
    }

    @Test
    fun `explicit feedback controls packet frame count`() {
        val writes = mutableListOf<Write>()
        val pcm = ByteArray(49 * 2 * 2)
        val packetizer = UsbPcmIsoPacketizer(
            sampleRate = 48_000,
            packetsPerSecond = 1000,
            channels = 2,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 2,
            usbBitResolution = 16,
            feedbackFramesPerPacketQ16 = { 49 shl 16 },
        ) { data, packetLengths, packetCount ->
            writes += Write(data, packetLengths.copyOf(packetCount))
        }

        packetizer.write(pcm)
        packetizer.flush()

        assertArrayEquals(intArrayOf(49 * 4), writes.single().packetLengths)
        assertEquals(49 * 4, writes.single().data.size)
    }

    private fun packetizer(
        sampleRate: Int,
        inputBytesPerSample: Int,
        inputBitDepth: Int,
        usbBytesPerSample: Int,
        usbBitResolution: Int,
        writes: MutableList<Write>,
    ): UsbPcmIsoPacketizer = UsbPcmIsoPacketizer(
        sampleRate = sampleRate,
        packetsPerSecond = 1000,
        channels = 2,
        inputBytesPerSample = inputBytesPerSample,
        inputBitDepth = inputBitDepth,
        usbBytesPerSample = usbBytesPerSample,
        usbBitResolution = usbBitResolution,
    ) { data, packetLengths, packetCount ->
        writes += Write(data, packetLengths.copyOf(packetCount))
    }

    private data class Write(
        val data: ByteArray,
        val packetLengths: IntArray,
    )
}
