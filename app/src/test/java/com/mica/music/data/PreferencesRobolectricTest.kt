package com.mica.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.mica.music.ui.theme.MicaPreset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class PreferencesRobolectricTest {

    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "mica_settings",
            "mica_playback_session",
            "mica_eq_profiles",
            "mica_playlists",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun invalidEnumAndEqualizerValuesFallBackSafely() {
        context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "not-a-theme")
            .putString("song_sort_field", "not-a-sort")
            .putString("equalizer_band_levels", "100,bad,-200")
            .putInt("equalizer_global_gain", 1_200)
            .commit()

        assertEquals(AppThemeMode.SYSTEM, AppPreferences.themeMode(context))
        assertEquals(SongSortField.TITLE, AppPreferences.songSortField(context))
        assertEquals(listOf<Short>(100, -200), AppPreferences.equalizerBandLevels(context))
        assertEquals(1_200, AppPreferences.equalizerGlobalGainMillibels(context).toInt())
        assertEquals(ParticleCoverTuning(), AppPreferences.particleCoverTuning(context))
    }

    @Test
    fun particleCoverTuningRoundTrips() {
        val tuning = ParticleCoverTuning(
            erosionScale = 1.35f,
            featherScale = 1.7f,
            edgeParticleDensity = 0.88f,
            edgeParticleAlpha = 1.2f,
            edgeTravelScale = 0.4f,
            transitionParticleDensity = 1f,
        )

        AppPreferences.setParticleCoverTuning(context, tuning)

        assertEquals(tuning, AppPreferences.particleCoverTuning(context))
    }

    @Test
    fun customAccentColorRoundTrips() {
        val customColor = 0xFF2F80ED.toInt()

        AppPreferences.setAppAccentColor(context, AppAccentColor.CUSTOM)
        AppPreferences.setCustomAccentColorArgb(context, customColor)

        assertEquals(AppAccentColor.CUSTOM, AppPreferences.appAccentColor(context))
        assertEquals(customColor, AppPreferences.customAccentColorArgb(context))
    }

    @Test
    fun customMicaBackgroundRoundTrips() {
        val startColor = 0xFFFFF6EE.toInt()
        val endColor = 0xFFE3EEF8.toInt()

        AppPreferences.setMicaBackgroundPreset(context, MicaPreset.CUSTOM)
        AppPreferences.setCustomMicaStartArgb(context, startColor)
        AppPreferences.setCustomMicaEndArgb(context, endColor)
        AppPreferences.setCustomMicaSingleColor(context, true)

        assertEquals(MicaPreset.CUSTOM, AppPreferences.micaBackgroundPreset(context))
        assertEquals(startColor, AppPreferences.customMicaStartArgb(context))
        assertEquals(endColor, AppPreferences.customMicaEndArgb(context))
        assertTrue(AppPreferences.customMicaSingleColor(context))
    }

    @Test
    fun keepScreenOnWhenPlayingRoundTrips() {
        assertEquals(false, AppPreferences.keepScreenOnWhenPlaying(context))

        AppPreferences.setKeepScreenOnWhenPlaying(context, true)

        assertTrue(AppPreferences.keepScreenOnWhenPlaying(context))
    }

    @Test
    fun lyricsPageTranslationFontSizeDefaultsToOriginalAndRoundTrips() {
        context.getSharedPreferences("mica_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("lyrics_page_font_size", "large")
            .commit()

        assertEquals(22, AppPreferences.lyricsPageFontSizeSp(context))
        assertEquals(22, AppPreferences.lyricsPageTranslationFontSizeSp(context))

        AppPreferences.setLyricsPageTranslationFontSizeSp(context, 14)

        assertEquals(22, AppPreferences.lyricsPageFontSizeSp(context))
        assertEquals(14, AppPreferences.lyricsPageTranslationFontSizeSp(context))
    }

    @Test
    fun photoStackModeFromStorage() {
        assertEquals(PlayerCoverFlowMode.PHOTO_STACK, PlayerCoverFlowMode.fromStorage("photo_stack"))
        assertEquals(PlayerCoverFlowMode.STANDARD, PlayerCoverFlowMode.fromStorage("missing"))
    }

    @Test
    fun playerInfoVisibilityRoundTrips() {
        val visibility = PlayerInfoVisibility(
            showFormat = false,
            showSampleRate = true,
            showBitrate = false,
            showCurrentTime = true,
            showCustomText = true,
            customText = "Hi-Res",
        )

        AppPreferences.setPlayerInfoVisibility(context, visibility)

        assertEquals(visibility, AppPreferences.playerInfoVisibility(context))
    }

    @Test
    fun corruptProfilesAndSessionValuesDoNotEscape() {
        context.getSharedPreferences("mica_eq_profiles", Context.MODE_PRIVATE)
            .edit()
            .putString("profiles_json", "{bad")
            .putString("selection", "system:not-a-number")
            .commit()
        assertTrue(EqCustomProfileStore.listProfiles(context).isEmpty())
        assertEquals(EqSelection.System(0), EqCustomProfileStore.getSelection(context))

        PlaybackSessionStore.save(context, PlaybackSession("song", -5), sync = true)
        assertEquals(PlaybackSession("song", 0), PlaybackSessionStore.load(context))
        PlaybackSessionStore.save(context, PlaybackSession("", 100), sync = true)
        assertNull(PlaybackSessionStore.load(context))

        context.getSharedPreferences("mica_playlists", Context.MODE_PRIVATE)
            .edit()
            .putString("playlists_json", "[{\"id\":")
            .commit()
        assertTrue(PlaylistStore(context).playlists.isEmpty())
    }
}
