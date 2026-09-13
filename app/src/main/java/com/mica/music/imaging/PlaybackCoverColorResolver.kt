package com.mica.music.imaging

import android.content.Context
import android.util.LruCache
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.data.scanner.CoverColorPersistence
import com.mica.music.data.scanner.needsPersistedCoverColorRepair
import com.mica.music.data.scanner.shouldSampleCoverColorAtPlayback

/**
 * Owns playback-time artwork colour repair.
 *
 * Presentation code may request a colour, but it does not decide when artwork must be sampled or
 * when a sampled value is safe to persist back into the local library.
 */
internal object PlaybackCoverColorResolver {
    private const val CACHE_SIZE = 256
    private val sampledColorCache = LruCache<String, Int>(CACHE_SIZE)

    fun initialArgb(song: Song, sampleArtwork: Boolean): Int {
        val shouldSample = shouldSampleCoverColorAtPlayback(song, sampleArtwork)
        val cached = song.albumArtUri?.let(sampledColorCache::get)
        return resolveArgb(song.coverColorArgb, cached, shouldSample)
    }

    suspend fun resolve(
        context: Context,
        song: Song,
        sampleArtwork: Boolean,
    ): Int {
        val shouldSample = shouldSampleCoverColorAtPlayback(song, sampleArtwork)
        val albumArtUri = song.albumArtUri
        if (!shouldSample || albumArtUri.isNullOrBlank()) return song.coverColorArgb

        sampledColorCache.get(albumArtUri)?.let { cached ->
            persistIfNeeded(song, albumArtUri, cached, shouldSample)
            return cached
        }

        val extracted = CoverColorExtractor.fromUriString(context.applicationContext, albumArtUri)
            ?: return song.coverColorArgb
        sampledColorCache.put(albumArtUri, extracted)
        persistIfNeeded(song, albumArtUri, extracted, shouldSample)
        return extracted
    }

    internal fun resolveArgb(
        persistedArgb: Int,
        sampledArgb: Int?,
        shouldSample: Boolean,
    ): Int = if (shouldSample) sampledArgb ?: persistedArgb else persistedArgb

    internal fun shouldPersistSample(song: Song, shouldSample: Boolean): Boolean =
        shouldSample && song.source == SongSource.LIBRARY && song.needsPersistedCoverColorRepair()

    private fun persistIfNeeded(
        song: Song,
        albumArtUri: String,
        argb: Int,
        shouldSample: Boolean,
    ) {
        if (!shouldPersistSample(song, shouldSample)) return
        CoverColorPersistence.persistLibraryColor(song.id, albumArtUri, argb)
    }
}
