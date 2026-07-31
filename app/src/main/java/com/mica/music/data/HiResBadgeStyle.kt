package com.mica.music.data

/** Hi-Res 标志展示样式；内置预设后续在此 enum 扩展。 */
enum class HiResBadgeStyle(
    val storageValue: String,
    val settingsLabel: String,
) {
    DEFAULT("default", "默认"),
    GOLD_LABEL("gold_label", "黄底镂空"),
    CUSTOM_IMAGE("custom_image", "自定义图片"),
    ;

    companion object {
        fun fromStorage(value: String?): HiResBadgeStyle =
            entries.find { it.storageValue == value } ?: DEFAULT
    }
}

data class HiResBadgeAppearance(
    val style: HiResBadgeStyle = HiResBadgeStyle.DEFAULT,
    val customImagePath: String? = null,
)
