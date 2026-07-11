package com.mica.music.data.preferences

import com.mica.music.data.AppAccentColor
import com.mica.music.data.MicaPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class AppearancePreferencesRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun customAccentColorRoundTrips() {
        val customColor = 0xFF2F80ED.toInt()

        AppearancePreferences.setAppAccentColor(context, AppAccentColor.CUSTOM)
        AppearancePreferences.setCustomAccentColorArgb(context, customColor)

        assertEquals(AppAccentColor.CUSTOM, AppearancePreferences.appAccentColor(context))
        assertEquals(customColor, AppearancePreferences.customAccentColorArgb(context))
    }

    @Test
    fun customMicaBackgroundRoundTrips() {
        val startColor = 0xFFFFF6EE.toInt()
        val endColor = 0xFFE3EEF8.toInt()

        AppearancePreferences.setMicaBackgroundPreset(context, MicaPreset.CUSTOM)
        AppearancePreferences.setCustomMicaStartArgb(context, startColor)
        AppearancePreferences.setCustomMicaEndArgb(context, endColor)
        AppearancePreferences.setCustomMicaSingleColor(context, true)

        assertEquals(MicaPreset.CUSTOM, AppearancePreferences.micaBackgroundPreset(context))
        assertEquals(startColor, AppearancePreferences.customMicaStartArgb(context))
        assertEquals(endColor, AppearancePreferences.customMicaEndArgb(context))
        assertTrue(AppearancePreferences.customMicaSingleColor(context))
    }
}
