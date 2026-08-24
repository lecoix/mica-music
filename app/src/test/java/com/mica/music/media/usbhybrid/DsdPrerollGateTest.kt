package com.mica.music.media.usbhybrid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsdPrerollGateTest {
    @Test
    fun readinessStagesExactlyOneBufferAndStartedArmsIt() {
        val gate = DsdPrerollGate()
        val first = byteArrayOf(1, 2)

        assertTrue(gate.stage(first))
        assertFalse(gate.stage(byteArrayOf(3, 4)))
        assertArrayEquals(first, gate.arm())
        assertTrue(gate.isStarted())
        assertFalse(gate.hasStaged())
        assertNull(gate.arm())
    }

    @Test
    fun seekOrDisableClearsPreparedPayload() {
        val gate = DsdPrerollGate()
        gate.stage(byteArrayOf(1))
        gate.clear()

        assertFalse(gate.hasStaged())
        assertFalse(gate.isStarted())
    }

    @Test
    fun seekDuringPlaybackClearsPayloadButKeepsWriterArmed() {
        val gate = DsdPrerollGate()
        gate.stage(byteArrayOf(1))
        gate.reset(started = true)

        assertFalse(gate.hasStaged())
        assertTrue(gate.isStarted())
    }

    @Test
    fun pausedSeekStagesNewPositionUntilRendererActuallyStarts() {
        val gate = DsdPrerollGate()
        val newPosition = byteArrayOf(7, 8)

        gate.reset(started = false)
        assertFalse(gate.isStarted())
        assertTrue(gate.stage(newPosition))
        assertTrue(gate.hasStaged())
        assertFalse(gate.stage(byteArrayOf(9, 10)))

        assertArrayEquals(newPosition, gate.arm())
        assertTrue(gate.isStarted())
        assertFalse(gate.hasStaged())
    }
}
