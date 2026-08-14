package com.mica.music.data.preferences

import com.mica.music.data.AppAccentColor
import com.mica.music.data.CustomWallpaperCrop
import com.mica.music.data.MicaPreset
import com.mica.music.data.StatusBarVisibilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun statusBarVisibilityModeDefaultsToOffAndRoundTripsEveryMode() {
        assertEquals(
            StatusBarVisibilityMode.OFF,
            AppearancePreferences.statusBarVisibilityMode(context),
        )

        StatusBarVisibilityMode.entries.forEach { mode ->
            AppearancePreferences.setStatusBarVisibilityMode(context, mode)
            assertEquals(mode, AppearancePreferences.statusBarVisibilityMode(context))
        }
    }

    @Test
    fun statusBarVisibilityModesTargetPlayerAndNonPlayerPagesIndependently() {
        assertFalse(StatusBarVisibilityMode.OFF.hidesOnPlayer)
        assertFalse(StatusBarVisibilityMode.OFF.hidesOutsidePlayer)
        assertTrue(StatusBarVisibilityMode.PLAYER_ONLY.hidesOnPlayer)
        assertFalse(StatusBarVisibilityMode.PLAYER_ONLY.hidesOutsidePlayer)
        assertFalse(StatusBarVisibilityMode.NON_PLAYER_ONLY.hidesOnPlayer)
        assertTrue(StatusBarVisibilityMode.NON_PLAYER_ONLY.hidesOutsidePlayer)
        assertTrue(StatusBarVisibilityMode.ALL.hidesOnPlayer)
        assertTrue(StatusBarVisibilityMode.ALL.hidesOutsidePlayer)
    }

    @Test
    fun legacyHideStatusBarBooleanKeepsItsOriginalMeaning() {
        val preferences = context.getSharedPreferences(
            "mica_settings",
            android.content.Context.MODE_PRIVATE,
        )

        preferences.edit().putBoolean("hide_status_bar", true).commit()
        assertEquals(StatusBarVisibilityMode.ALL, AppearancePreferences.statusBarVisibilityMode(context))

        preferences.edit().putBoolean("hide_status_bar", false).commit()
        assertEquals(StatusBarVisibilityMode.OFF, AppearancePreferences.statusBarVisibilityMode(context))
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

    @Test
    fun customWallpaperSettingsRoundTripAndClamp() {
        AppearancePreferences.setCustomWallpaperOverlayPercent(context, 120)
        AppearancePreferences.setCustomWallpaperBlurDp(context, 40)
        AppearancePreferences.setCustomWallpaperCrop(
            context,
            CustomWallpaperCrop(zoom = 2.5f, offsetX = -0.5f, offsetY = 0.75f),
        )

        assertEquals(100, AppearancePreferences.customWallpaperOverlayPercent(context))
        assertEquals(32, AppearancePreferences.customWallpaperBlurDp(context))
        assertEquals(
            CustomWallpaperCrop(zoom = 2.5f, offsetX = -0.5f, offsetY = 0.75f),
            AppearancePreferences.customWallpaperCrop(context),
        )
    }

    @Test
    fun customWallpaperSettingsDefaultValuesAreStable() {
        assertEquals(40, AppearancePreferences.customWallpaperOverlayPercent(context))
        assertEquals(0, AppearancePreferences.customWallpaperBlurDp(context))
        assertEquals(CustomWallpaperCrop.Default, AppearancePreferences.customWallpaperCrop(context))
    }
}
