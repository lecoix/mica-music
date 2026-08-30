package com.mica.music.data.preferences

import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.AppThemeMode
import com.mica.music.data.ArtistBrowseSortField
import com.mica.music.data.FolderBrowseMode
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
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
class PreferencesFallbackRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun invalidEnumAndEqualizerValuesFallBackSafely() {
        context.getSharedPreferences("mica_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "not-a-theme")
            .putString("song_sort_field", "not-a-sort")
            .putString("album_browse_sort_field", "not-an-album-sort")
            .putString("album_browse_sort_direction", "not-a-direction")
            .putString("artist_browse_sort_field", "not-an-artist-sort")
            .putString("artist_browse_sort_direction", "not-a-direction")
            .putString("folder_browse_mode", "not-a-folder-mode")
            .putString("equalizer_band_levels", "100,bad,-200")
            .putInt("equalizer_global_gain", 1_200)
            .commit()

        assertEquals(AppThemeMode.SYSTEM, AppearancePreferences.themeMode(context))
        assertEquals(SongSortField.TITLE, LibraryBrowseSettings.songSortField(context))
        assertEquals(AlbumBrowseSortField.TITLE, LibraryBrowseSettings.albumBrowseSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.albumBrowseSortDirection(context))
        assertEquals(ArtistBrowseSortField.TITLE, LibraryBrowseSettings.artistBrowseSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(FolderBrowseMode.HIERARCHY, LibraryBrowseSettings.folderBrowseMode(context))
        assertEquals(listOf<Short>(100, -200), EqualizerPreferences.equalizerBandLevels(context))
        assertEquals(1_200, EqualizerPreferences.equalizerGlobalGainMillibels(context).toInt())
        assertEquals(ParticleCoverTuning(), PlaybackUiPreferences.particleCoverTuning(context))
    }

    @Test
    fun soundFxPreferencesClampAndStayInactiveByDefault() {
        assertFalse(SoundFxPreferences.isDspActive(context))

        SoundFxPreferences.save(
            context,
            com.mica.music.audio.fx.SoundFxSettings(
                enabled = true,
                stereoWidthPercent = 500,
                bassDb = 40,
                trebleDb = -40,
                reverbRoomPercent = 400,
                reverbDampingPercent = -10,
                reverbWetPercent = 500,
                surroundIntensityPercent = 400,
                surroundRotationDegPerSec = 400,
            ),
        )
        val saved = SoundFxPreferences.settings(context)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_WIDTH_PERCENT, saved.stereoWidthPercent)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_TONE_DB, saved.bassDb)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MIN_TONE_DB, saved.trebleDb)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_REVERB_PERCENT, saved.reverbRoomPercent)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MIN_REVERB_PERCENT, saved.reverbDampingPercent)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_REVERB_PERCENT, saved.reverbWetPercent)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_SURROUND_PERCENT, saved.surroundIntensityPercent)
        assertEquals(com.mica.music.audio.fx.SoundFxSettings.MAX_SURROUND_ROTATION, saved.surroundRotationDegPerSec)
        assertTrue(saved.isDspActive())

        context.getSharedPreferences("mica_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("sound_fx_stereo_width_percent", 100)
            .putInt("sound_fx_bass_db", 0)
            .putInt("sound_fx_treble_db", 0)
            .remove("sound_fx_reverb_wet_percent")
            .remove("sound_fx_surround_intensity_percent")
            .putInt("sound_fx_reverb_preset", 99)
            .commit()
        assertEquals(0, SoundFxPreferences.settings(context).reverbWetPercent)
        assertEquals(0, SoundFxPreferences.settings(context).surroundIntensityPercent)
        assertFalse(SoundFxPreferences.settings(context).isDspActive())
    }
}
