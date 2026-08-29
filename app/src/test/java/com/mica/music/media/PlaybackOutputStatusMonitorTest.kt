package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOutputStatusMonitorTest {
    @Test
    fun supersededPublisherCannotOverwriteNewCoordinatorState() {
        val oldPublisher = PlaybackOutputStatusMonitor.openPublisher()
        oldPublisher.publish(PlaybackOutputAvailability.SWITCHING, pendingPlayIntent = true)

        val newPublisher = PlaybackOutputStatusMonitor.openPublisher()
        newPublisher.publish(PlaybackOutputAvailability.STABLE, pendingPlayIntent = false)
        val accepted = PlaybackOutputStatusMonitor.status.value

        oldPublisher.publish(PlaybackOutputAvailability.FAILED, pendingPlayIntent = true, failureMessage = "stale")

        assertEquals(accepted, PlaybackOutputStatusMonitor.status.value)
        newPublisher.close()
    }
}
