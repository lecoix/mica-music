package com.mica.music.data

enum class MiniPlayerSwipeAction(
    val storageValue: String,
    val settingsLabel: String,
) {
    PREVIOUS("previous", "上一曲"),
    NEXT("next", "下一曲"),
    ;

    companion object {
        fun fromStorage(value: String?, defaultValue: MiniPlayerSwipeAction): MiniPlayerSwipeAction =
            entries.find { it.storageValue == value } ?: defaultValue
    }
}
