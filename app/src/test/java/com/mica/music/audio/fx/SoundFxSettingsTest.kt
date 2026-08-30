package com.mica.music.audio.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFxSettingsTest {
    @Test
    fun defaultSettingsDoNotRequestDsp() {
        assertFalse(SoundFxSettings().isDspActive())
        assertFalse(SoundFxSettings(enabled = true).isDspActive())
        assertFalse(
            SoundFxSettings(enabled = true, surroundRotationDegPerSec = 90).isDspActive(),
        )
        assertFalse(
            SoundFxSettings(enabled = true, reverbRoomPercent = 90).isDspActive(),
        )
    }

    @Test
    fun surroundIntensityRequestsDsp() {
        assertTrue(
            SoundFxSettings(enabled = true, surroundIntensityPercent = 40).isDspActive(),
        )
        assertFalse(
            SoundFxSettings(enabled = false, surroundIntensityPercent = 40).isDspActive(),
        )
    }

    @Test
    fun enabledNonNeutralWidthRequestsDsp() {
        assertTrue(
            SoundFxSettings(enabled = true, stereoWidthPercent = 120).isDspActive(),
        )
        assertFalse(
            SoundFxSettings(enabled = false, stereoWidthPercent = 120).isDspActive(),
        )
    }

    @Test
    fun reverbWetRequestsDsp() {
        assertTrue(
            SoundFxSettings(enabled = true, reverbWetPercent = 20).isDspActive(),
        )
        assertFalse(
            SoundFxSettings(enabled = false, reverbWetPercent = 20).isDspActive(),
        )
    }

    @Test
    fun invalidValuesAreClamped() {
        val settings = SoundFxSettings(
            enabled = true,
            stereoWidthPercent = 9_000,
            bassDb = 99,
            trebleDb = -80,
            reverbRoomPercent = 400,
            reverbDampingPercent = -10,
            reverbWetPercent = 500,
            surroundIntensityPercent = 400,
            surroundRotationDegPerSec = -10,
        ).sanitized()

        assertEquals(SoundFxSettings.MAX_WIDTH_PERCENT, settings.stereoWidthPercent)
        assertEquals(SoundFxSettings.MAX_TONE_DB, settings.bassDb)
        assertEquals(SoundFxSettings.MIN_TONE_DB, settings.trebleDb)
        assertEquals(SoundFxSettings.MAX_REVERB_PERCENT, settings.reverbRoomPercent)
        assertEquals(SoundFxSettings.MIN_REVERB_PERCENT, settings.reverbDampingPercent)
        assertEquals(SoundFxSettings.MAX_REVERB_PERCENT, settings.reverbWetPercent)
        assertEquals(SoundFxSettings.MAX_SURROUND_PERCENT, settings.surroundIntensityPercent)
        assertEquals(SoundFxSettings.MIN_SURROUND_ROTATION, settings.surroundRotationDegPerSec)
        assertTrue(settings.isDspActive())
    }
}
