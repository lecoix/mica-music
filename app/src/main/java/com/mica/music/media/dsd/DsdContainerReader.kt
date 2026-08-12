package com.mica.music.media.dsd

import java.io.Closeable

/** Canonical P5 output is always MSB-first and byte-interleaved by channel. */
enum class DsdContainerType {
    DSF,
    DFF,
}

enum class DsdSourceBitOrder {
    LSB_FIRST,
    MSB_FIRST,
}

enum class DsdContainerFailure {
    MALFORMED,
    TRUNCATED,
    UNSUPPORTED,
    DST_UNSUPPORTED,
}

class DsdContainerException(
    val failure: DsdContainerFailure,
    message: String,
) : Exception(message)

data class DsdStreamInfo(
    val container: DsdContainerType,
    val sampleRateHz: Int,
    val channelCount: Int,
    val sampleCountPerChannel: Long,
    val sourceBitOrder: DsdSourceBitOrder,
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
        require(sampleCountPerChannel >= 0L)
    }

    /** One byte-frame contains one DSD byte (8 samples) for every channel. */
    val byteFrameCount: Long
        get() = ceilDivNonNegative(sampleCountPerChannel, 8L)

    val durationUs: Long
        get() {
            val wholeSeconds = sampleCountPerChannel / sampleRateHz
            val remainingSamples = sampleCountPerChannel % sampleRateHz
            if (wholeSeconds > Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE
            val wholeUs = wholeSeconds * 1_000_000L
            val partialUs = remainingSamples * 1_000_000L / sampleRateHz
            return if (wholeUs > Long.MAX_VALUE - partialUs) Long.MAX_VALUE else wholeUs + partialUs
        }
}

interface DsdContainerReader : Closeable {
    val sourceIdentity: ByteSourceIdentity
    val info: DsdStreamInfo
    val framePosition: Long

    /**
     * Reads up to [maxFrames] canonical byte-frames into [destination].
     *
     * A returned frame is `channelCount` bytes in source channel order. All bytes are MSB-first,
     * regardless of the container's on-disk bit order. Returns 0 at EOF.
     */
    fun readFrames(
        destination: ByteArray,
        destinationOffset: Int = 0,
        maxFrames: Int,
    ): Int

    /** Seeks to the DSD byte boundary at or before [sampleIndex], returning the aligned sample. */
    fun seekToSample(sampleIndex: Long): Long
}

object DsdContainerReaders {
    fun open(source: SeekableByteSource): DsdContainerReader {
        try {
            val signature = source.readFullyAt(0L, 4).decodeAscii()
            return when (signature) {
                "DSD " -> DsfRawDsdReader.open(source)
                "FRM8" -> DffRawDsdReader.open(source)
                else -> throw DsdContainerException(
                    DsdContainerFailure.UNSUPPORTED,
                    "Unsupported DSD container signature: $signature",
                )
            }
        } catch (error: Exception) {
            runCatching { source.close() }
            throw error
        }
    }
}

internal fun ByteArray.decodeAscii(offset: Int = 0, length: Int = size - offset): String =
    String(this, offset, length, Charsets.US_ASCII)

internal fun ByteArray.u16Be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.u32Le(offset: Int): Long =
    (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)

internal fun ByteArray.u32Be(offset: Int): Long =
    ((this[offset].toLong() and 0xFFL) shl 24) or
        ((this[offset + 1].toLong() and 0xFFL) shl 16) or
        ((this[offset + 2].toLong() and 0xFFL) shl 8) or
        (this[offset + 3].toLong() and 0xFFL)

internal fun ByteArray.u64Le(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = value or ((this[offset + index].toLong() and 0xFFL) shl (8 * index))
    }
    if (value < 0L) {
        throw DsdContainerException(DsdContainerFailure.MALFORMED, "Unsigned 64-bit value exceeds Long range")
    }
    return value
}

internal fun ByteArray.u64Be(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFFL)
    }
    if (value < 0L) {
        throw DsdContainerException(DsdContainerFailure.MALFORMED, "Unsigned 64-bit value exceeds Long range")
    }
    return value
}

internal fun checkedAdd(base: Long, delta: Long, label: String): Long {
    if (base < 0L || delta < 0L || base > Long.MAX_VALUE - delta) {
        throw DsdContainerException(DsdContainerFailure.MALFORMED, "$label overflows file offset")
    }
    return base + delta
}

internal fun checkedMultiply(left: Long, right: Long, label: String): Long {
    if (left < 0L || right < 0L || (left != 0L && right > Long.MAX_VALUE / left)) {
        throw DsdContainerException(DsdContainerFailure.MALFORMED, "$label overflows")
    }
    return left * right
}

internal fun ceilDivNonNegative(value: Long, divisor: Long): Long {
    require(value >= 0L && divisor > 0L)
    return value / divisor + if (value % divisor == 0L) 0L else 1L
}
