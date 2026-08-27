package com.mica.music.media

import com.mica.music.audio.AudioQualityMode

internal data class AudioPipelineState(
    val equalizerEnabled: Boolean,
    val spectrumTapEnabled: Boolean,
    val offloadPreferenceEnabled: Boolean,
    val circuitOpen: Boolean = false,
) {
    val offloadEnabled: Boolean
        get() = !equalizerEnabled &&
            !spectrumTapEnabled &&
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
) {
    private var state = initialState

    fun applyInitialConfiguration() = applyConfiguration(state)

    fun onEqualizerEnabledChanged(enabled: Boolean) {
        invalidateCircuitBreaker()
        state = state.copy(equalizerEnabled = enabled)
        persistQualityMode(if (enabled) AudioQualityMode.DSP else AudioQualityMode.HIFI)
        applyConfiguration(state)
        flushPipeline("equalizer-enabled=$enabled")
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
        invalidateCircuitBreaker()
        state = state.copy(spectrumTapEnabled = enabled)
        applyConfiguration(state)
        flushPipeline("spectrum-enabled=$enabled")
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
}
