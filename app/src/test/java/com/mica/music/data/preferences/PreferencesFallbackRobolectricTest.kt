package com.mica.music.data.preferences

import com.mica.music.data.AlbumBrowseSortField
import com.mica.music.data.AppThemeMode
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import org.junit.Assert.assertEquals
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
            .putString("artist_browse_sort_direction", "not-a-direction")
            .putString("equalizer_band_levels", "100,bad,-200")
            .putInt("equalizer_global_gain", 1_200)
            .commit()

        assertEquals(AppThemeMode.SYSTEM, AppearancePreferences.themeMode(context))
        assertEquals(SongSortField.TITLE, LibraryBrowseSettings.songSortField(context))
        assertEquals(AlbumBrowseSortField.TITLE, LibraryBrowseSettings.albumBrowseSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.albumBrowseSortDirection(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.artistBrowseSortDirection(context))
        assertEquals(listOf<Short>(100, -200), EqualizerPreferences.equalizerBandLevels(context))
        assertEquals(1_200, EqualizerPreferences.equalizerGlobalGainMillibels(context).toInt())
        assertEquals(ParticleCoverTuning(), PlaybackUiPreferences.particleCoverTuning(context))
    }
}
