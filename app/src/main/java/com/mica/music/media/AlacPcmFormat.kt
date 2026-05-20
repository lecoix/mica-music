package com.mica.music.media

import android.media.AudioFormat
import android.os.Build
import com.mica.music.data.Song

/** ALAC 解码 / AudioTrack 输出用的 PCM 参数（尽量与源文件一致）。 */
data class AlacPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
) {
    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)

    val ffmpegPcmCodec: String
        get() = when {
            bitsPerSample <= 16 -> "pcm_s16le"
            bitsPerSample <= 24 -> "pcm_s24le"
            else -> "pcm_s32le"
        }

    val audioTrackEncoding: Int
        get() = when {
            bitsPerSample <= 16 -> AudioFormat.ENCODING_PCM_16BIT
            bitsPerSample <= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }

    companion object {
        fun fromSong(song: Song): AlacPcmFormat {
            val meta = song.metadata
            return AlacPcmFormat(
                sampleRateHz = meta.sampleRateHz.takeIf { it > 0 } ?: 44_100,
                channelCount = meta.channelCount.coerceIn(1, 2),
                bitsPerSample = meta.bitsPerSample?.takeIf { it > 0 } ?: 16,
            )
        }
    }
}
