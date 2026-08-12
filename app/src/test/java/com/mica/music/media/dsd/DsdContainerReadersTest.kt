package com.mica.music.media.dsd

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdContainerReadersTest {

    @Test
    fun readerRetriesShortRandomAccessReadsAndClosesSourceOnNormalClose() {
        val canonical = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
            0x12, 0x22,
            0x13, 0x23,
        )
        val source = TrackingByteArraySource(
            bytes = buildDsf(canonical, bitsPerSample = 8, blockSize = 2),
            maxBytesPerRead = 1,
        )

        DsdContainerReaders.open(source).use { reader ->
            val output = ByteArray(canonical.size)
            assertEquals(4, reader.readFrames(output, maxFrames = 4))
            assertArrayEquals(canonical, output)
            assertTrue(source.readCalls > canonical.size)
            assertTrue(!source.closed)
        }

        assertTrue(source.closed)
    }

    @Test
    fun failedOpenClosesOwnedSource() {
        val source = TrackingByteArraySource("NOPE".toByteArray())

        val error = expectDsdFailure { DsdContainerReaders.open(source) }

        assertEquals(DsdContainerFailure.UNSUPPORTED, error.failure)
        assertTrue(source.closed)
    }

    @Test
    fun dsfThreeChannelReadCrossesPlanarBlockBoundariesWithoutChangingCanonicalOrder() {
        val canonical = byteArrayOf(
            0x10, 0x20, 0x30,
            0x11, 0x21, 0x31,
            0x12, 0x22, 0x32,
            0x13, 0x23, 0x33,
            0x14, 0x24, 0x34,
        )
        val reader = DsdContainerReaders.open(
            ByteArraySource(
                buildDsf(
                    canonical = canonical,
                    bitsPerSample = 1,
                    blockSize = 2,
                    channels = 3,
                ),
            ),
        )
        val output = ByteArray(canonical.size)

        assertEquals(3, reader.info.channelCount)
        assertEquals(5, reader.readFrames(output, maxFrames = 5))
        assertArrayEquals(canonical, output)
    }

    @Test
    fun trackedTagLibDsfAndDffFixturesOpenThroughLocalFileAdapter() {
        val dsf = trackedFixture("empty10ms.dsf")
        val dff = trackedFixture("empty10ms.dff")

        DsdContainerReaders.open(LocalFileByteSource(dsf)).use { reader ->
            assertEquals(DsdContainerType.DSF, reader.info.container)
            assertEquals(2, reader.info.channelCount)
            assertEquals(2_822_400, reader.info.sampleRateHz)
            assertEquals(28_224L, reader.info.sampleCountPerChannel)
            assertEquals(3_528L, reader.info.byteFrameCount)
        }
        DsdContainerReaders.open(LocalFileByteSource(dff)).use { reader ->
            assertEquals(DsdContainerType.DFF, reader.info.container)
            assertEquals(2, reader.info.channelCount)
            assertEquals(2_822_400, reader.info.sampleRateHz)
            assertEquals(28_224L, reader.info.sampleCountPerChannel)
            assertEquals(3_528L, reader.info.byteFrameCount)
        }
    }

    @Test
    fun dsfOneBitPlanarBlocksBecomeMsbFirstInterleaved() {
        val canonical = byteArrayOf(
            0x80.toByte(), 0x40,
            0x81.toByte(), 0x41,
            0x82.toByte(), 0x42,
            0x83.toByte(), 0x43,
            0x84.toByte(), 0x44,
            0x85.toByte(), 0x45,
        )
        val reader = DsdContainerReaders.open(
            ByteArraySource(buildDsf(canonical, bitsPerSample = 1, blockSize = 4)),
        )

        assertEquals(DsdContainerType.DSF, reader.info.container)
        assertEquals(DsdSourceBitOrder.LSB_FIRST, reader.info.sourceBitOrder)
        assertEquals(48L, reader.info.sampleCountPerChannel)

        val first = ByteArray(6)
        assertEquals(3, reader.readFrames(first, maxFrames = 3))
        assertArrayEquals(canonical.copyOfRange(0, 6), first)

        assertEquals(32L, reader.seekToSample(35L))
        val tail = ByteArray(4)
        assertEquals(2, reader.readFrames(tail, maxFrames = 2))
        assertArrayEquals(canonical.copyOfRange(8, 12), tail)
        assertEquals(0, reader.readFrames(ByteArray(2), maxFrames = 1))
    }

    @Test
    fun dsfEightBitSourceIsAlreadyCanonical() {
        val canonical = byteArrayOf(
            0x10, 0x20,
            0x11, 0x21,
            0x12, 0x22,
            0x13, 0x23,
        )
        val reader = DsdContainerReaders.open(
            ByteArraySource(buildDsf(canonical, bitsPerSample = 8, blockSize = 4)),
        )
        val output = ByteArray(canonical.size)

        assertEquals(DsdSourceBitOrder.MSB_FIRST, reader.info.sourceBitOrder)
        assertEquals(4, reader.readFrames(output, maxFrames = 4))
        assertArrayEquals(canonical, output)
    }

    @Test
    fun dsfTruncatedFinalStoredBlockFailsClosed() {
        val valid = buildDsf(
            canonical = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            bitsPerSample = 8,
            blockSize = 4,
        )
        val truncated = valid.copyOf(valid.size - 1)

        val error = expectDsdFailure { DsdContainerReaders.open(ByteArraySource(truncated)) }

        assertEquals(DsdContainerFailure.TRUNCATED, error.failure)
    }

    @Test
    fun dffRawDataPreservesClusteredMsbFirstFramesAndOddChunkPadding() {
        val canonical = byteArrayOf(
            0x70, 0x30,
            0x71, 0x31,
            0x72, 0x32,
            0x73, 0x33,
        )
        val reader = DsdContainerReaders.open(ByteArraySource(buildDff(canonical, compression = "DSD ")))

        assertEquals(DsdContainerType.DFF, reader.info.container)
        assertEquals(DsdSourceBitOrder.MSB_FIRST, reader.info.sourceBitOrder)
        assertEquals(32L, reader.info.sampleCountPerChannel)

        assertEquals(16L, reader.seekToSample(23L))
        val output = ByteArray(4)
        assertEquals(2, reader.readFrames(output, maxFrames = 2))
        assertArrayEquals(canonical.copyOfRange(4, 8), output)
    }

    @Test
    fun dffDstCompressionIsExplicitlyRejected() {
        val error = expectDsdFailure {
            DsdContainerReaders.open(
                ByteArraySource(buildDff(byteArrayOf(1, 2, 3, 4), compression = "DST ", soundChunk = "DST ")),
            )
        }

        assertEquals(DsdContainerFailure.DST_UNSUPPORTED, error.failure)
        assertTrue(error.message.orEmpty().contains("DST"))
    }

    @Test
    fun dffMalformedClusteredFrameFailsClosed() {
        val error = expectDsdFailure {
            DsdContainerReaders.open(ByteArraySource(buildDff(byteArrayOf(1, 2, 3), compression = "DSD ")))
        }

        assertEquals(DsdContainerFailure.MALFORMED, error.failure)
    }
}

private fun trackedFixture(name: String): File = listOf(
    File("third_party/taglib/src/main/cpp/taglib/tests/data/$name"),
    File("../third_party/taglib/src/main/cpp/taglib/tests/data/$name"),
).firstOrNull(File::isFile) ?: error("Tracked TagLib DSD fixture is missing: $name")

private class ByteArraySource(
    private val bytes: ByteArray,
) : SeekableByteSource {
    override val length: Long = bytes.size.toLong()
    override val identity: ByteSourceIdentity = ByteSourceIdentity("test-bytes")

    override fun readAt(position: Long, destination: ByteArray, destinationOffset: Int, byteCount: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, bytes.size - position.toInt())
        bytes.copyInto(destination, destinationOffset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() = Unit
}

private class TrackingByteArraySource(
    private val bytes: ByteArray,
    private val maxBytesPerRead: Int = Int.MAX_VALUE,
) : SeekableByteSource {
    override val length: Long = bytes.size.toLong()
    override val identity: ByteSourceIdentity = ByteSourceIdentity("tracking-test-bytes")
    var closed: Boolean = false
        private set
    var readCalls: Int = 0
        private set

    override fun readAt(position: Long, destination: ByteArray, destinationOffset: Int, byteCount: Int): Int {
        check(!closed) { "read after close" }
        readCalls++
        if (position >= bytes.size) return -1
        val count = minOf(byteCount, maxBytesPerRead, bytes.size - position.toInt())
        bytes.copyInto(destination, destinationOffset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
    }
}

private fun buildDsf(
    canonical: ByteArray,
    bitsPerSample: Int,
    blockSize: Int,
    channels: Int = 2,
    sampleRateHz: Int = 2_822_400,
): ByteArray {
    require(canonical.size % channels == 0)
    val frames = canonical.size / channels
    val blockCount = (frames + blockSize - 1) / blockSize
    val payload = ByteArray(blockCount * blockSize * channels)
    repeat(blockCount) { block ->
        repeat(channels) { channel ->
            repeat(blockSize) { inBlock ->
                val frame = block * blockSize + inBlock
                if (frame < frames) {
                    val canonicalByte = canonical[frame * channels + channel]
                    payload[(block * channels + channel) * blockSize + inBlock] = if (bitsPerSample == 1) {
                        reverseBits(canonicalByte)
                    } else {
                        canonicalByte
                    }
                }
            }
        }
    }

    val headerSize = 92
    val fileSize = headerSize + payload.size
    val result = ByteArray(fileSize)
    "DSD ".toByteArray().copyInto(result, 0)
    putU64Le(result, 4, 28L)
    putU64Le(result, 12, fileSize.toLong())
    putU64Le(result, 20, 0L)
    "fmt ".toByteArray().copyInto(result, 28)
    putU64Le(result, 32, 52L)
    putU32Le(result, 40, 1)
    putU32Le(result, 44, 0)
    putU32Le(result, 48, 2)
    putU32Le(result, 52, channels)
    putU32Le(result, 56, sampleRateHz)
    putU32Le(result, 60, bitsPerSample)
    putU64Le(result, 64, frames.toLong() * 8L)
    putU32Le(result, 72, blockSize)
    putU32Le(result, 76, 0)
    "data".toByteArray().copyInto(result, 80)
    putU64Le(result, 84, payload.size.toLong() + 12L)
    payload.copyInto(result, headerSize)
    return result
}

private fun buildDff(
    canonical: ByteArray,
    compression: String,
    soundChunk: String = if (compression == "DST ") "DST " else "DSD ",
    channels: Int = 2,
    sampleRateHz: Int = 2_822_400,
): ByteArray {
    val fs = chunkBe("FS  ", u32Be(sampleRateHz))
    val channelIds = buildList<Byte> {
        addAll(u16Be(channels).toList())
        repeat(channels) { channel ->
            addAll(if (channel == 0) "SLFT".toByteArray().toList() else "SRGT".toByteArray().toList())
        }
    }.toByteArray()
    val chnl = chunkBe("CHNL", channelIds)
    val cmpr = chunkBe("CMPR", compression.toByteArray() + byteArrayOf(0))
    val unknownOdd = chunkBe("ABCD", byteArrayOf(0x55))
    val prop = chunkBe("PROP", "SND ".toByteArray() + fs + unknownOdd + chnl + cmpr)
    val topUnknownOdd = chunkBe("JUNK", byteArrayOf(0x33))
    val sound = chunkBe(soundChunk, canonical)
    val formPayload = "DSD ".toByteArray() + topUnknownOdd + prop + sound
    return "FRM8".toByteArray() + u64Be(formPayload.size.toLong()) + formPayload
}

private fun chunkBe(id: String, payload: ByteArray): ByteArray {
    val pad = if ((payload.size and 1) == 0) byteArrayOf() else byteArrayOf(0)
    return id.toByteArray() + u64Be(payload.size.toLong()) + payload + pad
}

private fun reverseBits(value: Byte): Byte =
    (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()

private fun putU32Le(bytes: ByteArray, offset: Int, value: Int) {
    repeat(4) { index -> bytes[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte() }
}

private fun putU64Le(bytes: ByteArray, offset: Int, value: Long) {
    repeat(8) { index -> bytes[offset + index] = ((value ushr (8 * index)) and 0xFF).toByte() }
}

private fun u16Be(value: Int): ByteArray = byteArrayOf(
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private fun u32Be(value: Int): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private fun u64Be(value: Long): ByteArray = ByteArray(8) { index ->
    ((value ushr (8 * (7 - index))) and 0xFF).toByte()
}

private inline fun expectDsdFailure(block: () -> Unit): DsdContainerException = try {
    block()
    throw AssertionError("Expected DsdContainerException")
} catch (error: DsdContainerException) {
    error
}
