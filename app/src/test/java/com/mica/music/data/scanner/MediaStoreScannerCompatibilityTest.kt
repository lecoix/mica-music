package com.mica.music.data.scanner

import com.mica.music.data.DsdSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreScannerCompatibilityTest {

    @Test
    fun filesFallbackIncludesApeAlongsideDsdFormats() {
        assertTrue("ape" in MediaStoreScanner.FILE_EXTENSION_FALLBACKS)
        assertTrue(MediaStoreScanner.FILE_EXTENSION_FALLBACKS.containsAll(DsdSupport.extensions))
    }

    @Test
    fun mediaStoreDurationClauseKeepsUnknownDurationForAppProbe() {
        val clause = mediaStoreDurationClause(60_000L)

        assertTrue(clause.contains("duration IS NULL"))
        assertTrue(clause.contains("duration <= 0"))
        assertTrue(clause.contains("duration >= 60000"))
    }

    @Test
    fun zeroMinimumDoesNotAddDurationFilter() {
        assertEquals("", mediaStoreDurationClause(0L))
    }

    @Test
    fun postProbeDurationFilterKeepsUnknownButRejectsKnownShortTracks() {
        assertTrue(shouldKeepScannedDuration(durationSec = 0, minDurationMs = 60_000L))
        assertFalse(shouldKeepScannedDuration(durationSec = 59, minDurationMs = 60_000L))
        assertTrue(shouldKeepScannedDuration(durationSec = 60, minDurationMs = 60_000L))
    }
}
