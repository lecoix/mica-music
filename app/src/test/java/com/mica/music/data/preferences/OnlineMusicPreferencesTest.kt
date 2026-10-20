package com.mica.music.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineMusicPreferencesTest {
    @Test fun `fixed quality never silently downgrades`() {
        assertEquals("128k", OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.K128, listOf("128k", "320k")))
        assertNull(OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.K320, listOf("128k")))
        assertNull(OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.LOSSLESS, listOf("128k", "320k")))
    }

    @Test fun `lossless prefers standard flac and accepts 24 bit fallback`() {
        assertEquals("flac", OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.LOSSLESS, listOf("flac24bit", "flac")))
        assertEquals("flac24bit", OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.LOSSLESS, listOf("flac24bit")))
        assertNull(OnlineMusicPreferences.resolveDefaultQuality(OnlineMusicDefaultQuality.ASK, listOf("128k", "flac")))
    }
}
