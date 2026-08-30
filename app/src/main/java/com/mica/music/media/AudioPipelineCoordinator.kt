package com.mica.music.media

import com.mica.music.audio.AudioQualityMode

internal data class AudioPipelineState(
    val equalizerEnabled: Boolean,
    val spectrumTapEnabled: Boolean,
    val offloadPreferenceEnabled: Boolean,
    val circuitOpen: Boolean = false,
    val replayGainDspEnabled: Boolean = false,
    val channelBalanceDspEnabled: Boolean = false,
    val soundFxDspEnabled: Boolean = false,
    val pcmSessionLatched: Boolean = false,
) {
    val softwareDspRequested: Boolean
        get() = equalizerEnabled ||
            spectrumTapEnabled ||
            replayGainDspEnabled ||
            channelBalanceDspEnabled ||
            soundFxDspEnabled

    val offloadEnabled: Boolean
        get() = !equalizerEnabled &&
            !spectrumTapEnabled &&
            !replayGainDspEnabled &&
            !channelBalanceDspEnabled &&
            !soundFxDspEnabled &&
            !pcmSessionLatched &&
            offloadPreferenceEnabled &&
            !circuitOpen
}

internal class AudioPipelineCoordinator(
    initialState: AudioPipelineState,
    private val invalidateCircuitBreaker: () -> Unit,
    private val resetCircuitBreaker: () -> Unit,
    private val applyConfiguration: (AudioPipelineState) -> Unit,
    private val persistQualityMode: (AudioQualityMode) -> Unit,
    private val flushPipeline: (String) -> Unit,
    private val isOffloadedPlayback: () -> Boolean = { false },
) {
    private var state = initialState.copy(
        pcmSessionLatched = initialState.pcmSessionLatched || initialState.softwareDspRequested,
    )

    fun applyInitialConfiguration() = applyConfiguration(state)

    fun onEqualizerEnabledChanged(enabled: Boolean) {
        persistQualityMode(if (enabled) AudioQualityMode.DSP else AudioQualityMode.HIFI)
        updateSoftwareDspState("equalizer-enabled=$enabled") {
            it.copy(equalizerEnabled = enabled)
        }
    }

    fun onOffloadPreferenceChanged(enabled: Boolean) {
        val wasOffloadEnabled = state.offloadEnabled
        if (enabled) {
            resetCircuitBreaker()
        } else {
            invalidateCircuitBreaker()
        }
        state = state.copy(
            offloadPreferenceEnabled = enabled,
            circuitOpen = if (enabled) false else state.circuitOpen,
        )
        applyConfiguration(state)
        if (wasOffloadEnabled != state.offloadEnabled) {
            flushPipeline("offload-user-enabled=$enabled")
        }
    }

    fun onSpectrumTapEnabledChanged(enabled: Boolean) {
        updateSoftwareDspState("spectrum-enabled=$enabled") {
            it.copy(spectrumTapEnabled = enabled)
        }
    }

    fun onReplayGainDspEnabledChanged(enabled: Boolean) {
        if (state.replayGainDspEnabled == enabled) return
        updateSoftwareDspState("replaygain-dsp-enabled=$enabled") {
            it.copy(replayGainDspEnabled = enabled)
        }
    }

    fun onChannelBalanceDspEnabledChanged(enabled: Boolean) {
        if (state.channelBalanceDspEnabled == enabled) return
        updateSoftwareDspState("channel-balance-enabled=$enabled") {
            it.copy(channelBalanceDspEnabled = enabled)
        }
    }

    fun onSoundFxDspEnabledChanged(enabled: Boolean) {
        if (state.soundFxDspEnabled == enabled) return
        updateSoftwareDspState("sound-fx-enabled=$enabled") {
            it.copy(soundFxDspEnabled = enabled)
        }
    }

    fun onOffloadCircuitOpened() {
        state = state.copy(circuitOpen = true)
        applyConfiguration(state)
        flushPipeline("offload-stall-fallback")
    }

    fun onRouteChanged(reason: String) {
        invalidateCircuitBreaker()
        flushPipeline(reason)
    }

    private inline fun updateSoftwareDspState(
        reason: String,
        transform: (AudioPipelineState) -> AudioPipelineState,
    ) {
        val wasOffloadEnabled = state.offloadEnabled
        val previousDspRequested = state.softwareDspRequested
        val transformed = transform(state)
        state = transformed.copy(
            pcmSessionLatched = transformed.pcmSessionLatched ||
                previousDspRequested ||
                transformed.softwareDspRequested,
        )
        if (wasOffloadEnabled == state.offloadEnabled) return

        invalidateCircuitBreaker()
        applyConfiguration(state)
        if (wasOffloadEnabled && !state.offloadEnabled && isOffloadedPlayback()) {
            flushPipeline("offload-to-pcm:$reason")
        }
    }
}
