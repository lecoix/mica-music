package com.mica.music.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import com.mica.music.media.dsd.ManualNavigationPlaybackPeriodProjection
import com.mica.music.media.usb.protocol.PlaybackFamily
import com.mica.music.media.usb.shadow.UsbExclusivePlaybackAdapter
import com.mica.music.media.usb.shadow.UsbExclusiveShadowMedia3Facts

@UnstableApi
internal class PeriodAwareMediaCodecAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val playbackPeriodProjection: ManualNavigationPlaybackPeriodProjection,
    private val playbackAdapter: UsbExclusivePlaybackAdapter? = null,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {
    @Throws(ExoPlaybackException::class)
    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        playbackAdapter?.observeStream(
            UsbExclusiveShadowMedia3Facts.occurrence(mediaPeriodId),
            PlaybackFamily.PCM,
            UsbExclusiveShadowMedia3Facts.audio(formats.firstOrNull(), "platform-pcm"),
        )
        playbackPeriodProjection.onStreamChanged(mediaPeriodId)
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onDisabled() {
        try {
            super.onDisabled()
        } finally {
            playbackPeriodProjection.clear()
        }
    }
}
