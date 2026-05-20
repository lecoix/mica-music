package com.mica.music.media.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media3 音频处理器：在 ExoPlayer 输出链路中应用 [SoftwareEqualizer]。 */
@UnstableApi
class SoftwareEqualizerAudioProcessor(
    private val equalizer: SoftwareEqualizer,
) : AudioProcessor {

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var pendingOutput = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var scratch = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        outputFormat = inputAudioFormat
        equalizer.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        equalizer.resetFilters()
        return outputFormat
    }

    override fun isActive(): Boolean = equalizer.isEnabled()

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
        val encoding = media3EncodingToAndroid(inputFormat.encoding)
        val size = pendingInput.remaining()
        if (scratch.capacity() < size) {
            scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            scratch.clear()
        }
        equalizer.processMedia3Buffer(pendingInput, encoding, scratch)
        scratch.flip()
        pendingOutput = scratch
        pendingInput = AudioProcessor.EMPTY_BUFFER
        return pendingOutput
    }

    override fun isEnded(): Boolean = inputEnded && !pendingOutput.hasRemaining()

    override fun flush() {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        pendingOutput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        equalizer.resetFilters()
    }

    override fun reset() {
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        outputFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
    }

    private fun media3EncodingToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> android.media.AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_FLOAT -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        else -> android.media.AudioFormat.ENCODING_PCM_16BIT
    }
}
