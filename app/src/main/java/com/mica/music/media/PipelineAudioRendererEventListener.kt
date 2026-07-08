package com.mica.music.media

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink

/** P0 wrapper: logs decoder input and AudioTrack delivery, then delegates. */
@UnstableApi
internal class PipelineAudioRendererEventListener(
    private val delegate: AudioRendererEventListener?,
) : AudioRendererEventListener {
    private var decoderName: String? = null

    override fun onAudioEnabled(decoderCounters: DecoderCounters) {
        delegate?.onAudioEnabled(decoderCounters)
    }

    override fun onAudioDecoderInitialized(
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        this.decoderName = decoderName
        delegate?.onAudioDecoderInitialized(
            decoderName,
            initializedTimestampMs,
            initializationDurationMs,
        )
    }

    override fun onAudioInputFormatChanged(
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        PcmFormatDiagnostics.logDecoderInputFormat(format)
        RendererSupportProbeDiagnostics.logFormat(format, decoderName)
        delegate?.onAudioInputFormatChanged(format, decoderReuseEvaluation)
    }

    override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
        PcmFormatDiagnostics.logAudioTrackConfig(audioTrackConfig)
        delegate?.onAudioTrackInitialized(audioTrackConfig)
    }

    override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
        delegate?.onAudioTrackReleased(audioTrackConfig)
    }

    override fun onAudioDisabled(decoderCounters: DecoderCounters) {
        delegate?.onAudioDisabled(decoderCounters)
    }

    override fun onAudioDecoderReleased(decoderName: String) {
        delegate?.onAudioDecoderReleased(decoderName)
    }

    override fun onAudioSinkError(audioSinkError: Exception) {
        delegate?.onAudioSinkError(audioSinkError)
    }

    override fun onAudioCodecError(audioCodecError: Exception) {
        delegate?.onAudioCodecError(audioCodecError)
    }

    override fun onAudioUnderrun(
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        delegate?.onAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        delegate?.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    }

    override fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long) {
        delegate?.onAudioPositionAdvancing(playoutStartSystemTimeMs)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        delegate?.onAudioSessionIdChanged(audioSessionId)
    }
}
