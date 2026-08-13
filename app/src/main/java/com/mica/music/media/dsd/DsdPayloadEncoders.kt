package com.mica.music.media.dsd

/** DSD idle pattern used only to complete an otherwise partial logical frame. */
const val DSD_IDLE_BYTE: Byte = 0x69

enum class DoPCarrierPacking(val bytesPerChannel: Int) {
    /** 24 valid bits packed into three little-endian PCM bytes. */
    PACKED_24_LE(3),

    /** 24 valid bits occupy bits 31..8 of a four-byte little-endian subslot. */
    SLOT_32_LE_MSB_ALIGNED(4),

    /** 24 valid bits occupy bits 23..0 of a four-byte little-endian subslot. */
    SLOT_32_LE_LSB_ALIGNED(4),
}

/**
 * Stateful logical DoP encoder.
 *
 * Input is canonical DSD byte-frames (`L0 R0 L1 R1 ...`). Output is one 24-bit logical word per
 * channel where bits 23..16 are the marker and bits 15..0 are the two chronological DSD bytes.
 * USB/PCM subslot packing is deliberately a separate explicit step.
 */
class DoPEncoder(
    val channelCount: Int,
    initialMarker: Int = MARKER_A,
) {
    init {
        require(channelCount > 0)
        require(initialMarker == MARKER_A || initialMarker == MARKER_B)
    }

    private val carriedFrame = ByteArray(channelCount)
    private var hasCarriedFrame = false
    var marker: Int = initialMarker
        private set

    /**
     * Encodes complete canonical byte-frames into [destinationWords].
     * Returns the number of DoP carrier frames produced, not the number of words.
     */
    fun encodeFrames(
        source: ByteArray,
        sourceOffset: Int = 0,
        frameCount: Int,
        destinationWords: IntArray,
        destinationWordOffset: Int = 0,
    ): Int {
        require(sourceOffset >= 0 && frameCount >= 0)
        require(frameCount <= (source.size - sourceOffset) / channelCount) { "source frame range out of bounds" }
        val maximumOutputFrames = (frameCount + if (hasCarriedFrame) 1 else 0) / 2
        require(maximumOutputFrames <= (destinationWords.size - destinationWordOffset) / channelCount) {
            "destination cannot hold encoded DoP frames"
        }

        var sourceFrame = 0
        var outputFrame = 0
        var outputWord = destinationWordOffset

        if (hasCarriedFrame && frameCount > 0) {
            writeCarrierFrame(
                first = carriedFrame,
                secondSource = source,
                secondOffset = sourceOffset,
                destination = destinationWords,
                destinationOffset = outputWord,
            )
            outputWord += channelCount
            outputFrame++
            sourceFrame++
            hasCarriedFrame = false
        }

        while (sourceFrame + 1 < frameCount) {
            val firstOffset = sourceOffset + sourceFrame * channelCount
            val secondOffset = firstOffset + channelCount
            for (channel in 0 until channelCount) {
                destinationWords[outputWord + channel] = logicalWord(
                    marker = marker,
                    olderByte = source[firstOffset + channel],
                    newerByte = source[secondOffset + channel],
                )
            }
            advanceMarker()
            outputWord += channelCount
            outputFrame++
            sourceFrame += 2
        }

        if (sourceFrame < frameCount) {
            val lastOffset = sourceOffset + sourceFrame * channelCount
            source.copyInto(carriedFrame, 0, lastOffset, lastOffset + channelCount)
            hasCarriedFrame = true
        }
        return outputFrame
    }

    /** Completes one pending half-frame with DSD idle. Returns 1 if a carrier frame was emitted. */
    fun drain(destinationWords: IntArray, destinationWordOffset: Int = 0): Int {
        if (!hasCarriedFrame) return 0
        require(destinationWordOffset >= 0 && destinationWordOffset + channelCount <= destinationWords.size)
        for (channel in 0 until channelCount) {
            destinationWords[destinationWordOffset + channel] = logicalWord(
                marker = marker,
                olderByte = carriedFrame[channel],
                newerByte = DSD_IDLE_BYTE,
            )
        }
        hasCarriedFrame = false
        advanceMarker()
        return 1
    }

    fun hasPendingHalfFrame(): Boolean = hasCarriedFrame

    /**
     * Emits one valid DoP idle runtime frame without touching any pending content half-frame.
     * All channels use the current marker and `0x69/0x69` DSD payload, then marker phase advances
     * exactly once for the runtime frame.
     */
    fun encodeIdleFrame(
        destinationWords: IntArray,
        destinationWordOffset: Int = 0,
    ): Int {
        require(destinationWordOffset >= 0 && destinationWordOffset + channelCount <= destinationWords.size)
        for (channel in 0 until channelCount) {
            destinationWords[destinationWordOffset + channel] = logicalWord(
                marker = marker,
                olderByte = DSD_IDLE_BYTE,
                newerByte = DSD_IDLE_BYTE,
            )
        }
        advanceMarker()
        return 1
    }

    private fun writeCarrierFrame(
        first: ByteArray,
        secondSource: ByteArray,
        secondOffset: Int,
        destination: IntArray,
        destinationOffset: Int,
    ) {
        for (channel in 0 until channelCount) {
            destination[destinationOffset + channel] = logicalWord(
                marker = marker,
                olderByte = first[channel],
                newerByte = secondSource[secondOffset + channel],
            )
        }
        advanceMarker()
    }

    private fun advanceMarker() {
        marker = if (marker == MARKER_A) MARKER_B else MARKER_A
    }

    companion object {
        const val MARKER_A = 0x05
        const val MARKER_B = 0xFA

        fun carrierFrameRate(dsdBitRateHz: Int): Int {
            require(dsdBitRateHz > 0 && dsdBitRateHz % 16 == 0) {
                "DSD bit rate must be exactly divisible by 16 for DoP"
            }
            return dsdBitRateHz / 16
        }

        fun logicalWord(marker: Int, olderByte: Byte, newerByte: Byte): Int =
            ((marker and 0xFF) shl 16) or
                ((olderByte.toInt() and 0xFF) shl 8) or
                (newerByte.toInt() and 0xFF)

        fun packWords(
            words: IntArray,
            wordOffset: Int = 0,
            wordCount: Int = words.size - wordOffset,
            packing: DoPCarrierPacking,
            destination: ByteArray,
            destinationOffset: Int = 0,
        ): Int {
            require(wordOffset >= 0 && wordCount >= 0 && wordOffset + wordCount <= words.size)
            val byteCount = wordCount * packing.bytesPerChannel
            require(destinationOffset >= 0 && destinationOffset + byteCount <= destination.size)
            var out = destinationOffset
            repeat(wordCount) { index ->
                val word = words[wordOffset + index]
                require(word in 0..0xFFFFFF) { "DoP word exceeds 24 bits" }
                val low = (word and 0xFF).toByte()
                val mid = ((word ushr 8) and 0xFF).toByte()
                val high = ((word ushr 16) and 0xFF).toByte()
                when (packing) {
                    DoPCarrierPacking.PACKED_24_LE -> {
                        destination[out++] = low
                        destination[out++] = mid
                        destination[out++] = high
                    }
                    DoPCarrierPacking.SLOT_32_LE_MSB_ALIGNED -> {
                        destination[out++] = 0
                        destination[out++] = low
                        destination[out++] = mid
                        destination[out++] = high
                    }
                    DoPCarrierPacking.SLOT_32_LE_LSB_ALIGNED -> {
                        destination[out++] = low
                        destination[out++] = mid
                        destination[out++] = high
                        destination[out++] = 0
                    }
                }
            }
            return byteCount
        }
    }
}

enum class NativeDsdFraming(val bytesPerSample: Int, val littleEndian: Boolean) {
    U8(1, false),
    U16_LE(2, true),
    U32_LE(4, true),
    U32_BE(4, false),
}

/**
 * Candidate Native-DSD byte packer. Selection of a framing is an external capability decision.
 * RAW_DATA descriptors alone must never choose one of these values.
 */
class NativeDsdEncoder(
    val channelCount: Int,
    val framing: NativeDsdFraming,
) {
    init {
        require(channelCount > 0)
    }

    private val pending = ByteArray(channelCount * framing.bytesPerSample)
    private var pendingFrameCount = 0

    /** Returns the number of Native runtime frames written. */
    fun encodeFrames(
        source: ByteArray,
        sourceOffset: Int = 0,
        frameCount: Int,
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): Int {
        require(sourceOffset >= 0 && frameCount >= 0)
        require(frameCount <= (source.size - sourceOffset) / channelCount) { "source frame range out of bounds" }
        val totalFramesAvailable = pendingFrameCount + frameCount
        val outputFrames = totalFramesAvailable / framing.bytesPerSample
        val outputBytes = outputFrames * channelCount * framing.bytesPerSample
        require(destinationOffset >= 0 && destinationOffset + outputBytes <= destination.size) {
            "destination cannot hold encoded Native DSD frames"
        }

        var sourceFrameIndex = 0
        var outputOffset = destinationOffset
        var producedFrames = 0

        if (pendingFrameCount > 0) {
            while (pendingFrameCount < framing.bytesPerSample && sourceFrameIndex < frameCount) {
                val inputOffset = sourceOffset + sourceFrameIndex * channelCount
                source.copyInto(
                    pending,
                    pendingFrameCount * channelCount,
                    inputOffset,
                    inputOffset + channelCount,
                )
                pendingFrameCount++
                sourceFrameIndex++
            }
            if (pendingFrameCount == framing.bytesPerSample) {
                outputOffset = writeNativeFrame(pending, 0, destination, outputOffset)
                pendingFrameCount = 0
                producedFrames++
            }
        }

        while (sourceFrameIndex + framing.bytesPerSample <= frameCount) {
            val inputOffset = sourceOffset + sourceFrameIndex * channelCount
            outputOffset = writeNativeFrame(source, inputOffset, destination, outputOffset)
            sourceFrameIndex += framing.bytesPerSample
            producedFrames++
        }

        while (sourceFrameIndex < frameCount) {
            val inputOffset = sourceOffset + sourceFrameIndex * channelCount
            source.copyInto(
                pending,
                pendingFrameCount * channelCount,
                inputOffset,
                inputOffset + channelCount,
            )
            pendingFrameCount++
            sourceFrameIndex++
        }
        check(producedFrames == outputFrames)
        return producedFrames
    }

    /** Pads one partial Native frame with DSD idle. Returns 1 when a frame was emitted. */
    fun drain(destination: ByteArray, destinationOffset: Int = 0): Int {
        if (pendingFrameCount == 0) return 0
        require(destinationOffset >= 0 && destinationOffset + channelCount * framing.bytesPerSample <= destination.size)
        while (pendingFrameCount < framing.bytesPerSample) {
            val offset = pendingFrameCount * channelCount
            repeat(channelCount) { channel -> pending[offset + channel] = DSD_IDLE_BYTE }
            pendingFrameCount++
        }
        writeNativeFrame(pending, 0, destination, destinationOffset)
        pendingFrameCount = 0
        return 1
    }

    fun pendingByteFrames(): Int = pendingFrameCount

    private fun writeNativeFrame(
        source: ByteArray,
        sourceOffset: Int,
        destination: ByteArray,
        start: Int,
    ): Int {
        var out = start
        for (channel in 0 until channelCount) {
            if (framing.littleEndian) {
                for (index in framing.bytesPerSample - 1 downTo 0) {
                    destination[out++] = source[sourceOffset + index * channelCount + channel]
                }
            } else {
                for (index in 0 until framing.bytesPerSample) {
                    destination[out++] = source[sourceOffset + index * channelCount + channel]
                }
            }
        }
        return out
    }

    companion object {
        fun runtimeFrameRate(dsdBitRateHz: Int, framing: NativeDsdFraming): Int {
            val divisor = 8 * framing.bytesPerSample
            require(dsdBitRateHz > 0 && dsdBitRateHz % divisor == 0) {
                "DSD bit rate must be exactly divisible by $divisor for $framing"
            }
            return dsdBitRateHz / divisor
        }
    }
}
