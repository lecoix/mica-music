package com.mica.music.playback

import com.mica.music.data.PlaybackTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTuningCoordinatorTest {
    @Test
    fun unsupportedSongUsesDefaultWithoutDroppingRequestedTuning() {
        val coordinator = PlaybackTuningCoordinator()
        val requested = PlaybackTuning(speed = 2f)
        coordinator.request(requested)

        val dsdTarget = coordinator.targetForSync(tuningAvailable = false, force = true)
        coordinator.markApplyIssued(dsdTarget!!)
        coordinator.onPlaybackParametersChanged(
            reported = PlaybackTuning(),
            tuningAvailable = false,
        )

        assertEquals(requested, coordinator.requested)
        assertEquals(
            requested,
            coordinator.targetForSync(tuningAvailable = true, force = false),
        )
    }

    @Test
    fun pendingRequestWinsWhenControllerConnects() {
        val coordinator = PlaybackTuningCoordinator()
        val requested = PlaybackTuning(speed = 1.5f, pitchSemitones = 3f)
        coordinator.request(requested)

        assertEquals(
            requested,
            coordinator.onConnected(
                reported = PlaybackTuning(),
                tuningAvailable = true,
            ),
        )
        assertEquals(requested, coordinator.requested)
    }

    @Test
    fun matchingConnectionAdoptsPlayerWithoutWritingItBack() {
        val coordinator = PlaybackTuningCoordinator()
        val reported = PlaybackTuning(speed = 1.25f)

        assertNull(coordinator.onConnected(reported, tuningAvailable = true))
        assertEquals(reported, coordinator.requested)
    }

    @Test
    fun externalSupportedParameterChangeUpdatesRequestedTuning() {
        val coordinator = PlaybackTuningCoordinator()
        val reported = PlaybackTuning(speed = 0.75f, pitchSemitones = -2f)

        coordinator.onPlaybackParametersChanged(reported, tuningAvailable = true)

        assertEquals(reported, coordinator.requested)
    }
}
