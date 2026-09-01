package com.mica.music.ui.theme

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.scanner.CoverColorExtractor
import com.mica.music.data.scanner.CoverColorPersistence
import com.mica.music.data.scanner.shouldSampleCoverColorAtPlayback
import kotlinx.coroutines.ensureActive

private const val CoverColorCacheSize = 256
private val sampledCoverColorCache = LruCache<String, Int>(CoverColorCacheSize)

/**
 * Uses the artwork color extracted and persisted during library scanning.
 *
 * Re-reading artwork on every track switch competes with cover/background
 * decode. Sampling here is only for missing/invalid persisted colors.
 */
@Composable
fun rememberCoverColor(
    song: Song,
    sampleArtwork: Boolean = true,
): Color {
    val isDark = MicaTheme.colors.isDark
    val albumArtUri = song.albumArtUri
    val cachedSample = albumArtUri?.let(sampledCoverColorCache::get)
    var sampledArgb by remember(song.id, albumArtUri) { mutableStateOf(cachedSample) }
    val context = LocalContext.current
    val shouldSample = shouldSampleCoverColorAtPlayback(song, sampleArtwork)
    LaunchedEffect(song.id, albumArtUri, shouldSample) {
        if (!shouldSample || albumArtUri.isNullOrBlank()) return@LaunchedEffect
        sampledCoverColorCache.get(albumArtUri)?.let { cached ->
            sampledArgb = cached
            return@LaunchedEffect
        }
        val extracted = CoverColorExtractor.fromUriString(context, albumArtUri) ?: return@LaunchedEffect
        sampledCoverColorCache.put(albumArtUri, extracted)
        if (song.source == SongSource.LIBRARY) {
            CoverColorPersistence.persistLibraryColor(song.id, albumArtUri, extracted)
        }
        ensureActive()
        sampledArgb = extracted
    }
    val argb = sampledArgb ?: song.coverColorArgb
    return remember(argb, isDark) {
        PlayerBackgroundBlend.comfortColor(
            Color(argb),
            isDark,
        )
    }
}
