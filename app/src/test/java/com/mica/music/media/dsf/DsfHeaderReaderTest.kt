package com.mica.music.media.dsf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsfHeaderReaderTest {

    @Test
    fun parsesSyntheticHeader() {
        val dsf = buildDsdChunk(totalSize = 1_000L, metadataPointer = 900L)
        val fmt = buildFmtChunk(
            channels = 2,
            sampleRateHz = 2_822_400,
            sampleCount = 2_822_400L,
        )
        val data = buildDataChunk(payloadBytes = 705_600L)

        val parsed = DsfHeaderReader.parse(dsf, fmt, data)

        assertEquals(2, parsed.channelCount)
        assertEquals(2_822_400, parsed.sampleRateHz)
        assertEquals(352_800, parsed.decoderSampleRateHz)
        assertEquals(2_822_400L, parsed.sampleCount)
        assertEquals("DSD64", parsed.dsdLabel)
        assertEquals(1_000_000L, parsed.durationUs)
        assertEquals(900L, parsed.metadataPointer)
        assertEquals(92L, parsed.dataPayloadOffset)
    }

    @Test
    fun readsLocalFixtureWhenPresent() {
        val fixture = File(".test-music/09.Count Down.dsf")
        if (!fixture.exists()) return

        val parsed = fixture.inputStream().use(DsfHeaderReader::read)

        assertEquals(614_482_584L, parsed.totalFileSize)
        assertEquals(614_424_668L, parsed.metadataPointer)
        assertEquals(2, parsed.channelCount)
        assertEquals(11_289_600, parsed.sampleRateHz)
        assertEquals("DSD256", parsed.dsdLabel)
        assertEquals(1_411_200, parsed.decoderSampleRateHz)
        assertEquals(2_457_670_656L, parsed.sampleCount)
        assertEquals(217_693L, parsed.durationMs)
        assertTrue(
            (parsed.sampleCount * parsed.channelCount / 8L) <=
                (parsed.dataChunkSize - DsfFormat.DATA_HEADER_SIZE),
        )
    }
}

class DsfSeekMathTest {

    @Test
    fun seekOffsetsMatchDsfPayloadLayout() {
        val format = DsfHeaderReader.parse(
            buildDsdChunk(totalSize = 614_482_584L, metadataPointer = 614_424_668L),
            buildFmtChunk(
                channels = 2,
                sampleRateHz = 11_289_600,
                sampleCount = 2_457_670_656L,
            ),
            buildDataChunk(payloadBytes = 614_424_576L),
        )

        assertEquals(92L, format.dataPayloadOffset)
        assertEquals(0L, DsfSeekMath.payloadByteOffset(format, 0L))
        assertEquals(
            614_417_664L,
            DsfSeekMath.payloadByteOffset(format, 2_457_670_656L),
        )
        assertEquals(
            614_416_476L,
            DsfSeekMath.fileOffsetForSampleIndex(format, 2_457_670_656L),
        )
        assertEquals(
            337_494_108L,
            DsfSeekMath.fileOffsetForSampleIndex(format, 1_350_000_000L),
        )
        assertEquals(
            1_354_727_424L,
            DsfSeekMath.sampleIndexForPositionMs(format, 120_000L),
        )
        assertEquals(
            0L,
            (DsfSeekMath.fileOffsetForSampleIndex(format, 1_350_000_000L) -
                format.dataPayloadOffset) % format.blockAlign,
        )
    }

    @Test
    fun localFixtureSeekChecksWhenPresent() {
        val fixture = File(".test-music/09.Count Down.dsf")
        if (!fixture.exists()) return

        val parsed = fixture.inputStream().use(DsfHeaderReader::read)
        val at60s = DsfSeekMath.fileOffsetForSampleIndex(
            parsed,
            DsfSeekMath.sampleIndexForPositionMs(parsed, 60_000L),
        )
        assertTrue(at60s > parsed.dataPayloadOffset)
        assertTrue(at60s < parsed.metadataPointer)
    }
}

private fun buildDsdChunk(totalSize: Long, metadataPointer: Long): ByteArray =
    byteArrayOf(
        'D'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), ' '.code.toByte(),
        *ulongLE(28L),
        *ulongLE(totalSize),
        *ulongLE(metadataPointer),
    )

private fun buildFmtChunk(
    channels: Int,
    sampleRateHz: Int,
    sampleCount: Long,
): ByteArray {
    val bytes = ByteArray(52)
    "fmt ".toByteArray().copyInto(bytes, 0)
    ulongLE(52L).copyInto(bytes, 4)
    uintLE(1).copyInto(bytes, 12)
    uintLE(0).copyInto(bytes, 16)
    uintLE(2).copyInto(bytes, 20)
    uintLE(channels).copyInto(bytes, 24)
    uintLE(sampleRateHz).copyInto(bytes, 28)
    uintLE(1).copyInto(bytes, 32)
    ulongLE(sampleCount).copyInto(bytes, 36)
    uintLE(4096).copyInto(bytes, 44)
    return bytes
}

private fun buildDataChunk(payloadBytes: Long): ByteArray =
    byteArrayOf(
        'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
        *ulongLE(payloadBytes + 12L),
    )

private fun uintLE(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

private fun ulongLE(value: Long): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 56) and 0xFF).toByte(),
    )
