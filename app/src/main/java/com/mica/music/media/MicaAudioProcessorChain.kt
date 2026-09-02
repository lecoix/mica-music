package com.mica.music.media

import com.mica.music.diagnostics.AudioPipelineDebugDiagnostics
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer

/**
 * Exo PCM pipeline for Mica shared PCM playback.
 *
 * Omits Media3's default tail processors:
 * - [androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor] rejects packed 24-bit PCM.
 *
 * Owns playback speed/pitch through Sonic inside the processor chain, after spectrum
 * tap, instead of AudioTrack playback params. Unsupported PCM formats pass through
 * unchanged so DSD decimation can keep playing with speed/pitch disabled.
 */
@UnstableApi
class MicaAudioProcessorChain(
    vararg processors: AudioProcessor,
    private val includePlaybackTuning: Boolean = true,
    includeFormatTrace: Boolean = AudioPipelineDebugDiagnostics.formatTraceEnabled,
) : DefaultAudioSink.AudioProcessorChain {

    private val playbackTuningProcessor = MicaPlaybackTuningAudioProcessor()
    private val audioProcessors: Array<AudioProcessor> =
        buildList {
            addAll(processors)
            if (includePlaybackTuning) {
                add(playbackTuningProcessor)
            }
            if (includeFormatTrace) {
                add(PipelineFormatTraceAudioProcessor("chain-exit"))
            }
        }.toTypedArray()

    override fun getAudioProcessors(): Array<AudioProcessor> = audioProcessors

    /** Ordered processor labels for [PcmFormatDiagnostics.logSinkBuild]. */
    fun processorNamesForDiagnostics(): List<String> =
        audioProcessors.map { processor ->
            when (processor) {
                is PipelineFormatTraceAudioProcessor -> processor.traceName
                is DsdDecimationAudioProcessor -> "DsdDecimation"
                is SpectrumAudioProcessor -> "Spectrum"
                is com.mica.music.audio.eq.SoftwareEqualizerAudioProcessor -> "EQ"
                is MicaPlaybackTuningAudioProcessor -> "PlaybackTuning"
                else -> processor.javaClass.simpleName
            }
        }

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        if (includePlaybackTuning) {
            playbackTuningProcessor.setSpeed(playbackParameters.speed)
            playbackTuningProcessor.setPitch(playbackParameters.pitch)
        }
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = skipSilenceEnabled

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (includePlaybackTuning) {
            playbackTuningProcessor.getMediaDuration(playoutDuration)
        } else {
            playoutDuration
        }

    override fun getSkippedOutputFrameCount(): Long = 0L
}

@UnstableApi
internal class MicaPlaybackTuningAudioProcessor(
    private val sonicAudioProcessor: SonicAudioProcessor = SonicAudioProcessor(),
) : AudioProcessor {

    private var passthrough = true
    private var pendingInput = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var speed = 1f
    private var pitch = 1f

    fun setSpeed(value: Float) {
        speed = value
        sonicAudioProcessor.setSpeed(value)
    }

    fun setPitch(value: Float) {
        pitch = value
        sonicAudioProcessor.setPitch(value)
    }

    fun getMediaDuration(playoutDuration: Long): Long =
        if (passthrough) playoutDuration else sonicAudioProcessor.getMediaDuration(playoutDuration)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        passthrough = !supportsSonic(inputAudioFormat)
        if (passthrough) {
            flushSonic()
            logSonicDisabled(inputAudioFormat, "unsupported")
            PcmFormatDiagnostics.logProcessorConfigure(
                processorName = "PlaybackTuning",
                inputFormat = inputAudioFormat,
                outputFormat = inputAudioFormat,
                active = false,
            )
            return inputAudioFormat
        }
        return try {
            val output = sonicAudioProcessor.configure(inputAudioFormat)
            PcmFormatDiagnostics.logProcessorConfigure(
                processorName = "PlaybackTuning",
                inputFormat = inputAudioFormat,
                outputFormat = output,
                active = true,
            )
            output
        } catch (exception: AudioProcessor.UnhandledAudioFormatException) {
            passthrough = true
            flushSonic()
            logSonicDisabled(inputAudioFormat, "configure-failed")
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        !passthrough && sonicAudioProcessor.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (passthrough) {
            pendingInput = inputBuffer
        } else {
            sonicAudioProcessor.queueInput(inputBuffer)
        }
    }

    override fun queueEndOfStream() {
        if (passthrough) {
            inputEnded = true
        } else {
            sonicAudioProcessor.queueEndOfStream()
        }
    }

    override fun getOutput(): ByteBuffer {
        if (!passthrough) return sonicAudioProcessor.output
        val output = pendingInput
        pendingInput = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean =
        if (passthrough) {
            inputEnded && !pendingInput.hasRemaining()
        } else {
            sonicAudioProcessor.isEnded
        }

    override fun flush() {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        flushSonic()
    }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        pendingInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        sonicAudioProcessor.flush(streamMetadata)
    }

    override fun reset() {
        passthrough = true
        pendingInput = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        sonicAudioProcessor.reset()
        sonicAudioProcessor.setSpeed(speed)
        sonicAudioProcessor.setPitch(pitch)
    }

    private fun supportsSonic(format: AudioProcessor.AudioFormat): Boolean =
        format.sampleRate > 0 &&
            format.channelCount > 0 &&
            (format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT)

    private fun flushSonic() {
        sonicAudioProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun logSonicDisabled(format: AudioProcessor.AudioFormat, reason: String) {
        DiagnosticLog.event(
            "PlaybackTuning",
            "sonic-disabled reason=$reason sr=${format.sampleRate} ch=${format.channelCount} " +
                "enc=${format.encoding} speed=$speed pitch=$pitch",
        )
    }
}
