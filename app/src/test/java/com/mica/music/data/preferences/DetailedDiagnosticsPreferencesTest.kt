package com.mica.music.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.util.DiagnosticDetailConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailedDiagnosticsPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MicaSettingsStore.prefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        MicaSettingsStore.prefs(context).edit().clear().commit()
    }

    @Test
    fun defaultsAreFullyOff() {
        val state = DetailedDiagnosticsPreferences.state(context)
        assertFalse(state.enabled)
        assertFalse(state.libraryScan)
        assertFalse(state.playbackMedia)
        assertFalse(state.audioPipeline)
        assertFalse(state.usbDevice)
        assertFalse(state.uiRendering)
    }

    @Test
    fun stateRoundTripsAcrossPreferences() {
        val expected = DiagnosticDetailConfig(
            enabled = true,
            libraryScan = true,
            playbackMedia = false,
            audioPipeline = true,
            usbDevice = true,
            uiRendering = false,
        )
        DetailedDiagnosticsPreferences.setState(context, expected)
        assertEquals(expected, DetailedDiagnosticsPreferences.state(context))
    }
}
