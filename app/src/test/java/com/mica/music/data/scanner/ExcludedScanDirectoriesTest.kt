package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedScanDirectoriesTest {
    @Test
    fun normalizeAllTrimsSeparatorsAndDeduplicatesCaseInsensitively() {
        assertEquals(
            listOf("Music/Live", "Podcasts"),
            ExcludedScanDirectories.normalizeAll(
                listOf("/Music/Live/", "Music\\Live", "Podcasts"),
            ),
        )
    }

    @Test
    fun isExcludedMatchesDirectoryBoundaries() {
        val excluded = listOf("Music/Live")

        assertTrue(ExcludedScanDirectories.isExcluded("Music/Live", excluded))
        assertTrue(ExcludedScanDirectories.isExcluded("Music/Live/2024", excluded))
        assertFalse(ExcludedScanDirectories.isExcluded("Music/Liverpool", excluded))
        assertFalse(ExcludedScanDirectories.isExcluded("", excluded))
    }
}
