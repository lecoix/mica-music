package com.mica.music.media.dsf

/**
 * Byte offsets inside the DSF [data] chunk for 1-bit interleaved DSD payloads.
 *
 * Verified against `.test-music/09.Count Down.dsf`:
 * `sampleCount * channelCount / 8 + dataPayloadOffset == metadataPointer`.
 */
internal object DsfSeekMath {

    fun sampleIndexForPositionMs(format: DsfFormat, positionMs: Long): Long {
        if (positionMs <= 0L || format.sampleRateHz <= 0) return 0L
        val requested = (positionMs * format.sampleRateHz) / 1_000L
            .coerceAtMost(format.sampleCount)
        return alignSampleIndexToBlock(format, requested)
    }

    fun fileOffsetForSampleIndex(format: DsfFormat, sampleIndex: Long): Long {
        val alignedIndex = alignSampleIndexToBlock(format, sampleIndex)
        val blockIndex = alignedIndex / format.samplesPerBlockPerChannel
        return format.dataPayloadOffset + blockIndex * format.blockAlign
    }

    fun payloadByteOffset(format: DsfFormat, sampleIndex: Long): Long {
        require(format.bitsPerSample == 1) {
            "Only 1-bit DSF payloads are supported (found ${format.bitsPerSample})"
        }
        require(format.channelCount > 0) { "Invalid channel count" }
        return (safeIndex(format, sampleIndex) * format.channelCount) / 8L
    }

    fun totalPayloadBytes(format: DsfFormat): Long {
        val fromSampleCount = payloadByteOffset(format, format.sampleCount)
        val fromChunk = format.dataChunkSize - DsfFormat.DATA_HEADER_SIZE
        return minOf(fromSampleCount, fromChunk)
    }

    fun payloadBytesRemaining(format: DsfFormat, sampleIndex: Long): Long {
        val totalPayload = totalPayloadBytes(format)
        val consumed = payloadByteOffset(format, sampleIndex)
        return (totalPayload - consumed).coerceAtLeast(0L)
    }

    private fun safeIndex(format: DsfFormat, sampleIndex: Long): Long =
        sampleIndex.coerceIn(0L, format.sampleCount)

    private fun alignSampleIndexToBlock(format: DsfFormat, sampleIndex: Long): Long {
        val safeIndex = safeIndex(format, sampleIndex)
        val samplesPerBlock = format.samplesPerBlockPerChannel
        if (samplesPerBlock <= 0L) return 0L
        return (safeIndex / samplesPerBlock) * samplesPerBlock
    }
}
