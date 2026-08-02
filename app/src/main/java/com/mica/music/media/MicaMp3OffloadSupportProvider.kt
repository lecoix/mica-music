package com.mica.music.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * MP3 offload is disabled because the Xiaomi test device can create an encoded MP3 AudioTrack
 * without ever transitioning it to started, leaving Media3 buffering indefinitely. Other formats
 * continue to use the platform's normal offload capability decision.
 */
@UnstableApi
internal class MicaMp3OffloadSupportProvider private constructor(
    private val delegate: DefaultAudioSink.AudioOffloadSupportProvider,
) : DefaultAudioSink.AudioOffloadSupportProvider {

    constructor(context: Context) : this(DefaultAudioOffloadSupportProvider(context))

    override fun getAudioOffloadSupport(
        format: Format,
        audioAttributes: AudioAttributes,
    ): AudioOffloadSupport =
        if (format.sampleMimeType == MimeTypes.AUDIO_MPEG) {
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        } else {
            delegate.getAudioOffloadSupport(format, audioAttributes)
        }
}
