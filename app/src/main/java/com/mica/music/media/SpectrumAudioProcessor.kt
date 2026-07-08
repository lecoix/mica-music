package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer

/**
 * Taps Exo PCM into [MicaSpectrumAnalyzer] without altering the audio stream.
 *
 * Passes [pendingInput] through unchanged (zero-copy). Analysis reads a duplicate
 * buffer so we never reuse a single scratch [ByteBuffer] for output — reusing
 * output buffers stalls playback on some devices (~1s loops).
 *
 * Inactive while spectrum is off. Toggling spectrum must pair with
 * [MicaMediaService.flushAudioPipeline] so [isActive] is re-evaluated safely.
 */
@UnstableApi
class SpectrumAudioProcessor : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var pendingOutput = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var probeWindowStartMs = 0L
    private var probeQueueInputCalls = 0
    private var probeGetOutputCalls = 0
    private var probeTapCalls = 0
    private var probeSkippedTapCalls = 0
    private var probeInvalidEncodingCalls = 0
    private var probeInputBytes = 0L
    private var probeTapBytes = 0L
    private var probeMaxInputBytes = 0
    private var probeLastQueueInputMs = 0L
    private var probeLastTapMs = 0L
    private var probeMaxInputGapMs = 0L
    private var probeMaxTapGapMs = 0L
    private var probeTapNanos = 0L
    private var probeMaxTapNanos = 0L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        DiagnosticLog.event(
            "SpectrumTap",
            "configure sr=${inputFormat.sampleRate} ch=${inputFormat.channelCount} " +
                "enc=${inputFormat.encoding}",
        )
        return outputFormat
    }

    override fun isActive(): Boolean = MicaSpectrumAnalyzer.isEnabledForProcessing()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.hasRemaining()) {
            recordQueueInput(inputBuffer.remaining(), System.currentTimeMillis())
            pendingInput = inputBuffer
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val nowMs = System.currentTimeMillis()
        probeGetOutputCalls++
        if (!pendingInput.hasRemaining()) {
            pendingOutput = AudioProcessor.EMPTY_BUFFER
            reportProbeIfNeeded(nowMs)
            return pendingOutput
        }
        val length = pendingInput.remaining()
        if (MicaSpectrumAnalyzer.isAnalysisActive()) {
            val tapStart = System.nanoTime()
            val tapped = tapSpectrum(pendingInput.duplicate(), length)
            recordTap(length, System.currentTimeMillis(), System.nanoTime() - tapStart, tapped)
        } else {
            probeSkippedTapCalls++
        }
        pendingOutput = pendingInput
        pendingInput = AudioProcessor.EMPTY_BUFFER
        reportProbeIfNeeded(nowMs)
        return pendingOutput
    }

    override fun isEnded(): Boolean = inputEnded && !pendingOutput.hasRemaining()

    override fun flush() {
        DiagnosticLog.event(
            "SpectrumTap",
            "flush pendingInput=${pendingInput.remaining()} pendingOutput=${pendingOutput.remaining()}",
        )
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        DiagnosticLog.event("SpectrumTap", "reset")
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
    }

    private fun tapSpectrum(buffer: ByteBuffer, length: Int): Boolean {
        val encoding = media3EncodingToAndroid(inputFormat.encoding)
        if (encoding == android.media.AudioFormat.ENCODING_INVALID) return false
        val bytes = ByteArray(length)
        buffer.get(bytes)
        MicaSpectrumAnalyzer.processPcmBuffer(
            buffer = bytes,
            offset = 0,
            length = length,
            encoding = encoding,
            sampleRateHz = inputFormat.sampleRate,
            channelCount = inputFormat.channelCount,
        )
        return true
    }

    private fun recordQueueInput(length: Int, nowMs: Long) {
        if (probeWindowStartMs == 0L) probeWindowStartMs = nowMs
        probeQueueInputCalls++
        probeInputBytes += length
        probeMaxInputBytes = maxOf(probeMaxInputBytes, length)
        if (probeLastQueueInputMs != 0L) {
            probeMaxInputGapMs = maxOf(probeMaxInputGapMs, nowMs - probeLastQueueInputMs)
        }
        probeLastQueueInputMs = nowMs
    }

    private fun recordTap(length: Int, nowMs: Long, tapNanos: Long, tapped: Boolean) {
        if (!tapped) {
            probeInvalidEncodingCalls++
            return
        }
        probeTapCalls++
        probeTapBytes += length
        probeTapNanos += tapNanos
        probeMaxTapNanos = maxOf(probeMaxTapNanos, tapNanos)
        if (probeLastTapMs != 0L) {
            probeMaxTapGapMs = maxOf(probeMaxTapGapMs, nowMs - probeLastTapMs)
        }
        probeLastTapMs = nowMs
    }

    private fun reportProbeIfNeeded(nowMs: Long) {
        val start = probeWindowStartMs
        if (start == 0L || nowMs - start < 1_000L) return
        val seconds = (nowMs - start) / 1_000f
        val avgTapMs = if (probeTapCalls > 0) {
            probeTapNanos / probeTapCalls / 1_000_000f
        } else {
            0f
        }
        DiagnosticLog.event(
            "SpectrumTap",
            "inputCalls=${probeQueueInputCalls} getOutputCalls=${probeGetOutputCalls} " +
                "tapCalls=${probeTapCalls} skippedTap=${probeSkippedTapCalls} " +
                "invalidEncoding=${probeInvalidEncodingCalls} inputKBps=${(probeInputBytes / 1024f / seconds).format1()} " +
                "tapKBps=${(probeTapBytes / 1024f / seconds).format1()} " +
                "maxInputGapMs=$probeMaxInputGapMs maxTapGapMs=$probeMaxTapGapMs " +
                "maxInputBytes=$probeMaxInputBytes avgTapMs=${avgTapMs.format2()} " +
                "maxTapMs=${(probeMaxTapNanos / 1_000_000f).format2()} " +
                "sr=${inputFormat.sampleRate} ch=${inputFormat.channelCount} enc=${inputFormat.encoding} " +
                "analysisActive=${MicaSpectrumAnalyzer.isAnalysisActive()}",
        )
        resetProbeWindow(nowMs)
    }

    private fun resetProbeWindow(nowMs: Long) {
        probeWindowStartMs = nowMs
        probeQueueInputCalls = 0
        probeGetOutputCalls = 0
        probeTapCalls = 0
        probeSkippedTapCalls = 0
        probeInvalidEncodingCalls = 0
        probeInputBytes = 0L
        probeTapBytes = 0L
        probeMaxInputBytes = 0
        probeMaxInputGapMs = 0L
        probeMaxTapGapMs = 0L
        probeTapNanos = 0L
        probeMaxTapNanos = 0L
    }

    private fun media3EncodingToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        C.ENCODING_PCM_32BIT -> android.media.AudioFormat.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        else -> android.media.AudioFormat.ENCODING_INVALID
    }

    private fun Float.format1(): String = String.format("%.1f", this)

    private fun Float.format2(): String = String.format("%.2f", this)
}
