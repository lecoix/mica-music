package com.mica.music.data.preferences

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteAutoSyncPreferencesRobolectricTest {
    @Test
    fun automaticSyncDefaultsEnabledAndPersistsOptOut() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MicaSettingsStore.prefs(context).edit().remove("remote_auto_sync_enabled").commit()

        assertTrue(RemoteAutoSyncPreferences.enabled(context))

        RemoteAutoSyncPreferences.setEnabled(context, false)
        assertFalse(RemoteAutoSyncPreferences.enabled(context))

        RemoteAutoSyncPreferences.setEnabled(context, true)
        assertTrue(RemoteAutoSyncPreferences.enabled(context))
    }
}