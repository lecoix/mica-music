package com.mica.music.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.DefaultRenderersFactory
import java.util.ArrayList

@UnstableApi
class MicaRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {

    private val alacBlockingSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        if (mimeType == MimeTypes.AUDIO_ALAC) {
            emptyList()
        } else {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        }
    }

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
        setMediaCodecSelector(alacBlockingSelector)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioProcessorChain(
                MicaAudioProcessorChain(
                    DsdDecimationAudioProcessor(context),
                    SpectrumAudioProcessor(),
                    MicaEqualizerManager.audioProcessor,
                ),
            )
            .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
