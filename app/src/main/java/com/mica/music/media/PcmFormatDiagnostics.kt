package com.mica.music.media

import com.mica.music.diagnostics.AudioPipelineDebugDiagnostics
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.mica.music.util.DiagnosticLog

/** P0 PCM pipeline format logging helpers (debug builds only). */
@UnstableApi
internal object PcmFormatDiagnostics {

    const val LOG_SINK_BUILD = "AudioSinkBuild"
    const val LOG_PROCESSOR_FORMAT = "ProcessorFormat"
    const val LOG_DECODER_INPUT = "DecoderInput"
    const val LOG_AUDIO_TRACK = "AudioTrackDelivery"

    fun encodingLabel(encoding: Int): String =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> "PCM_16BIT"
            C.ENCODING_PCM_24BIT -> "PCM_24BIT"
            C.ENCODING_PCM_32BIT -> "PCM_32BIT"
            C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            C.ENCODING_INVALID -> "INVALID"
            else -> "ENC_$encoding"
        }

    fun formatLabel(format: AudioProcessor.AudioFormat): String =
        "${format.sampleRate}Hz/${format.channelCount}ch/${encodingLabel(format.encoding)}"

    fun formatLabel(format: Format): String {
        val pcmEncoding = format.pcmEncoding
        val encodingPart = if (pcmEncoding == Format.NO_VALUE) {
            format.sampleMimeType ?: "unknown-mime"
        } else {
            encodingLabel(pcmEncoding)
        }
        return "${format.sampleRate}Hz/${format.channelCount}ch/$encodingPart"
    }

    fun logSinkBuild(
        profile: String,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
        processorNames: List<String>,
    ) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        DiagnosticLog.event(
            LOG_SINK_BUILD,
            "profile=$profile enableFloatOutput=$enableFloatOutput " +
                "enableAudioOutputPlaybackParameters=$enableAudioOutputPlaybackParameters " +
                "processors=${processorNames.joinToString()}",
        )
    }

    fun logProcessorConfigure(
        processorName: String,
        inputFormat: AudioProcessor.AudioFormat,
        outputFormat: AudioProcessor.AudioFormat,
        active: Boolean,
    ) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        DiagnosticLog.event(
            LOG_PROCESSOR_FORMAT,
            "name=$processorName active=$active " +
                "input=${formatLabel(inputFormat)} output=${formatLabel(outputFormat)}",
        )
    }

    fun logDecoderInputFormat(format: Format) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        DiagnosticLog.event(
            LOG_DECODER_INPUT,
            "format=${formatLabel(format)} bitrate=${format.bitrate}",
        )
    }

    fun logAudioTrackConfig(config: AudioSink.AudioTrackConfig) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        DiagnosticLog.event(
            LOG_AUDIO_TRACK,
            "encoding=${encodingLabel(config.encoding)} " +
                "sampleRate=${config.sampleRate}Hz " +
                "bufferSize=${config.bufferSize}",
        )
    }
}
