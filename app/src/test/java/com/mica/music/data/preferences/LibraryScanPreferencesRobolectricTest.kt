package com.mica.music.data.preferences

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
class LibraryScanPreferencesRobolectricTest {

    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun excludedScanDirectoriesRoundTripNormalizedPaths() {
        LibraryScanSettings.setExcludedScanDirectories(
            context,
            listOf(" Music/Live/ ", "Music\\Live", "Podcasts"),
        )

        assertEquals(listOf("Music/Live", "Podcasts"), LibraryScanSettings.excludedScanDirectories(context))
        assertEquals(
            listOf("Music/Live", "Podcasts"),
            LibraryScanSettings.scanOptions(context).excludedDirectories,
        )
    }

    @Test
    fun lyricsRetryRequirementIsPersistentAndDefaultsToFalse() {
        assertFalse(LibraryScanSettings.lyricsRetryRequired(context))

        LibraryScanSettings.setLyricsRetryRequired(context, true)
        assertTrue(LibraryScanSettings.lyricsRetryRequired(context))

        LibraryScanSettings.setLyricsRetryRequired(context, false)
        assertFalse(LibraryScanSettings.lyricsRetryRequired(context))
    }
}
