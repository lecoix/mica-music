package com.mica.music.data

/** 播放页封面区域的交互行为。 */
enum class PlayerCoverFlowMode(
    val storageValue: String,
    val settingsLabel: String,
) {
    /** 维持当前播放页大封面表现。 */
    STANDARD("standard", "标准"),

    /** 暂停后切换为平行封面带，队列相邻封面从两侧并排展开。 */
    PAUSE_FOLD("pause_fold", "平行封面带"),

    /** 复古 Cover Flow：中心封面正面展示，两侧封面带透视倾斜与倒影。 */
    RETRO_3D("retro_3d", "复古立体封面"),
    ;

    companion object {
        fun fromStorage(value: String?): PlayerCoverFlowMode =
            entries.find { it.storageValue == value } ?: STANDARD
    }
}
