package com.mica.music.media.dsf

/**
 * Parsed header of a Sony DSF (DSD Stream File).
 *
 * See [DSF File Format Specification](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf).
 */
data class DsfFormat(
    val totalFileSize: Long,
    val metadataPointer: Long,
    val formatVersion: Int,
    val formatId: Int,
    val channelType: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val sampleCount: Long,
    val blockSizePerChannel: Int,
    val dataChunkSize: Long,
    val dataPayloadOffset: Long,
) {
    val durationUs: Long
        get() = if (sampleRateHz <= 0) 0L else sampleCount * 1_000_000L / sampleRateHz

    val durationMs: Long
        get() = durationUs / 1_000L

    /** PCM frame rate emitted by FFmpeg's packed-byte DSD decoder. */
    val decoderSampleRateHz: Int
        get() = sampleRateHz / 8

    val samplesPerBlockPerChannel: Long
        get() = blockSizePerChannel * 8L

    val blockAlign: Int
        get() = blockSizePerChannel * channelCount

    val dsdLabel: String?
        get() {
            val multiple = sampleRateHz / 44_100.0
            return when {
                kotlin.math.abs(multiple - 64.0) < 1.0 -> "DSD64"
                kotlin.math.abs(multiple - 128.0) < 1.0 -> "DSD128"
                kotlin.math.abs(multiple - 256.0) < 1.0 -> "DSD256"
                kotlin.math.abs(multiple - 512.0) < 1.0 -> "DSD512"
                else -> null
            }
        }

    companion object {
        const val MIME_DSF = "audio/dsd"
        const val MIME_CONTAINER_DSF = "audio/x-dsf"
        const val CHUNK_DSD = "DSD "
        const val CHUNK_FMT = "fmt "
        const val CHUNK_DATA = "data"
        const val FORMAT_ID_DSD_RAW = 0
        const val DSD_CHUNK_SIZE = 28L
        const val FMT_CHUNK_SIZE = 52L
        const val DATA_HEADER_SIZE = 12L
    }
}
