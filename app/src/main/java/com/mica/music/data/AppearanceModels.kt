package com.mica.music.data

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

const val DEFAULT_CUSTOM_WALLPAPER_OVERLAY_PERCENT = 40
const val MIN_CUSTOM_WALLPAPER_OVERLAY_PERCENT = 0
const val MAX_CUSTOM_WALLPAPER_OVERLAY_PERCENT = 100
const val DEFAULT_CUSTOM_WALLPAPER_BLUR_DP = 0
const val MIN_CUSTOM_WALLPAPER_BLUR_DP = 0
const val MAX_CUSTOM_WALLPAPER_BLUR_DP = 32

/** 用户壁纸在“自动铺满”基础上的额外缩放与归一化偏移。 */
data class CustomWallpaperCrop(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun clamped(): CustomWallpaperCrop = copy(
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        offsetX = offsetX.coerceIn(-1f, 1f),
        offsetY = offsetY.coerceIn(-1f, 1f),
    )

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        val Default = CustomWallpaperCrop()
    }
}
