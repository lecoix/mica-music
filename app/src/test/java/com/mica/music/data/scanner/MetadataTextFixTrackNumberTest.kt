package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataTextFixTrackNumberTest {
    @Test
    fun parseTrackNumberReadsPlainValue() {
        assertEquals(5, MetadataTextFix.parseTrackNumber("5"))
        assertEquals(5, MetadataTextFix.parseTrackNumber("05"))
    }

    @Test
    fun parseTrackNumberReadsTrackTotalFormat() {
        assertEquals(3, MetadataTextFix.parseTrackNumber("3/12"))
        assertEquals(3, MetadataTextFix.parseTrackNumber(" 3 / 12 "))
    }

    @Test
    fun parseTrackNumberReturnsZeroForMissingOrInvalid() {
        assertEquals(0, MetadataTextFix.parseTrackNumber(null))
        assertEquals(0, MetadataTextFix.parseTrackNumber(""))
        assertEquals(0, MetadataTextFix.parseTrackNumber("abc"))
    }
}
