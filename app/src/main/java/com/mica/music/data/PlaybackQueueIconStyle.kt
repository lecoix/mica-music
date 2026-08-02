package com.mica.music.data

/** 播放页底部播放列表按钮使用的图标方案。 */
enum class PlaybackQueueIconStyle(
    val storageValue: String,
    val settingsLabel: String,
) {
    ORIGINAL("original", "原图"),
    IMAGE_11("image_11", "11"),
    IMAGE_18("image_18", "18"),
    ;

    companion object {
        fun fromStorage(value: String?): PlaybackQueueIconStyle = when (value) {
            "image_14", "image_17" -> IMAGE_18
            else -> entries.find { it.storageValue == value } ?: ORIGINAL
        }
    }
}
