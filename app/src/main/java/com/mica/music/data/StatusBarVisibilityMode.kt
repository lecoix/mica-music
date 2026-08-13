package com.mica.music.data

enum class StatusBarVisibilityMode(
    val storageValue: String,
    val settingsLabel: String,
    val hidesOnPlayer: Boolean,
    val hidesOutsidePlayer: Boolean,
) {
    OFF("off", "关闭", hidesOnPlayer = false, hidesOutsidePlayer = false),
    PLAYER_ONLY("player_only", "仅播放页隐藏", hidesOnPlayer = true, hidesOutsidePlayer = false),
    NON_PLAYER_ONLY("non_player_only", "仅非播放页隐藏", hidesOnPlayer = false, hidesOutsidePlayer = true),
    ALL("all", "全部隐藏", hidesOnPlayer = true, hidesOutsidePlayer = true),
    ;

    companion object {
        fun fromStorage(value: String?): StatusBarVisibilityMode =
            entries.firstOrNull { it.storageValue == value } ?: OFF
    }
}
