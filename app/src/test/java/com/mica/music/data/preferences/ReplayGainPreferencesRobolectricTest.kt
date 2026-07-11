package com.mica.music.data.preferences

import com.mica.music.data.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ReplayGainPreferencesRobolectricTest {
    private val context = PreferencesTestFixtures.context()

    @Before
    fun clearPreferences() {
        PreferencesTestFixtures.clearMicaSettings(context)
    }

    @Test
    fun defaultsOffAndRoundTrips() {
        assertEquals(ReplayGainMode.OFF, ReplayGainPreferences.mode(context))

        ReplayGainPreferences.setMode(context, ReplayGainMode.ALBUM)

        assertEquals(ReplayGainMode.ALBUM, ReplayGainPreferences.mode(context))
    }
}
