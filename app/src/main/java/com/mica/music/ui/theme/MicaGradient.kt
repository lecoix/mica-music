package com.mica.music.ui.theme



import androidx.compose.foundation.background

import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.lerp



enum class MicaPreset(

    val storageValue: String,

    val settingsLabel: String,

) {

    Dawn("dawn", "晨曦"),

    Dusk("dusk", "暮色"),

    Midnight("midnight", "午夜"),

    Aurora("aurora", "极光"),

    Fog("fog", "雾霭"),

    CUSTOM("custom", "自定义"),

    ;



    companion object {

        fun fromStorage(value: String?): MicaPreset =

            entries.find { it.storageValue == value } ?: Dawn

    }

}



/** 用户自定义云母渐变（浅/深主题共用同一套色）。 */
data class CustomMicaBackground(
    val startArgb: Int,
    val endArgb: Int,
    val singleColor: Boolean,
) {
    companion object {
        val Default = CustomMicaBackground(
            startArgb = DEFAULT_START_ARGB,
            endArgb = DEFAULT_END_ARGB,
            singleColor = false,
        )

        const val DEFAULT_START_ARGB = 0xFFFFF6EE.toInt()
        const val DEFAULT_END_ARGB = 0xFFE3EEF8.toInt()
    }
}

/** 按浅色/深色主题返回云母渐变起止色；[CUSTOM] 时使用 [custom]。 */
fun MicaPreset.gradientColors(
    isDark: Boolean,
    custom: CustomMicaBackground = CustomMicaBackground.Default,
): Pair<Color, Color> = when (this) {
    MicaPreset.CUSTOM -> {
        val start = Color(custom.startArgb)
        val end = if (custom.singleColor) start else Color(custom.endArgb)
        start to end
    }

    MicaPreset.Dawn -> if (isDark) {

        HifiPalette.MicaDawnDarkStart to HifiPalette.MicaDawnDarkEnd

    } else {

        HifiPalette.MicaDawnStart to HifiPalette.MicaDawnEnd

    }

    MicaPreset.Dusk -> if (isDark) {

        HifiPalette.MicaDuskDarkStart to HifiPalette.MicaDuskDarkEnd

    } else {

        HifiPalette.MicaDuskStart to HifiPalette.MicaDuskEnd

    }

    MicaPreset.Midnight -> if (isDark) {

        HifiPalette.MicaMidnightDarkStart to HifiPalette.MicaMidnightDarkEnd

    } else {

        HifiPalette.MicaMidnightStart to HifiPalette.MicaMidnightEnd

    }

    MicaPreset.Aurora -> if (isDark) {

        HifiPalette.MicaAuroraDarkStart to HifiPalette.MicaAuroraDarkEnd

    } else {

        HifiPalette.MicaAuroraStart to HifiPalette.MicaAuroraEnd

    }

    MicaPreset.Fog -> if (isDark) {

        HifiPalette.MicaFogDarkStart to HifiPalette.MicaFogDarkEnd

    } else {

        HifiPalette.MicaFogStart to HifiPalette.MicaFogEnd

    }

}



@Deprecated("Use gradientColors(isDark) or micaBackground()", ReplaceWith("gradientColors(isDark)"))

fun MicaPreset.colors(): Pair<Color, Color> = gradientColors(isDark = false)



/** 垂直渐变在屏幕底边的主题色（终点色，与 [Modifier.micaBackground] 底边一致）。 */

fun MicaPreset.bottomThemeColor(
    isDark: Boolean,
    custom: CustomMicaBackground = CustomMicaBackground.Default,
): Color = gradientColors(isDark, custom).second



/** 浮岛卡片底边描边（略深于底面，半透明）。 */

fun micaFloatingCardBottomEdge(bottomSurface: Color, isDark: Boolean): Color {

    val base = if (isDark) {

        lerp(bottomSurface, Color.Black, 0.32f)

    } else {

        lerp(bottomSurface, Color.Black, 0.12f)

    }

    return base.copy(alpha = base.alpha * 0.55f)

}



@Composable
fun Modifier.micaBackground(preset: MicaPreset): Modifier {
    val custom = LocalCustomMicaBackground.current
    val (start, end) = preset.gradientColors(MicaTheme.colors.isDark, custom)
    return this.background(Brush.verticalGradient(listOf(start, end)))
}

/** 使用 [LocalMicaBackgroundPreset] 的页面背景渐变。 */
@Composable
fun Modifier.micaAppBackground(): Modifier =
    if (LocalCustomWallpaperPath.current == null) {
        micaBackground(LocalMicaBackgroundPreset.current)
    } else {
        this
    }

data class MicaSurfaceColors(
    val gradientStart: Color,
    val gradientEnd: Color,
)

/** 当前云母预设的渐变起止色（播放页、Material 背景等应与此一致）。 */
@Composable
fun rememberMicaSurfaceColors(): MicaSurfaceColors {
    val preset = LocalMicaBackgroundPreset.current
    val custom = LocalCustomMicaBackground.current
    val isDark = MicaTheme.colors.isDark
    val (start, end) = preset.gradientColors(isDark, custom)
    return androidx.compose.runtime.remember(preset, custom, isDark) {
        MicaSurfaceColors(gradientStart = start, gradientEnd = end)
    }
}

fun Modifier.micaFromArtwork(dominantColor: Color, vibrantColor: Color): Modifier {

    return this.background(

        Brush.verticalGradient(

            listOf(dominantColor.copy(alpha = 0.95f), vibrantColor.copy(alpha = 0.85f)),

        ),

    )

}
