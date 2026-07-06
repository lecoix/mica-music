package com.mica.music.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.preferences.LibraryScanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScanStateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LibraryScanSettings.setExcludedScanDirectories(context, emptyList())
        LibraryScanSettings.setMinTrackDurationSec(context, 60)
        LibraryScanSettings.setIncludeNonMusicAudio(context, false)
        LibraryScanSettings.setDeepMetadataProbe(context, true)
    }

    @Test
    fun initialReadsLibraryScanSettings() {
        LibraryScanSettings.setMinTrackDurationSec(context, 120)
        LibraryScanSettings.setExcludedScanDirectories(context, listOf("Music/Live"))

        val state = SettingsScanState.initial(context)

        assertEquals(120, state.minDurationSec)
        assertEquals(listOf("Music/Live"), state.excludedDirectories)
        assertEquals(false, state.includeNonMusic)
        assertEquals(true, state.deepProbe)
    }

    @Test
    fun withExcludedDirectoriesPersistsAndReturnsNullWhenUnchanged() {
        val initial = SettingsScanState.initial(context).copy(excludedDirectories = listOf("Music/Live"))

        assertNull(initial.withExcludedDirectories(context, listOf("Music/Live")))

        val updated = initial.withExcludedDirectories(context, listOf("Music/Rock"))
        assertEquals(listOf("Music/Rock"), updated?.excludedDirectories)
        assertEquals(listOf("Music/Rock"), LibraryScanSettings.excludedScanDirectories(context))
    }
}
