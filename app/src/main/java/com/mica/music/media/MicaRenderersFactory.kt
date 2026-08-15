package com.mica.music.media

import android.content.Context
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegRendererSupportProbe
import com.mica.music.data.PlaybackTuning
import com.mica.music.media.dsd.DirectDsdPrototypeRendererLoader
import com.mica.music.media.dsd.DirectDsdTrackTransitionCoordinator
import com.mica.music.media.dsd.ManualNavigationTransitionBridge
import com.mica.music.media.dsd.ManualNavigationPlaybackPeriodProjection
import com.mica.music.media.dsd.TransitionAwarePcmAudioSink
import java.util.ArrayList

@UnstableApi
internal class MicaRenderersFactory(
    context: Context,
    private val outputPath: AudioOutputPathConfig = AudioOutputPathConfig.PRODUCTION,
    private val trackTransitionCoordinator: DirectDsdTrackTransitionCoordinator = DirectDsdTrackTransitionCoordinator(),
    private val manualNavigationTransitionBridge: ManualNavigationTransitionBridge = ManualNavigationTransitionBridge(),
) : DefaultRenderersFactory(context) {

    private val platformPlaybackPeriodProjection =
        ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge)

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
        installRendererSupportProbe()
    }

    private fun installRendererSupportProbe() {
        // R1a: diagnostic-only probe at the real renderer supportsFormat seam. Never enabled in
        // release (no listener registered), so renderer selection and playback are unchanged.
        if (!AudioPipelineDebugDiagnostics.formatTraceEnabled) return
        FfmpegRendererSupportProbe.setListener { rendererName, format, formatSupport ->
            RendererSupportProbeDiagnostics.logSupportsFormat(rendererName, format, formatSupport)
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        if (outputPath.outputMode.requiresMinimalProcessorChain) {
            return transitionAwarePcmSink(
                buildUsbDirectMinimalSink(
                    context = context,
                    profileLabel = "usb-direct-platform",
                    enableFloatOutput = enableFloatOutput,
                ),
                platformPlaybackPeriodProjection,
            )
        }
        val processorChain = buildUnifiedFixedChain(context)
        PcmFormatDiagnostics.logSinkBuild(
            profile = "$UNIFIED_SINK_PROFILE+production-int16-sink",
            enableFloatOutput = false,
            enableAudioOutputPlaybackParameters = false,
            processorNames = processorChain.processorNamesForDiagnostics(),
        )
        return transitionAwarePcmSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(false)
                .setAudioProcessorChain(processorChain)
                .build(),
            platformPlaybackPeriodProjection,
        )
    }

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
        val tracingListener = if (AudioPipelineDebugDiagnostics.formatTraceEnabled) {
            PipelineAudioRendererEventListener(eventListener)
        } else {
            eventListener
        }
        if (PcmDeliveryExperiment.rendererSplit) {
            buildRendererSplitAudioRenderers(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                tracingListener,
                out,
            )
            return
        }
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            tracingListener,
            out,
        )
        replacePlatformAudioRenderer(
            context,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            tracingListener,
            out,
        )
    }

    /**
     * R1b: one ExoPlayer, mutually-exclusive DsdOnly / PcmOnly FFmpeg renderers each bound to its own
     * sink, then the platform renderer (via [super]) for AAC/MP3/WAV. FFmpeg renderers are added
     * first so they win ties over the platform renderer (extension-prefer semantics). The platform
     * renderer keeps the unified fixed chain [audioSink] (X behaviour for those formats).
     *
     * The PcmOnly sink uses enableFloatOutput=true for hi-res PCM with EQ + spectrum +
     * hardware speed/pitch via [MicaFloatDspAudioSink] (R4).
     */
    private fun buildRendererSplitAudioRenderers(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        platformAudioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        DirectDsdPrototypeRendererLoader.create(
            context,
            trackTransitionCoordinator,
            manualNavigationTransitionBridge,
        )?.let(out::add)
        val dsdPeriodProjection = ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge)
        out.add(
            FfmpegAudioRenderer(
                eventHandler,
                eventListener,
                buildDsdAudioSink(context, dsdPeriodProjection),
                "DsdOnly",
                MicaRendererSupportPolicies.dsdOnly,
                false,
                FfmpegAudioRenderer.StreamPeriodObserver(dsdPeriodProjection::onStreamChanged),
            ),
        )
        val pcmPeriodProjection = ManualNavigationPlaybackPeriodProjection(manualNavigationTransitionBridge)
        out.add(
            FfmpegAudioRenderer(
                eventHandler,
                eventListener,
                buildPcmAudioSink(context, pcmPeriodProjection),
                "PcmOnly",
                MicaRendererSupportPolicies.pcmOnly,
                outputPath.usbOutputRequest != null,
                FfmpegAudioRenderer.StreamPeriodObserver(pcmPeriodProjection::onStreamChanged),
            ),
        )
        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            platformAudioSink,
            eventHandler,
            eventListener,
            out,
        )
        replacePlatformAudioRenderer(
            context,
            mediaCodecSelector,
            enableDecoderFallback,
            platformAudioSink,
            eventHandler,
            eventListener,
            out,
        )
    }

    private fun buildDsdAudioSink(
        context: Context,
        playbackPeriodProjection: ManualNavigationPlaybackPeriodProjection,
    ): AudioSink {
        if (outputPath.outputMode.requiresMinimalProcessorChain) {
            return transitionAwarePcmSink(buildUsbDirectDsdSink(context), playbackPeriodProjection)
        }
        val trace = AudioPipelineDebugDiagnostics.formatTraceEnabled
        val chain = MicaAudioProcessorChain(
            *buildList {
                if (trace) add(PipelineFormatTraceAudioProcessor("dsd-sink-entry"))
                add(buildDsdDecimationProcessor(context))
                if (trace) add(PipelineFormatTraceAudioProcessor("dsd-after-dsd"))
                add(SpectrumAudioProcessor())
                if (trace) add(PipelineFormatTraceAudioProcessor("dsd-after-spectrum"))
                // R4 follow-up: EQ on the decimated 24-bit int PCM. SoftwareEqualizer has a
                // 24-bit branch, so this is purely additive (off by default → bit-exact). Speed/
                // pitch remain unavailable here until P4 FloatPcm decimation (Sonic needs float).
                add(MicaEqualizerManager.createAudioProcessor())
                if (trace) add(PipelineFormatTraceAudioProcessor("dsd-after-eq"))
            }.toTypedArray(),
            includePlaybackTuning = outputPath.outputMode.allowsSharedPcmDsp,
            includeFormatTrace = trace,
        )
        val enableFloatDsdOutput = outputPath.dsdDecimationMode == DsdDecimationOutputMode.FloatPcm
        PcmFormatDiagnostics.logSinkBuild(
            profile = "$RENDERER_SPLIT_PROFILE+R1b-dsd-sink+R4-eq+${outputPath.dsdDecimationMode.name.lowercase()}",
            enableFloatOutput = enableFloatDsdOutput,
            enableAudioOutputPlaybackParameters = false,
            processorNames = chain.processorNamesForDiagnostics(),
        )
        return transitionAwarePcmSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatDsdOutput)
                .setEnableAudioOutputPlaybackParameters(false)
                .setAudioProcessorChain(chain)
                .build(),
            playbackPeriodProjection,
        )
    }

    /**
     * R4: the float (hi-res) PCM sink. Media3 excludes the custom [MicaAudioProcessorChain] from its
     * float output path, so the inner sink carries an empty chain and hardware speed/pitch
     * (`enableAudioOutputPlaybackParameters=true`); EQ + spectrum run in [MicaFloatDspAudioSink],
     * which wraps the inner sink and processes float PCM without changing frame counts. Purely
     * additive to audio quality: bit-exact passthrough when EQ off and spectrum inactive.
     */
    private fun buildPcmAudioSink(
        context: Context,
        playbackPeriodProjection: ManualNavigationPlaybackPeriodProjection,
    ): AudioSink {
        if (outputPath.outputMode.requiresMinimalProcessorChain) {
            return transitionAwarePcmSink(
                buildUsbDirectMinimalSink(
                    context = context,
                    profileLabel = "usb-direct-pcm",
                    enableFloatOutput = true,
                ),
                playbackPeriodProjection,
            )
        }
        val chain = MicaAudioProcessorChain(
            includePlaybackTuning = false,
            includeFormatTrace = false,
        )
        PcmFormatDiagnostics.logSinkBuild(
            profile = "$RENDERER_SPLIT_PROFILE+R4-pcm-float-dsp-sink",
            enableFloatOutput = true,
            enableAudioOutputPlaybackParameters = true,
            processorNames = chain.processorNamesForDiagnostics(),
        )
        val inner = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setAudioTrackBufferSizeProvider(
                MicaCappedSpeedBufferSizeProvider(PlaybackTuning.MAX_SPEED.toDouble()),
            )
            .setAudioProcessorChain(chain)
            .build()
        return transitionAwarePcmSink(
            MicaFloatDspAudioSink(inner, MicaEqualizerSpectrumTap()),
            playbackPeriodProjection,
        )
    }

    private fun transitionAwarePcmSink(
        delegate: AudioSink,
        playbackPeriodProjection: ManualNavigationPlaybackPeriodProjection,
    ): AudioSink =
        TransitionAwarePcmAudioSink(
            delegate,
            trackTransitionCoordinator,
            manualNavigationTransitionBridge,
            playbackPeriodProjection,
        )

    private fun replacePlatformAudioRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val index = out.indexOfFirst { it.javaClass == MediaCodecAudioRenderer::class.java }
        if (index < 0) return
        out[index] = PeriodAwareMediaCodecAudioRenderer(
            context,
            getCodecAdapterFactory(),
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink,
            platformPlaybackPeriodProjection,
        )
    }

    private fun buildUnifiedFixedChain(context: Context): MicaAudioProcessorChain {
        val trace = AudioPipelineDebugDiagnostics.formatTraceEnabled
        return MicaAudioProcessorChain(
            *buildList {
                if (trace) add(PipelineFormatTraceAudioProcessor("sink-entry"))
                add(buildDsdDecimationProcessor(context))
                if (trace) add(PipelineFormatTraceAudioProcessor("after-dsd"))
                add(SpectrumAudioProcessor())
                if (trace) add(PipelineFormatTraceAudioProcessor("after-spectrum"))
                add(MicaEqualizerManager.audioProcessor)
                if (trace) add(PipelineFormatTraceAudioProcessor("after-eq"))
            }.toTypedArray(),
            includePlaybackTuning = outputPath.outputMode.allowsSharedPcmDsp,
            includeFormatTrace = trace,
        )
    }

    private fun buildDsdDecimationProcessor(context: Context): DsdDecimationAudioProcessor =
        DsdDecimationAudioProcessor(context, outputPath.dsdDecimationMode)

    /**
     * P6 USB Direct PCM: DSD still decimates but skips EQ/spectrum/Sonic (minimal chain).
     * Not active until [PlaybackOutputMode.UsbDirectPcm] is selected at stack build.
     */
    private fun buildUsbDirectDsdSink(context: Context): AudioSink =
        buildUsbDirectMinimalSink(
            context = context,
            profileLabel = "usb-direct-dsd",
            enableFloatOutput = false,
            buildDsdDecimationProcessor(context),
        )

    /**
     * P6 USB Direct PCM: empty processor chain, no [MicaFloatDspAudioSink] wrapper.
     * [enableFloatOutput] follows USB DAC capability probe when P6 lands.
     */
    private fun buildUsbDirectMinimalSink(
        context: Context,
        profileLabel: String,
        enableFloatOutput: Boolean,
        vararg extraProcessors: androidx.media3.common.audio.AudioProcessor,
    ): AudioSink {
        val chain = MicaAudioProcessorChain(
            *extraProcessors,
            includePlaybackTuning = false,
            includeFormatTrace = false,
        )
        PcmFormatDiagnostics.logSinkBuild(
            profile = "$RENDERER_SPLIT_PROFILE+P6-$profileLabel",
            enableFloatOutput = enableFloatOutput,
            enableAudioOutputPlaybackParameters = false,
            processorNames = chain.processorNamesForDiagnostics(),
        )
        val provider = UsbHostOutputAdapter.createProvider(context, outputPath)
        return DefaultAudioSink.Builder(context)
            // Keep high-resolution integer decoder output as float. The SK02 prototype provider
            // accepts it only when every float maps exactly to signed PCM24; it fails closed
            // instead of silently truncating to PCM16.
            .setEnableFloatOutput(enableFloatOutput || outputPath.usbOutputRequest != null)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioProcessorChain(chain)
            .setAudioOutputProvider(provider)
            .build()
    }

    private companion object {
        const val UNIFIED_SINK_PROFILE = "UnifiedFixedChain"
        const val RENDERER_SPLIT_PROFILE = "RendererSplit"
    }
}
