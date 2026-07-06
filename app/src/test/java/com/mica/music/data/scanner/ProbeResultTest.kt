package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultTest {

    @Test
    fun technicalValueReturnsPayloadOnSuccess() {
        val payload = AudioTechnicalProbe.Result(containerName = "FLAC", bitsPerSample = 24)
        val value = ProbeResult.Ok(payload).technicalValue()
        assertEquals("FLAC", value.containerName)
        assertEquals(24, value.bitsPerSample)
    }

    @Test
    fun technicalValueDegradesToEmptyOnFailure() {
        val value = ProbeResult.Failed("technical").technicalValue()
        assertNull(value.containerName)
        assertNull(value.bitsPerSample)
    }

    @Test
    fun scanProbeStatsTracksTechnicalFailuresOnly() {
        val stats = ScanProbeStats(technicalFailed = 2)
        assertTrue(stats.hasTechnicalFailures())
        assertFalse(ScanProbeStats().hasTechnicalFailures())
        assertEquals(2, stats.technicalFailed)
    }
}
