package com.mica.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.mica.music.data.PlayerLowerBackgroundMode
import com.mica.music.data.Song

data class PlayerScreenAppearance(
    val coverColor: Color,
    val accent: Color,
    val contentColors: PlayerContentColors,
    val hifiBadgeColors: PlayerContentColors,
    val artworkJunction: Color,
    val lowerSurface: Color,
)

@Composable
fun rememberPlayerScreenAppearance(
    song: Song,
    lowerBackground: PlayerLowerBackgroundMode,
): PlayerScreenAppearance {
    val coverColor = rememberCoverColor(song)
    val isDark = MicaTheme.colors.isDark
    val mica = rememberMicaSurfaceColors()
    val appAccent = MicaTheme.colors.accent
    val coverAccent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    val accent = when (lowerBackground) {
        PlayerLowerBackgroundMode.THEME -> appAccent
        else -> coverAccent
    }
    val lowerSurface = when (lowerBackground) {
        PlayerLowerBackgroundMode.THEME -> mica.gradientEnd
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            PlayerBackgroundBlend.artworkHold(coverAccent, coverAccent, isDark)
        else -> mica.gradientEnd
    }
    val contentColors = when (lowerBackground) {
        PlayerLowerBackgroundMode.COVER_GLOW ->
            remember { blurredCoverPlayerContentColors() }
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            remember(lowerSurface) { PlayerBackgroundBlend.readableTextColors(lowerSurface) }
        else -> rememberPlayerContentColors()
    }
    val hifiBadgeColors = rememberPlayerContentColors()
    val artworkJunction = when (lowerBackground) {
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            PlayerBackgroundBlend.artworkJunction(coverAccent, coverAccent, isDark)
        else -> mica.gradientEnd
    }
    return PlayerScreenAppearance(
        coverColor = coverColor,
        accent = accent,
        contentColors = contentColors,
        hifiBadgeColors = hifiBadgeColors,
        artworkJunction = artworkJunction,
        lowerSurface = lowerSurface,
    )
}
