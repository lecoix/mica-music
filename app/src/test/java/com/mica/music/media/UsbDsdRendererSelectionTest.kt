package com.mica.music.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDsdRendererSelectionTest {
    @Test
    fun explicitUsbDsdModesCannotInstallCompetingDecodedDsdRenderer() {
        assertFalse(shouldInstallDecodedDsdRenderer(PlaybackOutputMode.UsbDop))
        assertFalse(shouldInstallDecodedDsdRenderer(PlaybackOutputMode.UsbNativeDsdExperimental))
    }

    @Test
    fun sharedAndPcmModesRetainDecodedDsdRenderer() {
        assertTrue(shouldInstallDecodedDsdRenderer(PlaybackOutputMode.SharedPcm))
        assertTrue(shouldInstallDecodedDsdRenderer(PlaybackOutputMode.UsbDirectPcm))
    }
}
