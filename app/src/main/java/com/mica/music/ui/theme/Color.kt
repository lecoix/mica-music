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

    /** 原样比例：方框内留白/未铺满区域的底衬 */
    val CoverFitLetterbox = Color.Black

    // —— 晨曦：浅色=日出暖雾→天光蓝；深色=海军→琥珀（与午夜浅色对调）——
    val MicaDawnStart = Color(0xFFFFF6EE)
    val MicaDawnEnd = Color(0xFFE3EEF8)
    val MicaDawnDarkStart = Color(0xFF081420)
    val MicaDawnDarkEnd = Color(0xFF8B4E28)

    // —— 暮色 ——
    val MicaDuskStart = Color(0xFFFFF4EB)
    val MicaDuskEnd = Color(0xFFFFE8F2)
    val MicaDuskDarkStart = Color(0xFF2A1810)
    val MicaDuskDarkEnd = Color(0xFF6B3A32)

    // —— 午夜（与晨曦已对调：午夜取用原晨曦奶白+淡紫）——
    val MicaMidnightStart = Color(0xFFF7F2E8)
    val MicaMidnightEnd = Color(0xFFE8E0F2)
    val MicaMidnightDarkStart = Color(0xFF141022)
    val MicaMidnightDarkEnd = Color(0xFF251A3D)

    // —— 极光 ——
    val MicaAuroraStart = Color(0xFFE6F5F0)
    val MicaAuroraEnd = Color(0xFFD8E8FF)
    val MicaAuroraDarkStart = Color(0xFF0A1F1A)
    val MicaAuroraDarkEnd = Color(0xFF1E4A3D)

    val MicaFogStart = Color(0xFFF5F5F8)
    val MicaFogEnd = Color(0xFFE8EBF0)
    val MicaFogDarkStart = Color(0xFF12121A)
    val MicaFogDarkEnd = Color(0xFF1E1E2A)
}

data class HifiColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val surfaceGlass: Color,
    /** 不透明浮层/卡片（迷你播放栏浮岛等） */
    val surfaceCard: Color,
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
    surfaceCard = HifiPalette.NeutralWhite,
    isDark = false,
)

val DarkHifiColors = HifiColors(
    textPrimary = HifiPalette.NeutralWhite,
    textSecondary = HifiPalette.NeutralWhite.copy(alpha = 0.70f),
    textTertiary = HifiPalette.NeutralWhite.copy(alpha = 0.40f),
    divider = HifiPalette.NeutralWhite.copy(alpha = 0.12f),
    surfaceGlass = HifiPalette.NeutralBlack.copy(alpha = 0.30f),
    surfaceCard = HifiPalette.MicaFogDarkEnd,
    isDark = true,
)
