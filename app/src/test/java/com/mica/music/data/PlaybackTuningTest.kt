package com.mica.music.data

import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTuningTest {

    @Test
    fun clampsSpeedAndPitchSemitones() {
        assertEquals(PlaybackTuning.MIN_SPEED, PlaybackTuning().withSpeed(0.1f).speed, 0.0001f)
        assertEquals(PlaybackTuning.MAX_SPEED, PlaybackTuning().withSpeed(3f).speed, 0.0001f)
        assertEquals(
            PlaybackTuning.MIN_PITCH_SEMITONES,
            PlaybackTuning().withPitchSemitones(-24f).pitchSemitones,
            0.0001f,
        )
        assertEquals(
            PlaybackTuning.MAX_PITCH_SEMITONES,
            PlaybackTuning().withPitchSemitones(24f).pitchSemitones,
            0.0001f,
        )
    }

    @Test
    fun convertsSemitonesToPlaybackParameters() {
        val parameters = PlaybackTuning(speed = 1.25f, pitchSemitones = 12f)
            .toPlaybackParameters()

        assertEquals(1.25f, parameters.speed, 0.0001f)
        assertEquals(2.0f, parameters.pitch, 0.0001f)
    }

    @Test
    fun readsPlaybackParametersBackToSemitones() {
        val tuning = PlaybackTuning.fromPlaybackParameters(PlaybackParameters(0.75f, 0.5f))

        assertEquals(0.75f, tuning.speed)
        assertEquals(-12f, tuning.pitchSemitones, 0.0001f)
    }
}
