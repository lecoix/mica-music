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

    @Test
    fun normalizedExclusionMatcherPreservesBoundarySemantics() {
        val normalized = ExcludedScanDirectories.normalizeAll(
            listOf("/Music/Live/", "music\\live", "Podcasts"),
        )

        assertTrue(ExcludedScanDirectories.isExcludedNormalized("Music/Live", normalized))
        assertTrue(ExcludedScanDirectories.isExcludedNormalized("music/live/2024", normalized))
        assertTrue(ExcludedScanDirectories.isExcludedNormalized("Podcasts/Episode", normalized))
        assertFalse(ExcludedScanDirectories.isExcludedNormalized("Music/Liverpool", normalized))
        assertFalse(ExcludedScanDirectories.isExcludedNormalized("", normalized))
    }

    @Test
    fun safArtifactFilterExcludesAndroidTrashFilesWithoutBlanketHiddenFiltering() {
        assertTrue(
            FolderScanner.shouldIgnoreSafArtifact(
                ".trashed-1791550701-song.wav",
                isDirectory = false,
            ),
        )
        assertTrue(
            FolderScanner.shouldIgnoreSafArtifact(
                ".trashed-anything.flac",
                isDirectory = false,
            ),
        )
        assertFalse(
            FolderScanner.shouldIgnoreSafArtifact(
                "trashed-song.wav",
                isDirectory = false,
            ),
        )
        assertFalse(
            FolderScanner.shouldIgnoreSafArtifact(
                ".hidden-song.wav",
                isDirectory = false,
            ),
        )
    }

    @Test
    fun safArtifactFilterSkipsOnlyDedicatedMicaRecycleDirectory() {
        assertTrue(
            FolderScanner.shouldIgnoreSafArtifact(
                ".MicaRecycle",
                isDirectory = true,
            ),
        )
        assertFalse(
            FolderScanner.shouldIgnoreSafArtifact(
                ".MicaRecycle",
                isDirectory = false,
            ),
        )
        assertFalse(
            FolderScanner.shouldIgnoreSafArtifact(
                ".hiddenMusic",
                isDirectory = true,
            ),
        )
    }
}
