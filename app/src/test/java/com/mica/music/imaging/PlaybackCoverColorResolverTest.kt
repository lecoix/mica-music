package com.mica.music.imaging

import com.mica.music.data.SongSource
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoverColorResolverTest {
    @Test
    fun samplingDisabledIgnoresProcessCachedColor() {
        val cached = 0xFF123456.toInt()
        val persisted = 0xFFABCDEF.toInt()

        assertEquals(
            persisted,
            PlaybackCoverColorResolver.resolveArgb(persisted, cached, shouldSample = false),
        )
    }

    @Test
    fun samplingEnabledCanUseProcessCachedColor() {
        val cached = 0xFF123456.toInt()
        val persisted = CoverColorExtractor.FALLBACK_ARGB

        assertEquals(
            cached,
            PlaybackCoverColorResolver.resolveArgb(persisted, cached, shouldSample = true),
        )
    }

    @Test
    fun cachedSampleStillRepairsMissingLibraryColor() {
        val librarySong = SongFixtures.song().copy(
            albumArtUri = "file:///cover.jpg",
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
            source = SongSource.LIBRARY,
        )

        assertTrue(PlaybackCoverColorResolver.shouldPersistSample(librarySong, shouldSample = true))
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

        assertFalse(PlaybackCoverColorResolver.shouldPersistSample(remoteSong, shouldSample = true))
        assertFalse(PlaybackCoverColorResolver.shouldPersistSample(validLibrarySong, shouldSample = true))
    }
}
