package com.mica.music.ui.theme

import androidx.compose.material3.MaterialTheme
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

/**
 * 播放页用：封面图采样主色，并做舒适度柔化。
 */
@Composable
fun rememberCoverColor(song: Song): Color {
    val isDark = MicaTheme.colors.isDark
    val themeBg = MaterialTheme.colorScheme.background
    var color by remember(song.id, song.coverColorArgb) {
        mutableStateOf(PlayerBackgroundBlend.comfortColor(song.coverColor, isDark))
    }
    val context = LocalContext.current
    LaunchedEffect(song.id, song.albumArtUri, isDark) {
        val sampled = CoverColorExtractor.fromUriString(context, song.albumArtUri)
        if (sampled != null) {
            color = PlayerBackgroundBlend.comfortColor(Color(sampled), isDark)
        }
    }
    return color
}
