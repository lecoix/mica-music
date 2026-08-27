package com.mica.music.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class LibraryZoomPreferencesRobolectricTest {
    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun `page zoom identities persist independently`() {
        val ids = setOf("dense_list", "normal_list", "dense_grid", "large_grid")

        assertEquals(
            "normal_list",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.SONGS, "normal_list", ids),
        )
        assertEquals(
            "normal_list",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.SEARCH, "normal_list", ids),
        )

        LibraryZoomPreferences.setPresetId(context, LibraryZoomPage.SONGS, "large_grid", ids)
        LibraryZoomPreferences.setPresetId(context, LibraryZoomPage.SEARCH, "dense_list", ids)

        assertEquals(
            "large_grid",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.SONGS, "normal_list", ids),
        )
        assertEquals(
            "dense_list",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.SEARCH, "normal_list", ids),
        )
    }

    @Test
    fun `unknown persisted identity falls back and invalid writes are ignored`() {
        val ids = setOf("normal_list", "large_grid")
        LibraryZoomPreferences.setPresetId(context, LibraryZoomPage.RECENT, "not_a_preset", ids)

        assertEquals(
            "normal_list",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.RECENT, "normal_list", ids),
        )

        context.getSharedPreferences("mica_settings", 0).edit()
            .putString("list_zoom_recent", "removed_old_preset")
            .commit()

        assertEquals(
            "large_grid",
            LibraryZoomPreferences.presetId(context, LibraryZoomPage.RECENT, "large_grid", ids),
        )
    }
}
