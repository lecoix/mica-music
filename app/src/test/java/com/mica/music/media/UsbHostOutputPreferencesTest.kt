package com.mica.music.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbHostOutputPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun reset() {
        UsbHostOutputPreferences.setEnabled(context, false)
    }

    @Test
    fun usbIntentDefaultsToSharedPcm() {
        assertEquals(false, UsbHostOutputPreferences.isEnabled(context))
        assertEquals(
            PlaybackOutputMode.SharedPcm,
            UsbHostOutputPreferences.selectedPath(context).outputMode,
        )
    }

    @Test
    fun legacyDebugPrototypeIntentIsNotPromotedIntoReleasePreference() {
        context.getSharedPreferences("usb_host_prototype", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("sk02_media3_enabled", true)
            .commit()

        assertEquals(false, UsbHostOutputPreferences.isEnabled(context))
        assertEquals(
            PlaybackOutputMode.SharedPcm,
            UsbHostOutputPreferences.selectedPath(context).outputMode,
        )
    }

    @Test
    fun committedUsbIntentIsRestoredByStartupSelection() {
        UsbHostOutputPreferences.setEnabled(context, true)

        assertEquals(
            PlaybackOutputMode.UsbDirectPcm,
            UsbHostOutputPreferences.selectedPath(context).outputMode,
        )
    }

    @Test
    fun latestCommittedIntentWinsBeforeProcessRestart() {
        UsbHostOutputPreferences.setEnabled(context, true)
        UsbHostOutputPreferences.setEnabled(context, false)

        assertEquals(
            PlaybackOutputMode.SharedPcm,
            UsbHostOutputPreferences.selectedPath(context).outputMode,
        )
    }
}
