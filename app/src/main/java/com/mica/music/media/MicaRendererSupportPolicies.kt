package com.mica.music.media

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegFormatPolicy
import com.mica.music.data.DsdSupport

/**
 * R1b: mutually-exclusive renderer allowlists for the renderer-split FFmpeg renderers.
 *
 * Derived from the R1a on-device matrix (log 34/35):
 * - DSD (`audio/dsd`) → DsdOnly.
 * - FLAC / ALAC → PcmOnly (FFmpeg decodes both; platform ALAC is blocked upstream).
 * - MP3 / AAC / WAV(`audio/raw`) → neither, so the platform renderer claims them.
 */
@UnstableApi
internal object MicaRendererSupportPolicies {

    fun dsdOnlyAccepts(mime: String?, codecs: String?): Boolean =
        (mime != null && DsdSupport.isDsdMime(mime)) ||
            (codecs != null && DsdSupport.isDsdMime(codecs))

    fun pcmOnlyAccepts(mime: String?): Boolean =
        mime == MimeTypes.AUDIO_FLAC || mime == MimeTypes.AUDIO_ALAC

    val dsdOnly = FfmpegFormatPolicy { format ->
        dsdOnlyAccepts(format.sampleMimeType, format.codecs)
    }

    val pcmOnly = FfmpegFormatPolicy { format ->
        pcmOnlyAccepts(format.sampleMimeType)
    }
}
