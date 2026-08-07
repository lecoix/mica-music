package com.mica.music.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioOffloadPreferencesRobolectricTest {
    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun defaultsEnabledOnDevicesOutsideBuiltInDenylist() {
        val state = AudioOffloadPreferences.state(context, "1:build-a", builtInDenied = false)

        assertTrue(state.enabled)
        assertNull(state.disabledReason)
    }

    @Test
    fun builtInDenylistChangesDefaultButAllowsManualRetry() {
        assertEquals(
            AudioOffloadDisabledReason.BUILT_IN_DENYLIST,
            AudioOffloadPreferences.state(context, "1:build-a", builtInDenied = true).disabledReason,
        )

        AudioOffloadPreferences.setEnabled(context, true)

        assertTrue(AudioOffloadPreferences.state(context, "1:build-a", builtInDenied = true).enabled)
    }

    @Test
    fun verifiedFailureDisablesOnlyTheRecordedBuild() {
        AudioOffloadPreferences.recordVerifiedFailure(context, "1:build-a")

        val currentBuild = AudioOffloadPreferences.state(context, "1:build-a", builtInDenied = false)
        val updatedBuild = AudioOffloadPreferences.state(context, "1:build-b", builtInDenied = false)

        assertFalse(currentBuild.enabled)
        assertEquals(AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE, currentBuild.disabledReason)
        assertTrue(updatedBuild.enabled)
        assertNull(updatedBuild.disabledReason)
    }

    @Test
    fun explicitManualDisableSurvivesBuildChange() {
        AudioOffloadPreferences.recordVerifiedFailure(context, "1:build-a")
        AudioOffloadPreferences.setEnabled(context, false)

        val updatedBuild = AudioOffloadPreferences.state(context, "1:build-b", builtInDenied = false)

        assertFalse(updatedBuild.enabled)
        assertNull(updatedBuild.disabledReason)
    }

    @Test
    fun manualChoiceClearsVerifiedFailureRecord() {
        AudioOffloadPreferences.recordVerifiedFailure(context, "1:build-a")

        AudioOffloadPreferences.setEnabled(context, true)

        assertTrue(AudioOffloadPreferences.state(context, "1:build-a", builtInDenied = false).enabled)
    }

    @Test
    fun knownXiaomiAndroid12DeviceIsDeniedWithoutBroadManufacturerBlock() {
        assertTrue(BuiltInAudioOffloadDenylist.matches("Xiaomi", "Redmi", "22081212C", 31))
        assertFalse(BuiltInAudioOffloadDenylist.matches("Xiaomi", "Redmi", "22081212C", 36))
        assertFalse(BuiltInAudioOffloadDenylist.matches("Xiaomi", "Redmi", "other", 31))
    }
}
