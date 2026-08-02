package com.mica.music.data

/** Exactly one external lyric surface can be active at a time. */
enum class ExternalLyricsMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    OFF("off", "关闭"),
    DESKTOP("desktop", "桌面歌词"),
    STATUS_BAR("status_bar", "状态栏歌词"),
    ;

    companion object {
        fun fromStorage(value: String?): ExternalLyricsMode = entries.firstOrNull {
            it.storageValue == value
        } ?: OFF
    }
}

/** How external lyric windows behave while Mica itself is in the foreground. */
enum class ExternalLyricsVisibilityMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    DEFAULT("default", "默认"),
    HIDE_WHEN_APP_FOREGROUND("hide_when_app_foreground", "在软件内隐藏"),
    ;

    companion object {
        fun fromStorage(value: String?): ExternalLyricsVisibilityMode = entries.firstOrNull {
            it.storageValue == value
        } ?: DEFAULT
    }
}

/** Color treatment shared by the desktop and status-bar lyric surfaces. */
enum class ExternalLyricsColorMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    SINGLE("single", "单色"),
    GRADIENT("gradient", "多色混合"),
    ;

    companion object {
        fun fromStorage(value: String?): ExternalLyricsColorMode = entries.firstOrNull {
            it.storageValue == value
        } ?: SINGLE
    }
}

data class ExternalLyricsStyle(
    val visibilityMode: ExternalLyricsVisibilityMode = ExternalLyricsVisibilityMode.DEFAULT,
    val colorMode: ExternalLyricsColorMode = ExternalLyricsColorMode.SINGLE,
    val colorsArgb: List<Int> = DEFAULT_EXTERNAL_LYRICS_COLORS,
    val gradientAngleDegrees: Int = DEFAULT_EXTERNAL_LYRICS_GRADIENT_ANGLE,
    val desktopOriginalFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    val desktopTranslationFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    val statusBarOriginalFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    val statusBarTranslationFontSizeSp: Int = DEFAULT_LYRICS_PAGE_FONT_SIZE_SP,
    val desktopWidthPercent: Int = DEFAULT_EXTERNAL_LYRICS_WIDTH_PERCENT,
    val statusBarWidthPercent: Int = DEFAULT_EXTERNAL_LYRICS_WIDTH_PERCENT,
) {
    val normalizedColors: List<Int>
        get() = normalizeExternalLyricsColors(colorsArgb)
}

const val DEFAULT_EXTERNAL_LYRICS_GRADIENT_ANGLE = 0
const val DEFAULT_STATUS_BAR_LYRICS_TOP_OFFSET_DP = 0
const val DEFAULT_EXTERNAL_LYRICS_WIDTH_PERCENT = 100
const val MIN_EXTERNAL_LYRICS_WIDTH_PERCENT = 50
const val MAX_EXTERNAL_LYRICS_WIDTH_PERCENT = 100
const val MIN_STATUS_BAR_LYRICS_TOP_OFFSET_DP = 0
const val MAX_STATUS_BAR_LYRICS_TOP_OFFSET_DP = 240
const val MAX_EXTERNAL_LYRICS_COLORS = 4

val DEFAULT_EXTERNAL_LYRICS_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFF66CCFF.toInt(),
    0xFFFF80B5.toInt(),
    0xFFFFD166.toInt(),
)

fun normalizeExternalLyricsColors(colors: List<Int>): List<Int> =
    colors.take(MAX_EXTERNAL_LYRICS_COLORS).ifEmpty { DEFAULT_EXTERNAL_LYRICS_COLORS }
