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
    val sampleArtworkColor =
        lowerBackground != PlayerLowerBackgroundMode.THEME &&
            lowerBackground != PlayerLowerBackgroundMode.STAR_MAP
    val coverColor = rememberCoverColor(song, sampleArtwork = sampleArtworkColor)
    val isDark = MicaTheme.colors.isDark
    val mica = rememberMicaSurfaceColors()
    val themeContentColors = rememberPlayerContentColors()
    val appAccent = MicaTheme.colors.accent
    val coverAccent = PlayerBackgroundBlend.accentuateCover(coverColor, isDark)
    val accent = when (lowerBackground) {
        PlayerLowerBackgroundMode.THEME -> appAccent
        PlayerLowerBackgroundMode.STAR_MAP -> StarMapForegroundAccent
        else -> coverAccent
    }
    val lowerSurface = when (lowerBackground) {
        PlayerLowerBackgroundMode.THEME -> mica.gradientEnd
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            PlayerBackgroundBlend.artworkHold(coverAccent, coverAccent, isDark)
        PlayerLowerBackgroundMode.STAR_MAP -> StarMapBackgroundSurface
        else -> mica.gradientEnd
    }
    val contentColors = when (lowerBackground) {
        PlayerLowerBackgroundMode.COVER_GLOW,
        PlayerLowerBackgroundMode.DYNAMIC_LIGHT,
        PlayerLowerBackgroundMode.DYNAMIC_ARTWORK,
        // ponytail: cover-sample luminance → B/W; overlays ignored until contrast bugs show up
        -> remember(coverColor) { PlayerBackgroundBlend.readableTextColors(coverColor) }
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            remember(lowerSurface) { PlayerBackgroundBlend.readableTextColors(lowerSurface) }
        PlayerLowerBackgroundMode.STAR_MAP -> lightPlayerContentColors()
        else -> themeContentColors
    }.copy(
        dynamicColors = remember(coverColor, lowerSurface, isDark, lowerBackground) {
            if (lowerBackground == PlayerLowerBackgroundMode.STAR_MAP) {
                lightPlayerContentColors()
            } else {
                PlayerBackgroundBlend.dynamicTextColors(
                    coverColor = coverColor,
                    surface = lowerSurface,
                    isDark = isDark,
                )
            }
        },
    )
    val hifiBadgeColors =
        if (lowerBackground == PlayerLowerBackgroundMode.STAR_MAP) {
            lightPlayerContentColors()
        } else {
            themeContentColors
        }
    val artworkJunction = when (lowerBackground) {
        PlayerLowerBackgroundMode.ARTWORK_GRADIENT ->
            PlayerBackgroundBlend.artworkJunction(coverAccent, coverAccent, isDark)
        PlayerLowerBackgroundMode.STAR_MAP -> StarMapBackgroundSurface
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
