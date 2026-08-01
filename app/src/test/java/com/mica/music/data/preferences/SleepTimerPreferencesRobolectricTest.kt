package com.mica.music.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SleepTimerPreferencesRobolectricTest {
    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun defaultsToThirtyMinutesAndPersistsLastSelection() {
        assertEquals(
            SleepTimerPreferences.DEFAULT_DURATION_MINUTES,
            SleepTimerPreferences.lastDurationMinutes(context),
        )

        SleepTimerPreferences.setLastDurationMinutes(context, 60)

        assertEquals(60, SleepTimerPreferences.lastDurationMinutes(context))
    }
}
