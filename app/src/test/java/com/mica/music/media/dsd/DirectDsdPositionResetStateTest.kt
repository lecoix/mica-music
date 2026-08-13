package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDsdPositionResetStateTest {
    @Test
    fun initialResetWithoutPumpDoesNotRequestFreshTransportOrPlayingArm() {
        val state = DirectDsdPositionResetState()

        state.onPositionReset(positionUs = 1_000_000L, hadPump = false, isPlaying = true)

        assertNull(state.consumeFreshPumpPositionUs())
        assertNull(state.postResetArmPositionUsIfReady(startupReady = true, playbackArmed = false))
    }

    @Test
    fun playingResetRequestsFreshPumpAndConsumesPostResetArmExactlyOnce() {
        val state = DirectDsdPositionResetState()

        state.onPositionReset(positionUs = 45_000_000L, hadPump = true, isPlaying = true)

        assertEquals(45_000_000L, state.consumeFreshPumpPositionUs())
        assertNull(state.consumeFreshPumpPositionUs())
        assertNull(state.postResetArmPositionUsIfReady(startupReady = false, playbackArmed = false))
        assertEquals(
            45_000_000L,
            state.postResetArmPositionUsIfReady(startupReady = true, playbackArmed = false),
        )
        state.markPostResetArmed(45_000_000L)
        assertNull(state.postResetArmPositionUsIfReady(startupReady = true, playbackArmed = false))
    }

    @Test
    fun pausedResetRequestsFreshPumpButNeverAutoArms() {
        val state = DirectDsdPositionResetState()

        state.onPositionReset(positionUs = 90_000_000L, hadPump = true, isPlaying = false)

        assertEquals(90_000_000L, state.consumeFreshPumpPositionUs())
        assertNull(state.postResetArmPositionUsIfReady(startupReady = true, playbackArmed = false))
    }

    @Test
    fun laterResetReplacesAnyPendingResetDecision() {
        val state = DirectDsdPositionResetState()
        state.onPositionReset(positionUs = 10L, hadPump = true, isPlaying = true)

        state.onPositionReset(positionUs = 20L, hadPump = true, isPlaying = false)

        assertEquals(20L, state.consumeFreshPumpPositionUs())
        assertNull(state.postResetArmPositionUsIfReady(startupReady = true, playbackArmed = false))
    }
}
