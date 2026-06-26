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
            .commit()

        assertEquals(AppThemeMode.SYSTEM, AppPreferences.themeMode(context))
        assertEquals(SongSortField.TITLE, AppPreferences.songSortField(context))
        assertEquals(listOf<Short>(100, -200), AppPreferences.equalizerBandLevels(context))
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
    fun photoStackModeAndBurnPreferenceRoundTrip() {
        assertEquals(PlayerCoverFlowMode.PHOTO_STACK, PlayerCoverFlowMode.fromStorage("photo_stack"))
        assertEquals(PlayerCoverFlowMode.STANDARD, PlayerCoverFlowMode.fromStorage("missing"))
        assertEquals(false, AppPreferences.photoStackBurnEnabled(context))

        AppPreferences.setPhotoStackBurnEnabled(context, true)

        assertTrue(AppPreferences.photoStackBurnEnabled(context))
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
