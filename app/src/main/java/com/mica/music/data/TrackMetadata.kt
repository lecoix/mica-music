package com.mica.music.data

/**
 * 由 [com.mica.music.data.scanner.AudioMetadataProbe] 从文件头读取的真实参数。
 */
data class TrackMetadata(
    val containerName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int?,
    val bitrateKbps: Int,
    val channelCount: Int,
    /** ExoPlayer 播放用 MIME（content:// URI 无扩展名时必须提供） */
    val playbackMimeType: String,
) {
    val sampleRateLabel: String
        get() = formatSampleRateLabel(sampleRateHz, bitsPerSample)

    val bitrateLabel: String
        get() = formatBitrateLabel(bitrateKbps)

    val formatLabel: String get() = containerName

    val isHiRes: Boolean
        get() = sampleRateHz > 48_000 || (bitsPerSample ?: 16) > 16

    companion object {
        fun formatSampleRateLabel(sampleRateHz: Int, bits: Int?): String {
            if (sampleRateHz <= 0) return "—"
            val khz = sampleRateHz / 1000.0
            val khzText = if (kotlin.math.abs(khz - khz.toInt()) < 0.05) {
                "${khz.toInt()}kHz"
            } else {
                "%.1fkHz".format(khz)
            }
            return if (bits != null && bits > 0) "${bits}bit/$khzText" else khzText
        }

        fun formatBitrateLabel(kbps: Int): String {
            if (kbps <= 0) return "—"
            return "$kbps kbps"
        }

        fun fallback(
            mimeType: String,
            bitrateBpsFromStore: Int,
            displayName: String? = null,
            mediaUri: String = "",
        ): TrackMetadata {
            val container = containerFromMime(mimeType)
            val kbps = if (bitrateBpsFromStore > 0) bitrateBpsFromStore / 1000 else 320
            return TrackMetadata(
                containerName = container,
                sampleRateHz = 44_100,
                bitsPerSample = 16,
                bitrateKbps = kbps,
                channelCount = 2,
                playbackMimeType = PlaybackMimeResolver.resolve(
                    storeMime = mimeType,
                    probeMime = null,
                    displayName = displayName,
                    mediaUri = mediaUri,
                ),
            )
        }

        fun containerFromMime(mimeType: String): String {
            val m = mimeType.lowercase()
            return when {
                "flac" in m -> "FLAC"
                "mpeg" in m || "mp3" in m -> "MP3"
                "mp4" in m || "m4a" in m || "aac" in m -> "AAC"
                "wav" in m || "x-wav" in m -> "WAV"
                "ogg" in m || "opus" in m -> "OGG"
                "dsd" in m || "dsf" in m -> "DSD"
                "alac" in m -> "ALAC"
                else -> mimeType.substringAfterLast('/').uppercase().ifBlank { "AUDIO" }
            }
        }
    }
}
