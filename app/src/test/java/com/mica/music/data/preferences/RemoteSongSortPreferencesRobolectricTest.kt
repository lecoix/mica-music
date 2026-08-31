package com.mica.music.data.preferences

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteSongSortPreferencesRobolectricTest {
    @Test
    fun remoteSortPreferenceRoundTripsIndependentlyFromLocalSongSort() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LibraryBrowseSettings.setSongSort(context, SongSortField.SIZE, SortDirection.ASC)
        LibraryBrowseSettings.setRemoteSongSort(context, SongSortField.LAST_PLAYED, SortDirection.DESC)

        assertEquals(SongSortField.LAST_PLAYED, LibraryBrowseSettings.remoteSongSortField(context))
        assertEquals(SortDirection.DESC, LibraryBrowseSettings.remoteSongSortDirection(context))
        assertEquals(SongSortField.SIZE, LibraryBrowseSettings.songSortField(context))
        assertEquals(SortDirection.ASC, LibraryBrowseSettings.songSortDirection(context))
    }

    @Test(expected = IllegalArgumentException::class)
    fun remoteSortPreferenceRejectsFilesystemOnlyField() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LibraryBrowseSettings.setRemoteSongSort(context, SongSortField.FOLDER, SortDirection.ASC)
    }
}