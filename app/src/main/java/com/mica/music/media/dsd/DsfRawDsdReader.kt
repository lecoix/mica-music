package com.mica.music.media.dsd

import kotlin.math.min

internal class DsfRawDsdReader private constructor(
    private val source: SeekableByteSource,
    override val info: DsdStreamInfo,
    private val blockSizePerChannel: Int,
    private val dataPayloadOffset: Long,
) : DsdContainerReader {
    override val sourceIdentity: ByteSourceIdentity = source.identity
    override var framePosition: Long = 0L
        private set

    override fun readFrames(destination: ByteArray, destinationOffset: Int, maxFrames: Int): Int {
        require(destinationOffset >= 0 && maxFrames >= 0)
        require(maxFrames <= (destination.size - destinationOffset) / info.channelCount) {
            "destination cannot hold $maxFrames DSD frames"
        }
        if (maxFrames == 0 || framePosition >= info.byteFrameCount) return 0

        var outputOffset = destinationOffset
        var framesRead = 0
        val remaining = min(maxFrames.toLong(), info.byteFrameCount - framePosition).toInt()
        while (framesRead < remaining) {
            val absoluteFrame = framePosition + framesRead
            val blockIndex = absoluteFrame / blockSizePerChannel
            val frameInBlock = (absoluteFrame % blockSizePerChannel).toInt()
            val segmentFrames = min(remaining - framesRead, blockSizePerChannel - frameInBlock)
            val blockStride = checkedMultiply(
                blockSizePerChannel.toLong(),
                info.channelCount.toLong(),
                "DSF block stride",
            )
            val blockBase = checkedAdd(
                dataPayloadOffset,
                checkedMultiply(blockIndex, blockStride, "DSF block offset"),
                "DSF block base",
            )
            val channelSegments = Array(info.channelCount) { channel ->
                val channelBase = checkedAdd(
                    blockBase,
                    checkedMultiply(channel.toLong(), blockSizePerChannel.toLong(), "DSF channel offset"),
                    "DSF channel base",
                )
                source.readFullyAt(channelBase + frameInBlock, segmentFrames)
            }

            for (frame in 0 until segmentFrames) {
                for (channel in 0 until info.channelCount) {
                    val raw = channelSegments[channel][frame]
                    destination[outputOffset++] = if (info.sourceBitOrder == DsdSourceBitOrder.LSB_FIRST) {
                        reverseBits(raw)
                    } else {
                        raw
                    }
                }
            }
            framesRead += segmentFrames
        }
        framePosition += framesRead
        return framesRead
    }

    override fun seekToSample(sampleIndex: Long): Long {
        require(sampleIndex >= 0L) { "sampleIndex must be non-negative" }
        val clamped = min(sampleIndex, info.sampleCountPerChannel)
        val aligned = (clamped / 8L) * 8L
        framePosition = min(aligned / 8L, info.byteFrameCount)
        return aligned
    }

    override fun close() {
        source.close()
    }

    companion object {
        private const val DSD_CHUNK_SIZE = 28L
        private const val FMT_CHUNK_SIZE = 52L
        private const val DATA_HEADER_SIZE = 12L
        private const val HEADER_SIZE = 92

        fun open(source: SeekableByteSource): DsfRawDsdReader {
            val header = source.readFullyAt(0L, HEADER_SIZE)
            requireAscii(header, 0, "DSD ", "DSF DSD chunk")
            if (header.u64Le(4) != DSD_CHUNK_SIZE) {
                malformed("Unexpected DSF DSD chunk size")
            }
            val declaredFileSize = header.u64Le(12)
            if (declaredFileSize < HEADER_SIZE) malformed("Invalid DSF declared file size: $declaredFileSize")
            source.length?.let { actual ->
                if (declaredFileSize > actual) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSF declares $declaredFileSize bytes but source has $actual",
                    )
                }
            }

            requireAscii(header, 28, "fmt ", "DSF fmt chunk")
            if (header.u64Le(32) != FMT_CHUNK_SIZE) malformed("Unexpected DSF fmt chunk size")
            val formatVersion = header.u32Le(40)
            val formatId = header.u32Le(44)
            if (formatVersion != 1L || formatId != 0L) {
                throw DsdContainerException(
                    DsdContainerFailure.UNSUPPORTED,
                    "Unsupported DSF format version/id: $formatVersion/$formatId",
                )
            }
            val channelCount = positiveInt(header.u32Le(52), "DSF channel count")
            val sampleRateHz = positiveInt(header.u32Le(56), "DSF sample rate")
            val bitsPerSample = header.u32Le(60).toInt()
            if (bitsPerSample != 1 && bitsPerSample != 8) {
                throw DsdContainerException(
                    DsdContainerFailure.UNSUPPORTED,
                    "Unsupported DSF bit order flag: $bitsPerSample",
                )
            }
            val sampleCount = header.u64Le(64)
            val blockSize = positiveInt(header.u32Le(72), "DSF block size")

            requireAscii(header, 80, "data", "DSF data chunk")
            val dataChunkSize = header.u64Le(84)
            if (dataChunkSize < DATA_HEADER_SIZE) malformed("DSF data chunk is smaller than its header")
            val payloadBytes = dataChunkSize - DATA_HEADER_SIZE
            val byteFrames = ceilDivNonNegative(sampleCount, 8L)
            val blockCount = ceilDivNonNegative(byteFrames, blockSize.toLong())
            val storedBytesNeeded = checkedMultiply(
                checkedMultiply(blockCount, blockSize.toLong(), "DSF stored blocks"),
                channelCount.toLong(),
                "DSF stored payload",
            )
            if (payloadBytes < storedBytesNeeded) {
                throw DsdContainerException(
                    DsdContainerFailure.TRUNCATED,
                    "DSF payload has $payloadBytes bytes; block layout needs $storedBytesNeeded",
                )
            }
            val payloadEnd = checkedAdd(HEADER_SIZE.toLong(), payloadBytes, "DSF data end")
            if (payloadEnd > declaredFileSize) {
                malformed("DSF data chunk ends at $payloadEnd beyond declared file size $declaredFileSize")
            }
            source.length?.let { actual ->
                if (payloadEnd > actual) {
                    throw DsdContainerException(
                        DsdContainerFailure.TRUNCATED,
                        "DSF data chunk ends at $payloadEnd but source has $actual bytes",
                    )
                }
            }

            return DsfRawDsdReader(
                source = source,
                info = DsdStreamInfo(
                    container = DsdContainerType.DSF,
                    sampleRateHz = sampleRateHz,
                    channelCount = channelCount,
                    sampleCountPerChannel = sampleCount,
                    sourceBitOrder = if (bitsPerSample == 1) {
                        DsdSourceBitOrder.LSB_FIRST
                    } else {
                        DsdSourceBitOrder.MSB_FIRST
                    },
                ),
                blockSizePerChannel = blockSize,
                dataPayloadOffset = HEADER_SIZE.toLong(),
            )
        }

        private fun requireAscii(bytes: ByteArray, offset: Int, expected: String, label: String) {
            if (bytes.decodeAscii(offset, expected.length) != expected) malformed("Invalid $label")
        }

        private fun positiveInt(value: Long, label: String): Int {
            if (value <= 0L || value > Int.MAX_VALUE) malformed("Invalid $label: $value")
            return value.toInt()
        }

        private fun malformed(message: String): Nothing =
            throw DsdContainerException(DsdContainerFailure.MALFORMED, message)

        private fun reverseBits(value: Byte): Byte =
            (Integer.reverse(value.toInt() and 0xFF) ushr 24).toByte()
    }
}
