package com.mica.music.media.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDsdTrackTransitionCoordinatorTest {
    @Test
    fun playingDopToPcmRequiresDirectReleaseBeforePcmAcceptance() {
        val events = mutableListOf<String>()
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        coordinator.beforeDirectAccept(isPlaying = true)

        assertFails { coordinator.beforePcmAccept() }
        coordinator.onDirectReleased(wasPaused = false)
        coordinator.beforePcmAccept()

        assertEquals(DirectDsdTrackTransportFamily.PCM, coordinator.snapshot().activeFamily)
        assertTrue(events.indexOfFirst { it.startsWith("trackTransition=dop-released") } <
            events.indexOfLast { it.startsWith("trackTransition=pcm-accept-allowed") })
    }

    @Test
    fun playingPcmToDopRequiresPcmReleaseBeforeDirectAcceptance() {
        val events = mutableListOf<String>()
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        coordinator.beforePcmAccept()

        assertFails { coordinator.beforeDirectAccept(isPlaying = true) }
        coordinator.onPcmReleased()
        coordinator.beforeDirectAccept(isPlaying = true)

        assertEquals(DirectDsdTrackTransportFamily.DOP, coordinator.snapshot().activeFamily)
        assertTrue(events.indexOfFirst { it == "trackTransition=PCM_SOURCE_INTAKE_CLOSED" } <
            events.indexOfFirst { it.startsWith("trackTransition=PCM_SINK_DECODER_STATE_RELEASED") })
        assertTrue(events.indexOfFirst { it.startsWith("trackTransition=PCM_SINK_DECODER_STATE_RELEASED") } <
            events.indexOfLast { it.startsWith("trackTransition=dop-accept-allowed") })
    }

    @Test
    fun pausedCrossFamilyTransitionsFailClosedBeforeDestinationAcceptance() {
        val dopToPcm = DirectDsdTrackTransitionCoordinator {}
        dopToPcm.beforeDirectAccept(isPlaying = false)
        dopToPcm.onDirectReleased(wasPaused = true)
        assertFails { dopToPcm.beforePcmAccept(isPlaying = false) }
        assertEquals(DirectDsdTrackTransportFamily.NONE, dopToPcm.snapshot().activeFamily)

        val pcmToDop = DirectDsdTrackTransitionCoordinator {}
        pcmToDop.beforePcmAccept()
        pcmToDop.onPcmPlayState(paused = true)
        pcmToDop.onPcmReleased()
        assertFails { pcmToDop.beforeDirectAccept(isPlaying = false) }
        assertEquals(DirectDsdTrackTransportFamily.NONE, pcmToDop.snapshot().activeFamily)

        pcmToDop.beforeDirectAccept(isPlaying = true)
        assertEquals(DirectDsdTrackTransportFamily.DOP, pcmToDop.snapshot().activeFamily)
    }

    @Test
    fun pausedActiveFamilyDefersDestinationBeforeSourceReleaseCallback() {
        val dop = DirectDsdTrackTransitionCoordinator {}
        dop.beforeDirectAccept(isPlaying = true)
        dop.onDirectPlayState(paused = true)
        assertTrue(dop.shouldDeferPcmUntilResume())

        val pcm = DirectDsdTrackTransitionCoordinator {}
        pcm.beforePcmAccept(isPlaying = true)
        pcm.onPcmPlayState(paused = true)
        assertTrue(pcm.shouldDeferDirectUntilResume())
    }

    @Test
    fun platformFlushCompletesPcmReleaseOnlyAtDirectHandoffAndActivityCancelsIt() {
        val events = mutableListOf<String>()
        val coordinator = DirectDsdTrackTransitionCoordinator(events::add)
        coordinator.beforePcmAccept(isPlaying = true)
        coordinator.onPcmFlushPotentialRelease()
        coordinator.completePcmReleaseForDirectHandoff()
        assertEquals(DirectDsdTrackTransportFamily.NONE, coordinator.snapshot().activeFamily)
        assertTrue(events.contains("trackTransition=PCM_SOURCE_INTAKE_CLOSED"))
        assertTrue(events.any { it.startsWith("trackTransition=PCM_SINK_DECODER_STATE_RELEASED") })

        val seekLike = DirectDsdTrackTransitionCoordinator {}
        seekLike.beforePcmAccept(isPlaying = true)
        seekLike.onPcmFlushPotentialRelease()
        seekLike.onPcmActivity()
        seekLike.completePcmReleaseForDirectHandoff()
        assertEquals(DirectDsdTrackTransportFamily.PCM, seekLike.snapshot().activeFamily)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }
}
