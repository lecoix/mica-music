package com.mica.music.data

/**
 * 标准系播放页折叠（紧凑）歌词显示行数。
 * 适用于 [PlayerCoverFlowMode.STANDARD]、粒子封面、平行封面带、复古立体封面；
 * 不影响自定义标准（其行数在布局编辑里单独配置）与拍立得回忆。
 */
enum class CompactLyricsLineMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 按可用高度在一行与三行之间自适应（布局引擎现状）。 */
    AUTO("auto", "自动"),

    /** 固定显示上一句 / 当前 / 下一句。 */
    THREE("three", "三行"),

    /** 固定只显示当前句。 */
    ONE("one", "一行"),
    ;

    companion object {
        fun fromStorage(value: String?): CompactLyricsLineMode =
            entries.find { it.storageValue == value } ?: AUTO
    }
}

/** 是否消费 [CompactLyricsLineMode] 偏好（与自定义布局行数无关）。 */
fun PlayerCoverFlowMode.usesCompactLyricsLinePreference(): Boolean = when (this) {
    PlayerCoverFlowMode.STANDARD,
    PlayerCoverFlowMode.PARTICLE_COVER,
    PlayerCoverFlowMode.PAUSE_FOLD,
    PlayerCoverFlowMode.RETRO_3D,
    -> true
    PlayerCoverFlowMode.CUSTOM_STANDARD,
    PlayerCoverFlowMode.PHOTO_STACK,
    -> false
}
