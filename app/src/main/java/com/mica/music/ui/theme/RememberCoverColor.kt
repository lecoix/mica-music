package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.mica.music.data.Song

/**
 * Uses the artwork color extracted and persisted during library scanning.
 *
 * Re-reading and decoding artwork here competes with the cover and background
 * requests during every track switch and causes visible frame stalls.
 */
@Composable
fun rememberCoverColor(
    song: Song,
    @Suppress("UNUSED_PARAMETER")
    sampleArtwork: Boolean = true,
): Color {
    val isDark = MicaTheme.colors.isDark
    return remember(song.coverColorArgb, isDark) {
        PlayerBackgroundBlend.comfortColor(
            Color(song.coverColorArgb),
            isDark,
        )
    }
}
