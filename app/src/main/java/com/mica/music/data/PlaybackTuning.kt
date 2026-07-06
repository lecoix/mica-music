package com.mica.music.data

import androidx.media3.common.PlaybackParameters
import kotlin.math.ln
import kotlin.math.pow

data class PlaybackTuning(
    val speed: Float = DEFAULT_SPEED,
    val pitchSemitones: Float = DEFAULT_PITCH_SEMITONES,
) {
    val pitchMultiplier: Float
        get() = semitonesToPitchMultiplier(pitchSemitones)

    val isDefault: Boolean
        get() = speed == DEFAULT_SPEED && pitchSemitones == DEFAULT_PITCH_SEMITONES

    fun withSpeed(value: Float): PlaybackTuning =
        copy(speed = value.coerceIn(MIN_SPEED, MAX_SPEED))

    fun withPitchSemitones(value: Float): PlaybackTuning =
        copy(pitchSemitones = value.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES))

    fun toPlaybackParameters(): PlaybackParameters =
        PlaybackParameters(speed, pitchMultiplier)

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val DEFAULT_SPEED = 1.0f

        const val MIN_PITCH_SEMITONES = -12.0f
        const val MAX_PITCH_SEMITONES = 12.0f
        const val DEFAULT_PITCH_SEMITONES = 0.0f

        fun coerced(speed: Float, pitchSemitones: Float): PlaybackTuning =
            PlaybackTuning()
                .withSpeed(speed)
                .withPitchSemitones(pitchSemitones)

        fun fromPlaybackParameters(parameters: PlaybackParameters): PlaybackTuning =
            coerced(
                speed = parameters.speed.coerceIn(MIN_SPEED, MAX_SPEED),
                pitchSemitones = pitchMultiplierToSemitones(parameters.pitch)
                    .coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES),
            )

        fun semitonesToPitchMultiplier(semitones: Float): Float =
            2.0.pow((semitones / 12.0).toDouble()).toFloat()

        private fun pitchMultiplierToSemitones(multiplier: Float): Float {
            val safe = multiplier.coerceAtLeast(0.0001f).toDouble()
            return (12.0 * ln(safe) / ln(2.0)).toFloat()
        }
    }
}
