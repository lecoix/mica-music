package com.mica.music.media

import androidx.media3.common.util.UnstableApi
import com.mica.music.media.eq.SoftwareEqualizer

/**
 * Production [MicaFloatDspAudioSink.FloatPcmDspTap] bound to the shared EQ and spectrum singletons.
 * Used by the float PcmSink whose hi-res path Media3 excludes from the custom processor chain.
 */
@UnstableApi
internal class MicaEqualizerSpectrumTap(
    private val equalizer: SoftwareEqualizer = MicaEqualizerManager.equalizer,
) : MicaFloatDspAudioSink.FloatPcmDspTap {

    override fun configure(sampleRate: Int, channelCount: Int) {
        equalizer.configure(sampleRate, channelCount)
        equalizer.resetFilters()
    }

    override fun isActive(): Boolean =
        equalizer.isEnabled() || MicaSpectrumAnalyzer.isAnalysisActive()

    override fun process(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        androidEncoding: Int,
        sampleRate: Int,
        channelCount: Int,
    ) {
        // EQ mutates in place (no-op when disabled); spectrum only reads.
        equalizer.processInterleaved(bytes, offset, length, androidEncoding)
        if (MicaSpectrumAnalyzer.isAnalysisActive()) {
            MicaSpectrumAnalyzer.processPcmBuffer(
                buffer = bytes,
                offset = offset,
                length = length,
                encoding = androidEncoding,
                sampleRateHz = sampleRate,
                channelCount = channelCount,
            )
        }
    }
}
