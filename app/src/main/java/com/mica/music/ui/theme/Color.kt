package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color

object HifiPalette {
    val PurplePrimary = Color(0xFF8B7AFF)
    val PurpleGlow = Color(0xFFA89BFF)
    val HiResGold = Color(0xFFD4AC4F)
    val LikeRed = Color(0xFFFF6B6B)

    val NeutralBlack = Color(0xFF1A1A1A)
    val NeutralGray600 = Color(0xFF6B6B6B)
    val NeutralGray400 = Color(0xFF9B9B9B)
    val NeutralWhite = Color(0xFFFFFFFF)

    val MicaDawnStart = Color(0xFFF7F2E8)
    val MicaDawnEnd = Color(0xFFE8E0F2)
    val MicaDuskStart = Color(0xFFFFE6CC)
    val MicaDuskEnd = Color(0xFFFFCCD9)
    val MicaMidnightStart = Color(0xFF0D1B2A)
    val MicaMidnightEnd = Color(0xFFD4823A)
    val MicaAuroraStart = Color(0xFF1A0B2E)
    val MicaAuroraEnd = Color(0xFF3B2266)
    val MicaFogStart = Color(0xFFF5F5F8)
    val MicaFogEnd = Color(0xFFE8EBF0)

    /** 深色主题下的页面背景渐变 */
    val MicaFogDarkStart = Color(0xFF12121A)
    val MicaFogDarkEnd = Color(0xFF1E1E2A)
    val MicaDawnDarkStart = Color(0xFF141022)
    val MicaDawnDarkEnd = Color(0xFF251A3D)
}

data class HifiColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val surfaceGlass: Color,
    val accent: Color = HifiPalette.PurplePrimary,
    val hiRes: Color = HifiPalette.HiResGold,
    val like: Color = HifiPalette.LikeRed,
    val isDark: Boolean = false,
)

val LightHifiColors = HifiColors(
    textPrimary = HifiPalette.NeutralBlack,
    textSecondary = HifiPalette.NeutralGray600,
    textTertiary = HifiPalette.NeutralGray400,
    divider = HifiPalette.NeutralBlack.copy(alpha = 0.08f),
    surfaceGlass = HifiPalette.NeutralWhite.copy(alpha = 0.60f),
    isDark = false,
)

val DarkHifiColors = HifiColors(
    textPrimary = HifiPalette.NeutralWhite,
    textSecondary = HifiPalette.NeutralWhite.copy(alpha = 0.70f),
    textTertiary = HifiPalette.NeutralWhite.copy(alpha = 0.40f),
    divider = HifiPalette.NeutralWhite.copy(alpha = 0.12f),
    surfaceGlass = HifiPalette.NeutralBlack.copy(alpha = 0.30f),
    isDark = true,
)
