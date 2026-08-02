package com.mica.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioStateTest {
    @Test
    fun unsupportedStateExplainsPlatformRequirement() {
        val state = SpatialAudioState.unsupported()

        assertEquals("Unsupported (requires Android 12L / API 32+)", state.summary())
        assertTrue(state.toLogMessage().contains("apiSupported=false"))
        assertTrue(state.toLogMessage().contains("canBeSpatialized5_1Pcm=unknown"))
    }

    @Test
    fun supportedStateSummarizesProbeWithoutClaimingStereoSupport() {
        val state = SpatialAudioState(
            apiSupported = true,
            supported = true,
            available = true,
            enabled = true,
            canBeSpatialized = true,
            headTrackerAvailable = false,
        )

        assertEquals(
            "Enabled; output=available; 5.1 PCM=spatializable; head tracking=unavailable",
            state.summary(),
        )
        assertTrue(state.toLogMessage().contains("canBeSpatialized5_1Pcm=true"))
    }
}
