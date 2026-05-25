package com.mica.music.data

/** 主页底部迷你播放栏样式。 */
enum class MiniPlayerStyle(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 方案 B：左右留白浮岛玻璃卡片，大封面与曲名/歌手、播放键。 */
    FLOATING_ISLAND("floating_island", "浮岛卡片"),

    /** 方案 D：通栏底条（底边主题色 + 底区填满），顶部分割线，左播放、右迷你频谱。 */
    AUDIOPHILE("audiophile", "极简 Hi‑Fi"),
    ;

    companion object {
        fun fromStorage(value: String?): MiniPlayerStyle =
            entries.find { it.storageValue == value } ?: FLOATING_ISLAND
    }
}
