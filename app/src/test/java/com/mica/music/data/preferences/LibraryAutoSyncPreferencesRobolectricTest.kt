package com.mica.music.data.preferences

import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.ScanSource
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryAutoSyncPreferencesRobolectricTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearOverrides() {
        LibraryAutoSyncPreferences.clearOverrides(context)
    }

    @Test
    fun defaultsEnableBothSources() {
        LibraryAutoSyncPreferences.clearOverrides(context)

        assertTrue(LibraryAutoSyncPreferences.enabled(context, ScanSource.DEVICE))
        assertTrue(LibraryAutoSyncPreferences.enabled(context, ScanSource.FOLDER))
    }

    @Test
    fun globalGateDisablesBothSourcesWithoutChangingSourceGates() {
        LibraryAutoSyncPreferences.clearOverrides(context)
        LibraryAutoSyncPreferences.setGloballyEnabled(context, false)

        assertFalse(LibraryAutoSyncPreferences.enabled(context, ScanSource.DEVICE))
        assertFalse(LibraryAutoSyncPreferences.enabled(context, ScanSource.FOLDER))
        assertTrue(LibraryAutoSyncPreferences.sourceEnabled(context, ScanSource.DEVICE))
        assertTrue(LibraryAutoSyncPreferences.sourceEnabled(context, ScanSource.FOLDER))
    }

    @Test
    fun sourceGatesAreIndependent() {
        LibraryAutoSyncPreferences.clearOverrides(context)
        LibraryAutoSyncPreferences.setSourceEnabled(context, ScanSource.DEVICE, false)

        assertFalse(LibraryAutoSyncPreferences.enabled(context, ScanSource.DEVICE))
        assertTrue(LibraryAutoSyncPreferences.enabled(context, ScanSource.FOLDER))

        LibraryAutoSyncPreferences.setSourceEnabled(context, ScanSource.DEVICE, true)
        LibraryAutoSyncPreferences.setSourceEnabled(context, ScanSource.FOLDER, false)

        assertTrue(LibraryAutoSyncPreferences.enabled(context, ScanSource.DEVICE))
        assertFalse(LibraryAutoSyncPreferences.enabled(context, ScanSource.FOLDER))
    }
}
