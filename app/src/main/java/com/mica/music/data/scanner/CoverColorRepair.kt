package com.mica.music.data.scanner

import com.mica.music.data.Song
import com.mica.music.data.SongSource

internal fun needsPersistedCoverColorRepair(
    coverColorArgb: Int,
    albumArtUri: String?,
): Boolean =
    !albumArtUri.isNullOrBlank() &&
        (coverColorArgb == 0 || coverColorArgb == CoverColorExtractor.FALLBACK_ARGB)

internal fun Song.needsPersistedCoverColorRepair(): Boolean =
    needsPersistedCoverColorRepair(coverColorArgb, albumArtUri)

internal fun shouldSampleCoverColorAtPlayback(
    song: Song,
    sampleArtwork: Boolean,
): Boolean =
    song.needsPersistedCoverColorRepair() &&
        (sampleArtwork || song.source == SongSource.LIBRARY)

internal fun canPersistCoverColor(
    current: Song?,
    songId: String,
    albumArtUri: String?,
    argb: Int,
): Boolean {
    if (current == null || current.id != songId) return false
    if (current.albumArtUri != albumArtUri) return false
    if (current.coverColorArgb == argb) return true
    return current.needsPersistedCoverColorRepair()
}
