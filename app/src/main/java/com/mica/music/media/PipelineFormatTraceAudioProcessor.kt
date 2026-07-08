package com.mica.music.media

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * P0 diagnostic [AudioProcessor]: logs PCM format at chain positions without altering audio.
 *
 * Place at sink entry and after major processors to locate unwanted bit-depth conversion.
 */
@UnstableApi
class PipelineFormatTraceAudioProcessor(
    val traceName: String,
) : AudioProcessor {

    private val processorName = traceName

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var maxObservedInputBytes = 0
    private var totalInputBytes = 0L
    private var queueInputCalls = 0
    private var windowStartMs = 0L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        PcmFormatDiagnostics.logProcessorConfigure(
            processorName = processorName,
            inputFormat = inputFormat,
            outputFormat = outputFormat,
            active = isActive,
        )
        return outputFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val nowMs = System.currentTimeMillis()
        if (windowStartMs == 0L) windowStartMs = nowMs
        queueInputCalls++
        val length = inputBuffer.remaining()
        totalInputBytes += length
        maxObservedInputBytes = maxOf(maxObservedInputBytes, length)
        pendingInput = inputBuffer
        reportBufferStatsIfNeeded(nowMs)
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = pendingInput
        pendingInput = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && !pendingInput.hasRemaining()

    override fun flush() {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
        maxObservedInputBytes = 0
        totalInputBytes = 0L
        queueInputCalls = 0
        windowStartMs = 0L
    }

    private fun reportBufferStatsIfNeeded(nowMs: Long) {
        val start = windowStartMs
        if (start == 0L || nowMs - start < 5_000L) return
        val seconds = (nowMs - start) / 1_000f
        com.mica.music.util.DiagnosticLog.event(
            PcmFormatDiagnostics.LOG_PROCESSOR_FORMAT,
            "name=$processorName stats queueCalls=$queueInputCalls " +
                "maxInputBytes=$maxObservedInputBytes " +
                "throughputKBps=${(totalInputBytes / 1024f / seconds).format1()} " +
                "format=${PcmFormatDiagnostics.formatLabel(inputFormat)}",
        )
        windowStartMs = nowMs
        queueInputCalls = 0
        totalInputBytes = 0L
        maxObservedInputBytes = 0
    }

    private fun Float.format1(): String = String.format("%.1f", this)
}
