package com.mica.music.data

/** 播放页下半部分（歌词、进度条、控制区）背景样式。 */
enum class PlayerLowerBackgroundMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 方案一：纯色，跟随浅色/深色主题背景 */
    THEME("theme", "主题色"),

    /** 方案二：从专辑取色过渡到主题背景色 */
    ARTWORK_GRADIENT("artwork_gradient", "封面渐变"),

    /** 全屏强模糊封面（Android 12+）；低版本为取色渐变兜底 */
    COVER_GLOW("cover_glow", "封面模糊"),
    ;

    companion object {
        fun fromStorage(value: String?): PlayerLowerBackgroundMode =
            entries.find { it.storageValue == value } ?: COVER_GLOW
    }
}
