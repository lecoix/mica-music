package com.afalphy.sylvakru

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPcmIsoPacketizerTest {

    @Test
    fun `native frame fifo mode converts slots without pre-packetizing`() {
        val frameWrites = mutableListOf<ByteArray>()
        val packetizer = UsbPcmIsoPacketizer(
            sampleRate = 48_000,
            packetsPerSecond = 8_000,
            channels = 2,
            inputBytesPerSample = 3,
            inputBitDepth = 24,
            usbBytesPerSample = 4,
            usbBitResolution = 32,
            writeFrames = { frameWrites += it },
        )

        packetizer.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        packetizer.writeUsbSilence(1)
        packetizer.flush()

        assertEquals(2, frameWrites.size)
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 0, 4, 5, 6),
            frameWrites[0],
        )
        assertArrayEquals(ByteArray(8), frameWrites[1])
    }

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
    fun `full-speed policy can keep each transfer near two milliseconds`() {
        val writes = mutableListOf<Write>()
        val packetizer = UsbPcmIsoPacketizer(
            sampleRate = 48_000,
            packetsPerSecond = 1_000,
            channels = 2,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 2,
            usbBitResolution = 16,
            packetsPerTransfer = 2,
        ) { data, packetLengths, packetCount ->
            writes += Write(data, packetLengths.copyOf(packetCount))
        }

        packetizer.write(ByteArray(4 * 48 * 2 * 2))

        assertEquals(2, writes.size)
        assertTrue(writes.all { it.packetLengths.contentEquals(intArrayOf(192, 192)) })
    }

    @Test
    fun `failed transport callback does not leave a full transfer in packetizer`() {
        val writes = mutableListOf<Write>()
        var attempt = 0
        val packetizer = UsbPcmIsoPacketizer(
            sampleRate = 48_000,
            packetsPerSecond = 1_000,
            channels = 2,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 2,
            usbBitResolution = 16,
            packetsPerTransfer = 2,
        ) { data, packetLengths, packetCount ->
            if (attempt++ == 0) error("transport failed")
            writes += Write(data, packetLengths.copyOf(packetCount))
        }

        try {
            packetizer.write(ByteArray(2 * 48 * 2 * 2))
        } catch (_: IllegalStateException) {
            // The next write must start from an empty transfer.
        }
        packetizer.write(ByteArray(2 * 48 * 2 * 2))

        assertEquals(1, writes.size)
        assertArrayEquals(intArrayOf(192, 192), writes.single().packetLengths)
    }

    @Test
    fun `16-bit PCM is losslessly left aligned into 24-bit USB slots`() {
        val writes = mutableListOf<Write>()
        val pcm = byteArrayOf(0x01, 0x02, 0x04, 0x05)
        val packetizer = packetizer(
            sampleRate = 48_000,
            inputBytesPerSample = 2,
            inputBitDepth = 16,
            usbBytesPerSample = 3,
            usbBitResolution = 24,
            writes = writes,
        )

        packetizer.write(pcm)
        packetizer.flush()

        assertArrayEquals(
            byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x04, 0x05),
            writes.single().data.copyOfRange(0, 6),
        )
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

    @Test
    fun `missing feedback falls back to nominal cadence`() {
        val writes = mutableListOf<Write>()
        val packetizer = feedbackPacketizer(
            feedback = { 0 },
            writes = writes,
        )

        packetizer.write(ByteArray(48 * 2 * 2))
        packetizer.flush()

        assertArrayEquals(intArrayOf(48 * 4), writes.single().packetLengths)
    }

    @Test
    fun `malformed feedback outside safety window falls back to nominal cadence`() {
        val writes = mutableListOf<Write>()
        val feedbackValues = ArrayDeque(listOf(1 shl 16, 96 shl 16))
        val packetizer = feedbackPacketizer(
            feedback = { feedbackValues.removeFirst() },
            writes = writes,
        )

        packetizer.write(ByteArray(96 * 2 * 2))
        packetizer.flush()

        assertArrayEquals(intArrayOf(48 * 4, 48 * 4), writes.single().packetLengths)
    }

    @Test
    fun `feedback resumes after a long run of missing samples without cadence drift`() {
        val writes = mutableListOf<Write>()
        var poll = 0
        val packetizer = feedbackPacketizer(
            feedback = {
                poll++
                if (poll <= 10_000) 0 else 49 shl 16
            },
            writes = writes,
        )

        packetizer.write(ByteArray((10_000 * 48 + 49) * 2 * 2))
        packetizer.flush()

        val lengths = writes.flatMap { it.packetLengths.asIterable() }
        assertEquals(10_001, lengths.size)
        assertTrue(lengths.take(10_000).all { it == 48 * 4 })
        assertEquals(49 * 4, lengths.last())
    }

    private fun feedbackPacketizer(
        feedback: () -> Int,
        writes: MutableList<Write>,
    ): UsbPcmIsoPacketizer = UsbPcmIsoPacketizer(
        sampleRate = 48_000,
        packetsPerSecond = 1000,
        channels = 2,
        inputBytesPerSample = 2,
        inputBitDepth = 16,
        usbBytesPerSample = 2,
        usbBitResolution = 16,
        feedbackFramesPerPacketQ16 = feedback,
    ) { data, packetLengths, packetCount ->
        writes += Write(data, packetLengths.copyOf(packetCount))
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
