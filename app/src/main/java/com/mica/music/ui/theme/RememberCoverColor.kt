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
import com.mica.music.data.scanner.CoverColorExtractor
import kotlinx.coroutines.delay

private const val CoverColorSampleDelayMs = 180L
private const val CoverColorCacheSize = 256
private val sampledCoverColorCache = LruCache<String, Int>(CoverColorCacheSize)

/**
 * 播放页用：封面图采样主色，并做舒适度柔化。
 */
@Composable
fun rememberCoverColor(
    song: Song,
    sampleArtwork: Boolean = true,
): Color {
    val isDark = MicaTheme.colors.isDark
    var color by remember(song.id, song.coverColorArgb, sampleArtwork, isDark) {
        mutableStateOf(PlayerBackgroundBlend.comfortColor(song.coverColor, isDark))
    }
    val context = LocalContext.current
    if (sampleArtwork) {
        LaunchedEffect(song.id, song.albumArtUri, isDark) {
            val cacheKey = song.albumArtUri ?: song.id
            sampledCoverColorCache.get(cacheKey)?.let { cached ->
                color = PlayerBackgroundBlend.comfortColor(Color(cached), isDark)
                return@LaunchedEffect
            }
            delay(CoverColorSampleDelayMs)
            val sampled = CoverColorExtractor.fromUriString(context, song.albumArtUri)
            if (sampled != null) {
                sampledCoverColorCache.put(cacheKey, sampled)
                color = PlayerBackgroundBlend.comfortColor(Color(sampled), isDark)
            }
        }
    }
    return color
}
