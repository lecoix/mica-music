package com.mica.music.data

enum class AppFontSource(
    val storageValue: String,
    val settingsLabel: String,
) {
    SYSTEM("system", "系统默认"),
    IMPORTED("imported", "导入字体"),
    ;

    companion object {
        fun fromStorage(value: String?): AppFontSource =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

data class AppFontSelection(
    val source: AppFontSource = AppFontSource.SYSTEM,
    val displayName: String = "",
    val filePath: String = "",
) {
    val settingsLabel: String
        get() = when (source) {
            AppFontSource.SYSTEM -> AppFontSource.SYSTEM.settingsLabel
            AppFontSource.IMPORTED -> displayName.ifBlank { AppFontSource.IMPORTED.settingsLabel }
        }

    companion object {
        val SystemDefault = AppFontSelection()
    }
}
