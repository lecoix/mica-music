package com.mica.music.media

/** Target PCM delivery format for SharedPcm output (Gate 3 probe model). */
internal sealed class PcmDeliveryFormat {

    abstract fun label(): String

    data class IntPcm(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
    ) : PcmDeliveryFormat() {
        override fun label(): String = "${sampleRateHz}Hz/${channelCount}ch/${bitsPerSample}bit"

        fun toAlacPcmFormat(): AlacPcmFormat =
            AlacPcmFormat(sampleRateHz, channelCount, bitsPerSample)
    }

    data class FloatPcm(
        val sampleRateHz: Int,
        val channelCount: Int,
    ) : PcmDeliveryFormat() {
        override fun label(): String = "float@${sampleRateHz}Hz/${channelCount}ch"
    }
}
