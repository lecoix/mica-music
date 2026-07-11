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
