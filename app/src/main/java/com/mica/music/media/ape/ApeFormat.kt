package com.mica.music.media.ape

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Information Media3 passes from the Monkey's Audio container to the FFmpeg decoder. */
data class ApeFormat(
    val fileVersion: Int,
    val compressionType: Int,
    val formatFlags: Int,
    val bitsPerSample: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val blocksPerFrame: Long,
    val finalFrameBlocks: Long,
    val frames: List<ApeFrame>,
    val durationUs: Long,
) {
    val decoderInitializationData: ByteArray =
        ByteBuffer.allocate(DECODER_EXTRADATA_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(fileVersion.toShort())
            .putShort(compressionType.toShort())
            .putShort(formatFlags.toShort())
            .array()

    val pcmEncoding: Int
        get() = when (bitsPerSample) {
            8 -> C.ENCODING_PCM_8BIT
            16 -> C.ENCODING_PCM_16BIT
            24 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_INVALID
        }

    val maxPacketBytes: Int
        get() = frames.maxOfOrNull { it.sizeBytes }?.plus(PACKET_PREFIX_BYTES) ?: PACKET_PREFIX_BYTES

    fun frameIndexForTimeUs(timeUs: Long): Int {
        if (frames.isEmpty()) return 0
        val targetBlocks = timeUs.coerceAtLeast(0L) * sampleRateHz / 1_000_000L
        return (targetBlocks / blocksPerFrame)
            .coerceIn(0L, frames.lastIndex.toLong())
            .toInt()
    }

    companion object {
        const val MIME = "audio/ape"
        const val CONTAINER_MIME = "audio/x-ape"
        // Pre-3.81 files have a separate bit-alignment table and are outside the playback MVP.
        const val MIN_FILE_VERSION = 3_810
        const val MAX_FILE_VERSION = 3_990
        const val MODERN_FILE_VERSION = 3_980
        const val DECODER_EXTRADATA_BYTES = 6
        const val PACKET_PREFIX_BYTES = 8
    }
}

data class ApeFrame(
    val position: Long,
    val sizeBytes: Int,
    val blocks: Long,
    val skip: Int,
    val timeUs: Long,
)
