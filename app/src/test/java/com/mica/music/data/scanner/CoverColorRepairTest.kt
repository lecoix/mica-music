package com.mica.music.data.scanner

import com.mica.music.data.SongSource
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverColorRepairTest {

    @Test
    fun artworkWithFallbackOrZeroNeedsRepair() {
        assertTrue(
            needsPersistedCoverColorRepair(
                CoverColorExtractor.FALLBACK_ARGB,
                "file:///cover.jpg",
            ),
        )
        assertTrue(needsPersistedCoverColorRepair(0, "file:///cover.jpg"))
    }

    @Test
    fun missingArtworkKeepsFallbackAsPlaceholder() {
        assertFalse(
            needsPersistedCoverColorRepair(CoverColorExtractor.FALLBACK_ARGB, null),
        )
        assertFalse(needsPersistedCoverColorRepair(CoverColorExtractor.FALLBACK_ARGB, ""))
        assertFalse(needsPersistedCoverColorRepair(0, null))
    }

    @Test
    fun extractedColorDoesNotNeedRepair() {
        assertFalse(needsPersistedCoverColorRepair(0xFFB13B66.toInt(), "file:///cover.jpg"))
    }

    @Test
    fun localMissingColorSamplesEvenWhenThemeBackgroundSkipsArtworkTint() {
        val local = SongFixtures.song().copy(
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
            source = SongSource.LIBRARY,
        )
        assertTrue(shouldSampleCoverColorAtPlayback(local, sampleArtwork = false))
    }

    @Test
    fun remoteMissingColorSamplesOnlyWhenPlaybackUsesArtworkTint() {
        val remote = SongFixtures.song().copy(
            coverColorArgb = 0,
            source = SongSource.REMOTE,
        )
        assertFalse(shouldSampleCoverColorAtPlayback(remote, sampleArtwork = false))
        assertTrue(shouldSampleCoverColorAtPlayback(remote, sampleArtwork = true))
    }

    @Test
    fun staleExtractCannotPersistAfterLibraryRowAlreadyHasUsableColor() {
        val current = SongFixtures.song().copy(
            albumArtUri = "file:///a.jpg",
            coverColorArgb = 0xFFAABBCC.toInt(),
        )
        assertFalse(
            canPersistCoverColor(
                current = current,
                songId = current.id,
                albumArtUri = current.albumArtUri,
                argb = 0xFF111111.toInt(),
            ),
        )
    }

    @Test
    fun extractCanPersistWhileLibraryRowStillMissingCoverColor() {
        val current = SongFixtures.song().copy(
            albumArtUri = "file:///a.jpg",
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
        )
        assertTrue(
            canPersistCoverColor(
                current = current,
                songId = current.id,
                albumArtUri = current.albumArtUri,
                argb = 0xFF111111.toInt(),
            ),
        )
    }

    @Test
    fun extractCannotPersistAfterArtworkUriChangedOrSongRemoved() {
        val current = SongFixtures.song().copy(
            albumArtUri = "file:///new.jpg",
            coverColorArgb = CoverColorExtractor.FALLBACK_ARGB,
        )
        assertFalse(
            canPersistCoverColor(
                current = current,
                songId = current.id,
                albumArtUri = "file:///old.jpg",
                argb = 0xFF111111.toInt(),
            ),
        )
        assertFalse(
            canPersistCoverColor(
                current = null,
                songId = current.id,
                albumArtUri = "file:///a.jpg",
                argb = 0xFF111111.toInt(),
            ),
        )
    }
}
