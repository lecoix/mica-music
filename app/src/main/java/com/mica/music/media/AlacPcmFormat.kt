package com.mica.music.media

import android.media.AudioFormat
import android.os.Build
import com.mica.music.data.Song

/** FFmpeg 裸 PCM → AudioTrack 的格式参数。 */
data class AlacPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
) {
    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)

    /** PCM 文件内与 [positionMs] 对齐的字节偏移（按帧对齐）。 */
    fun byteOffsetForMs(positionMs: Int): Long {
        if (positionMs <= 0) return 0L
        val frameIndex = (positionMs.toLong() * sampleRateHz) / 1_000L
        return frameIndex * bytesPerFrame.coerceAtLeast(1)
    }

    fun framesForMs(positionMs: Int): Long =
        if (positionMs <= 0) 0L else (positionMs.toLong() * sampleRateHz) / 1_000L

    val audioTrackEncoding: Int
        get() = when {
            bitsPerSample >= 32 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                AudioFormat.ENCODING_PCM_32BIT
            bitsPerSample <= 16 -> AudioFormat.ENCODING_PCM_16BIT
            bitsPerSample <= 24 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            else -> AudioFormat.ENCODING_PCM_16BIT
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
