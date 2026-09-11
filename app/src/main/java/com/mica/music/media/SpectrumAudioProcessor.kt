package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
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
class SpectrumAudioProcessor internal constructor(private val spectrumSession: SpectrumSinkSession? = null) : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var pendingOutput = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingFormat = inputAudioFormat
        if (inputFormat == AudioProcessor.AudioFormat.NOT_SET) inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        return outputFormat
    }

    override fun isActive(): Boolean = MicaSpectrumAnalyzer.isEnabledForProcessing()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.hasRemaining()) {
            pendingInput = inputBuffer
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        if (!pendingInput.hasRemaining()) {
            pendingOutput = AudioProcessor.EMPTY_BUFFER
            return pendingOutput
        }
        val length = pendingInput.remaining()
        if (MicaSpectrumAnalyzer.isCaptureActive() || spectrumSession != null) {
            tapSpectrum(pendingInput.duplicate(), length)
        }
        pendingOutput = pendingInput
        pendingInput = AudioProcessor.EMPTY_BUFFER
        return pendingOutput
    }

    override fun isEnded(): Boolean = inputEnded && !pendingOutput.hasRemaining()

    override fun flush() {
        spectrumSession?.processorFlushed()
        inputFormat = pendingFormat
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
    }

    private fun tapSpectrum(buffer: ByteBuffer, length: Int) {
        val encoding = media3EncodingToAndroid(inputFormat.encoding)
        if (encoding == android.media.AudioFormat.ENCODING_INVALID) return
        val bytes = ByteArray(length)
        buffer.get(bytes)
        if (spectrumSession != null) {
            spectrumSession.capture(bytes, 0, length, encoding, inputFormat.sampleRate, inputFormat.channelCount)
        } else MicaSpectrumAnalyzer.processPcmBuffer(
            buffer = bytes,
            offset = 0,
            length = length,
            encoding = encoding,
            sampleRateHz = inputFormat.sampleRate,
            channelCount = inputFormat.channelCount,
        )
    }

    private fun media3EncodingToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
        C.ENCODING_PCM_32BIT -> android.media.AudioFormat.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        else -> android.media.AudioFormat.ENCODING_INVALID
    }

}
