package com.mica.music.media.usbprototype

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPlaybackStartGateTest {
    @Test
    fun nativeRingStaysSilentUntilTwentyMillisecondsAreQueued() {
        val minimumFrames = usbStartPrefillFrames(sampleRate = 48_000)
        assertTrue(minimumFrames == 960L)
        assertFalse(
            shouldConsumeUsbSource(
                requestedPlaying = true,
                volume = 1f,
                bufferedFrames = 0,
                minimumBufferedFrames = minimumFrames,
            ),
        )
        assertFalse(
            shouldConsumeUsbSource(
                requestedPlaying = true,
                volume = 1f,
                bufferedFrames = minimumFrames - 1,
                minimumBufferedFrames = minimumFrames,
            ),
        )
        assertTrue(
            shouldConsumeUsbSource(
                requestedPlaying = true,
                volume = 1f,
                bufferedFrames = minimumFrames,
                minimumBufferedFrames = minimumFrames,
            ),
        )
    }

    @Test
    fun pauseAndNonUnityVolumeKeepSourceConsumptionStopped() {
        assertFalse(
            shouldConsumeUsbSource(
                requestedPlaying = false,
                volume = 1f,
                bufferedFrames = 4_096,
                minimumBufferedFrames = 960,
            ),
        )
        assertFalse(
            shouldConsumeUsbSource(
                requestedPlaying = true,
                volume = 0f,
                bufferedFrames = 4_096,
                minimumBufferedFrames = 960,
            ),
        )
    }
}
