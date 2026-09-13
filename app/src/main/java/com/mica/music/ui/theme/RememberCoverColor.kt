package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.Song
import com.mica.music.imaging.PlaybackCoverColorResolver

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
    val context = LocalContext.current
    var resolvedArgb by remember(song.id, song.albumArtUri, song.coverColorArgb, sampleArtwork) {
        mutableIntStateOf(PlaybackCoverColorResolver.initialArgb(song, sampleArtwork))
    }
    LaunchedEffect(song.id, song.albumArtUri, song.coverColorArgb, sampleArtwork) {
        resolvedArgb = PlaybackCoverColorResolver.resolve(context, song, sampleArtwork)
    }
    return remember(resolvedArgb, isDark) {
        PlayerBackgroundBlend.comfortColor(
            Color(resolvedArgb),
            isDark,
        )
    }
}
