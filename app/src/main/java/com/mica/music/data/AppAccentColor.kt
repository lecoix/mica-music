package com.mica.music.data

/** 应用强调色（按钮、当前曲高亮、频谱等）。 */
enum class AppAccentColor(
    val storageValue: String,
    val settingsLabel: String,
) {
    PURPLE("purple", "紫韵"),
    GOLD("gold", "鎏金"),
    TEAL("teal", "青釉"),
    CORAL("coral", "珊瑚"),
    /** Android 12+ 跟随系统 Material You 主色；低版本回退紫韵。 */
    DYNAMIC("dynamic", "动态取色"),
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        fun fromStorage(value: String?): AppAccentColor =
            entries.find { it.storageValue == value } ?: PURPLE
    }
}
