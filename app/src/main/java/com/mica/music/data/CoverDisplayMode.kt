package com.mica.music.data

/** 列表 / 迷你播放栏 / 播放页封面的缩放策略。 */
enum class CoverDisplayMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 裁切填充方框（默认）。 */
    CROP_FILL("crop_fill", "裁切填充"),

    /** 方框内完整显示；播放页全屏封面可按比例排版（列表 / 歌词聚焦仍为正方形容器）。 */
    FIT_ORIGINAL("fit_original", "原样比例"),
    ;

    companion object {
        fun fromStorage(value: String?): CoverDisplayMode =
            entries.find { it.storageValue == value } ?: CROP_FILL
    }
}
