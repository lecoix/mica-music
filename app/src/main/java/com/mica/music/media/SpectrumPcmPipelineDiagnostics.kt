package com.mica.music.media

import com.mica.music.util.DiagnosticDetailDomain
import com.mica.music.util.DiagnosticLog

/**
 * Immediate, threshold-based PCM pipeline events for spectrum stall diagnosis.
 * Runs only while detailed audio diagnostics are enabled; emits threshold-based gap/starvation/burst events.
 */
internal object SpectrumPcmPipelineDiagnostics {
    private const val UpstreamGapThresholdMs = 200L
    private const val AnalyzerStarvationThresholdMs = 100L
    private const val QueueBurstAbsoluteSamples = 30_000
    private const val QueueBurstDeltaSamples = 20_000
    private const val InnerRejectLogThreshold = 3

    fun onFloatDspUpstreamGap(
        gapMs: Long,
        bufferBytes: Int,
        presentationTimeUs: Long,
        processed: Boolean,
        passthroughReason: String?,
        inFlightRetry: Boolean,
        consecutiveInnerRejects: Int,
    ) {
        if (!DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.AUDIO_PIPELINE)) return
        if (gapMs < UpstreamGapThresholdMs) return
        DiagnosticLog.event(
            "FloatDspSink",
            "pcm-gap gapMs=$gapMs bufferBytes=$bufferBytes ptsUs=$presentationTimeUs " +
                "mode=${if (processed) "processed" else "passthrough"} " +
                "passthroughReason=${passthroughReason ?: "none"} " +
                "inFlightRetry=$inFlightRetry innerRejectStreak=$consecutiveInnerRejects " +
                "analysisActive=${MicaSpectrumAnalyzer.isAnalysisActive()} " +
                "playbackAdvancing=${MicaSpectrumAnalyzer.isPlaybackAdvancing()} " +
                "queuedSamples=${MicaSpectrumAnalyzer.queuedPcmSampleCount()}",
        )
    }

    fun onFloatDspPassthroughWhileAnalysisExpected(reason: String) {
        if (!DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.AUDIO_PIPELINE)) return
        if (!MicaSpectrumAnalyzer.isAnalysisActive()) return
        DiagnosticLog.event(
            "FloatDspSink",
            "pcm-passthrough-unexpected reason=$reason " +
                "analysisActive=true playbackAdvancing=${MicaSpectrumAnalyzer.isPlaybackAdvancing()} " +
                "queuedSamples=${MicaSpectrumAnalyzer.queuedPcmSampleCount()}",
        )
    }

    fun onFloatDspInnerReject(streak: Int, mode: String, bufferBytes: Int, presentationTimeUs: Long) {
        if (!DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.AUDIO_PIPELINE)) return
        if (streak < InnerRejectLogThreshold) return
        DiagnosticLog.event(
            "FloatDspSink",
            "inner-reject streak=$streak mode=$mode bufferBytes=$bufferBytes ptsUs=$presentationTimeUs " +
                "analysisActive=${MicaSpectrumAnalyzer.isAnalysisActive()} " +
                "queuedSamples=${MicaSpectrumAnalyzer.queuedPcmSampleCount()}",
        )
    }


    fun onAnalyzerStarvation(durationMs: Long, sampleRateHz: Int) {
        if (!DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.AUDIO_PIPELINE)) return
        if (durationMs < AnalyzerStarvationThresholdMs) return
        DiagnosticLog.event(
            "Spectrum",
            "pcm-starvation durationMs=$durationMs sr=$sampleRateHz " +
                "analysisActive=${MicaSpectrumAnalyzer.isAnalysisActive()} " +
                "playbackAdvancing=${MicaSpectrumAnalyzer.isPlaybackAdvancing()} " +
                "enabled=${MicaSpectrumAnalyzer.isEnabledForProcessing()}",
        )
    }

    fun onAnalyzerQueueBurst(
        previousQueuedSamples: Int,
        queuedSamples: Int,
        offeredSamples: Int,
        sampleRateHz: Int,
    ) {
        if (!DiagnosticLog.isDetailedEnabled(DiagnosticDetailDomain.AUDIO_PIPELINE)) return
        val delta = queuedSamples - previousQueuedSamples
        if (queuedSamples < QueueBurstAbsoluteSamples && delta < QueueBurstDeltaSamples) return
        DiagnosticLog.event(
            "Spectrum",
            "pcm-queue-burst prevQueued=$previousQueuedSamples queued=$queuedSamples " +
                "delta=$delta offered=$offeredSamples sr=$sampleRateHz " +
                "analysisActive=${MicaSpectrumAnalyzer.isAnalysisActive()} " +
                "playbackAdvancing=${MicaSpectrumAnalyzer.isPlaybackAdvancing()}",
        )
    }
}
