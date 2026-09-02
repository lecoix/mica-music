package com.mica.music.media

import com.mica.music.diagnostics.AudioPipelineDebugDiagnostics
import androidx.media3.common.C
import androidx.media3.common.Format
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
import java.util.Locale

internal object RendererSupportProbeDiagnostics {
    private const val LOG_RENDERER_SUPPORT_PROBE = "RendererSupportProbe"
    private const val LOG_PLAYBACK_ROUTE_PROBE = "PlaybackRouteProbe"
    private const val DSD_RATE_THRESHOLD = 352_800

    /**
     * R1a: logs the real renderer `supportsFormat()` decision (accept/reject matrix). Diagnostic
     * only — does not change renderer selection, sink, or playback behaviour.
     */
    fun logSupportsFormat(rendererName: String, format: Format, formatSupport: Int) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        val candidate = candidateForFormat(format)
        val outcome = describeFormatSupport(formatSupport)
        DiagnosticLog.event(
            LOG_RENDERER_SUPPORT_PROBE,
            "stage=supports-format " +
                "rendererClass=$rendererName " +
                "roleCandidate=${candidate.roleLabel} " +
                "decision=${outcome.decision} " +
                "result=${outcome.label} " +
                "dsdOnly=${candidate.dsdOnly} " +
                "pcmOnly=${candidate.pcmOnly} " +
                "platformDefault=${candidate.platformDefault} " +
                "evidence=${candidate.evidence} " +
                "mime=${format.sampleMimeType ?: "null"} " +
                "codecs=${format.codecs ?: "null"} " +
                "sampleRate=${format.sampleRate} " +
                "channelCount=${format.channelCount} " +
                "pcmEncoding=${PcmFormatDiagnostics.encodingLabel(format.pcmEncoding)} " +
                "id=${format.id ?: "null"}",
        )
    }

    internal fun describeFormatSupport(formatSupport: Int): FormatSupportOutcome {
        return when (formatSupport) {
            C.FORMAT_HANDLED -> FormatSupportOutcome("accept", "handled")
            C.FORMAT_EXCEEDS_CAPABILITIES -> FormatSupportOutcome("reject", "exceeds-capabilities")
            C.FORMAT_UNSUPPORTED_DRM -> FormatSupportOutcome("reject", "unsupported-drm")
            C.FORMAT_UNSUPPORTED_SUBTYPE -> FormatSupportOutcome("reject", "unsupported-subtype")
            C.FORMAT_UNSUPPORTED_TYPE -> FormatSupportOutcome("reject", "unsupported-type")
            else -> FormatSupportOutcome("reject", "unknown($formatSupport)")
        }
    }

    fun logFormat(format: Format, decoderName: String?) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        val candidate = candidateForFormat(format)
        DiagnosticLog.event(
            LOG_RENDERER_SUPPORT_PROBE,
            "stage=decoder-input " +
                "roleCandidate=${candidate.roleLabel} " +
                "dsdOnly=${candidate.dsdOnly} " +
                "pcmOnly=${candidate.pcmOnly} " +
                "platformDefault=${candidate.platformDefault} " +
                "evidence=${candidate.evidence} " +
                "decoder=${decoderName ?: "unknown"} " +
                "mime=${format.sampleMimeType ?: "null"} " +
                "codecs=${format.codecs ?: "null"} " +
                "sampleRate=${format.sampleRate} " +
                "channelCount=${format.channelCount} " +
                "pcmEncoding=${PcmFormatDiagnostics.encodingLabel(format.pcmEncoding)} " +
                "id=${format.id ?: "null"}",
        )
    }

    fun logRoute(song: Song, decision: PlaybackRouteDecision) {
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        val ext = song.fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val dsdMetadata = DsdSupport.isDsdMetadata(song.metadata)
        val dsdExtension = DsdSupport.isDsdExtension(ext)
        val route = when (decision) {
            is PlaybackRouteDecision.Supported -> "supported:${decision.reason}"
            is PlaybackRouteDecision.Unsupported -> "unsupported:${decision.reason}"
        }
        val candidate = candidateForSong(song, decision, dsdMetadata, dsdExtension)
        DiagnosticLog.event(
            LOG_PLAYBACK_ROUTE_PROBE,
            "song=${song.id} " +
                "file=${song.fileName.ifBlank { "unknown" }} " +
                "ext=${ext.ifBlank { "none" }} " +
                "route=$route " +
                "roleCandidate=${candidate.roleLabel} " +
                "dsdOnly=${candidate.dsdOnly} " +
                "pcmOnly=${candidate.pcmOnly} " +
                "platformDefault=${candidate.platformDefault} " +
                "dsdMetadata=$dsdMetadata " +
                "dsdExtension=$dsdExtension " +
                "container=${song.metadata.containerName} " +
                "mime=${song.metadata.playbackMimeType} " +
                "sampleRate=${song.metadata.sampleRateHz} " +
                "bits=${song.metadata.bitsPerSample} " +
                "channels=${song.metadata.channelCount}",
        )
    }

    internal fun candidateForFormat(format: Format): ProbeCandidate {
        val mime = format.sampleMimeType.orEmpty()
        val codecs = format.codecs.orEmpty()
        val mimeOrCodecDsd = DsdSupport.isDsdMime(mime) || DsdSupport.isDsdMime(codecs)
        val highRateDsdPcm = format.sampleRate >= DSD_RATE_THRESHOLD &&
            (format.pcmEncoding == C.ENCODING_PCM_FLOAT ||
                format.pcmEncoding == C.ENCODING_PCM_16BIT)
        val dsd = mimeOrCodecDsd || highRateDsdPcm
        val pcm = !dsd && (
            isPcmContainerMime(mime) ||
                format.pcmEncoding != Format.NO_VALUE
            )
        val platform = !dsd && !pcm
        val evidence = when {
            mimeOrCodecDsd -> "mime-or-codecs"
            highRateDsdPcm -> "high-rate-pcm"
            pcm -> "pcm-format"
            else -> "platform-fallback"
        }
        return ProbeCandidate(dsd, pcm, platform, evidence)
    }

    private fun candidateForSong(
        song: Song,
        decision: PlaybackRouteDecision,
        dsdMetadata: Boolean,
        dsdExtension: Boolean,
    ): ProbeCandidate {
        val dsd = dsdMetadata || dsdExtension
        val pcm = !dsd && (
            decision is PlaybackRouteDecision.Supported &&
                (decision.reason == "alac-ffmpeg" || isPcmContainerMime(song.metadata.playbackMimeType))
            )
        val platform = !dsd && !pcm
        val evidence = when {
            dsdMetadata -> "song-dsd-metadata"
            dsdExtension -> "song-dsd-extension"
            pcm -> "song-pcm-route"
            else -> "song-platform-route"
        }
        return ProbeCandidate(dsd, pcm, platform, evidence)
    }

    private fun isPcmContainerMime(mime: String): Boolean {
        val normalized = mime.lowercase(Locale.US)
        return "flac" in normalized ||
            "alac" in normalized ||
            "wav" in normalized ||
            "wave" in normalized ||
            "pcm" in normalized ||
            normalized == "audio/raw"
    }

    internal data class FormatSupportOutcome(
        val decision: String,
        val label: String,
    )

    internal data class ProbeCandidate(
        val dsdOnly: Boolean,
        val pcmOnly: Boolean,
        val platformDefault: Boolean,
        val evidence: String,
    ) {
        val roleLabel: String
            get() = when {
                dsdOnly -> "DsdOnly"
                pcmOnly -> "PcmOnly"
                else -> "PlatformDefault"
            }
    }
}
