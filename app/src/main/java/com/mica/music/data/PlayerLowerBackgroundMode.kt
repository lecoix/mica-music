package com.mica.music.data

/** 播放页下半部分（歌词、进度条、控制区）背景样式。 */
enum class PlayerLowerBackgroundMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 仅使用设置中的云母背景渐变（不用专辑取色、不叠加强调色）。 */
    THEME("theme", "主题色"),

    /** 从封面底边扩散专辑取色，下半屏保持取色（与「主题色」独立）。 */
    ARTWORK_GRADIENT("artwork_gradient", "封面渐变"),

    /** 全屏强模糊封面（Android 12+）；低版本为取色渐变兜底 */
    COVER_GLOW("cover_glow", "封面模糊"),
    ;

    companion object {
        fun fromStorage(value: String?): PlayerLowerBackgroundMode =
            entries.find { it.storageValue == value } ?: COVER_GLOW
    }
}
