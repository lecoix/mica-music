package com.mica.music.media

import com.mica.music.audio.eq.MicaEqualizerManager
import androidx.media3.common.util.UnstableApi
import com.mica.music.audio.eq.SoftwareEqualizer

/**
 * Production [MicaFloatDspAudioSink.FloatPcmDspTap] bound to the shared EQ and spectrum singletons.
 * Used by the float PcmSink whose hi-res path Media3 excludes from the custom processor chain.
 */
@UnstableApi
internal class MicaEqualizerSpectrumTap(
    private val equalizer: SoftwareEqualizer = MicaEqualizerManager.equalizer,
    private val spectrumSession: SpectrumSinkSession? = null,
) : MicaFloatDspAudioSink.FloatPcmDspTap {

    override fun configure(sampleRate: Int, channelCount: Int) {
        equalizer.configure(sampleRate, channelCount)
        equalizer.resetFilters()
    }

    override fun isActive(): Boolean =
        equalizer.isProcessingRequired() || MicaSpectrumAnalyzer.isCaptureActive()

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
        if (spectrumSession != null) {
            spectrumSession.capture(bytes, offset, length, androidEncoding, sampleRate, channelCount, timestampEachBuffer = true)
        } else if (MicaSpectrumAnalyzer.isCaptureActive()) {
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
