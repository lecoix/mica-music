package com.mica.music.audio.spectrum

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val SPECTRUM_BAND_COUNT = 96

/** Read-only spectrum projection consumed by presentation code. PCM/FFT ownership stays in media. */
object SpectrumUiProjection {
    private val _levels = MutableStateFlow(List(SPECTRUM_BAND_COUNT) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private val _envelope = MutableStateFlow(0f)
    val envelope: StateFlow<Float> = _envelope.asStateFlow()

    internal fun publishLevels(levels: List<Float>) {
        _levels.value = levels
    }

    internal fun publishEnvelope(envelope: Float) {
        _envelope.value = envelope
    }

    internal fun reset() {
        _levels.value = List(SPECTRUM_BAND_COUNT) { 0f }
        _envelope.value = 0f
    }
}