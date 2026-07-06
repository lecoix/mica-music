package com.mica.music.ui.screens.settings.color

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import java.util.Locale

internal fun formatAccentHex(colorArgb: Int): String =
    String.format(Locale.US, "#%06X", colorArgb and 0x00FFFFFF)

internal fun parseAccentHex(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }) {
        return null
    }
    return (0xFF000000 or hex.toLong(16)).toInt()
}

internal fun hueGradientColors(): List<Color> =
    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { hue ->
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    }
