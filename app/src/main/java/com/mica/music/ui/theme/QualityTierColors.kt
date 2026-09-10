package com.mica.music.ui.theme

import androidx.compose.ui.graphics.Color
import com.mica.music.data.LibraryAnalyzer

object QualityTierColors {
    val Light = mapOf(
        LibraryAnalyzer.TIER_HR to HifiPalette.HiResGold,
        LibraryAnalyzer.TIER_SQ to HifiPalette.PurplePrimary,
        LibraryAnalyzer.TIER_HQ to Color(0xFF5B9BD5),
        LibraryAnalyzer.TIER_OTHER to Color(0xFF5BA88C),
    )

    val Dark = mapOf(
        LibraryAnalyzer.TIER_HR to Color(0xFFE0BE6A),
        LibraryAnalyzer.TIER_SQ to Color(0xFF9D92FF),
        LibraryAnalyzer.TIER_HQ to Color(0xFF72B0E8),
        LibraryAnalyzer.TIER_OTHER to Color(0xFF6BBF9A),
    )

    fun of(label: String, isDark: Boolean): Color? =
        (if (isDark) Dark else Light)[label]
}
