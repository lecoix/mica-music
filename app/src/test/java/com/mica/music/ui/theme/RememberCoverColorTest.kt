package com.mica.music.ui.theme

import com.mica.music.data.SongSource
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberCoverColorTest {
    @Test
    fun samplingDisabledIgnoresProcessCachedColor() {
        val cached = 0xFF123456.toInt()
        val persisted = 0xFFABCDEF.toInt()

        assertNull(cachedPlaybackCoverColorSample(cached, shouldSample = false))
        assertEquals(
            persisted,
            resolvePlaybackCoverColorArgb(persisted, cached, shouldSample = false),
        )
    }

    @Test
    fun samplingEnabledCanUseProcessCachedColor() {
        val cached = 0xFF123456.toInt()
        val persisted = CoverColorExtractor.FALLBACK_ARGB

        assertEquals(cached, cachedPlaybackCoverColorSample(cached, shouldSample = true))
        assertEquals(
            cached,
            resolvePlaybackCoverColorArgb(persisted, cached, shouldSample = true),
        )
    }

    @Test
    fun cachedSampleStillRepairsMissingLibraryColor() {
        val librarySong = SongFixtures.song().copy(
            albumArtUri = "file:///cover.jpg",
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
            source = SongSource.LIBRARY,
        )

        assertTrue(shouldPersistPlaybackCoverColorSample(librarySong, shouldSample = true))
    }

    @Test
    fun cachedSampleDoesNotPersistRemoteOrAlreadyValidLibraryColor() {
        val remoteSong = SongFixtures.song().copy(
            albumArtUri = "file:///cover.jpg",
            coverColorArgb = 0,
            source = SongSource.REMOTE,
        )
        val validLibrarySong = SongFixtures.song().copy(
            albumArtUri = "file:///cover.jpg",
            coverColorArgb = 0xFF778899.toInt(),
            source = SongSource.LIBRARY,
        )

        assertFalse(shouldPersistPlaybackCoverColorSample(remoteSong, shouldSample = true))
        assertFalse(shouldPersistPlaybackCoverColorSample(validLibrarySong, shouldSample = true))
    }
}
