package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryAnalyzerTest {
    @Test
    fun qualityBadgeShowsHqSqHrAndHidesOther() {
        assertEquals(LibraryAnalyzer.TIER_HR, LibraryAnalyzer.qualityBadgeLabel(hiResFlac))
        assertEquals(LibraryAnalyzer.TIER_HR, LibraryAnalyzer.qualityBadgeLabel(dsd))
        assertEquals(LibraryAnalyzer.TIER_SQ, LibraryAnalyzer.qualityBadgeLabel(cdFlac))
        assertEquals(LibraryAnalyzer.TIER_HQ, LibraryAnalyzer.qualityBadgeLabel(mp3Hq))
        assertNull(LibraryAnalyzer.qualityBadgeLabel(mp3Low))
    }

    private val hiResFlac = TrackMetadata("FLAC", 96_000, 24, 2_000, 2, "audio/flac")
    private val cdFlac = TrackMetadata("FLAC", 44_100, 16, 900, 2, "audio/flac")
    private val dsd = TrackMetadata("DSD", 2_822_400, 1, 5_644, 2, "audio/dsd")
    private val mp3Hq = TrackMetadata("MP3", 44_100, 16, 320, 2, "audio/mpeg")
    private val mp3Low = TrackMetadata("MP3", 44_100, 16, 128, 2, "audio/mpeg")
}
