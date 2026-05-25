package com.mica.music.data

enum class AppThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.find { it.storageValue == value } ?: SYSTEM
    }
}
