package com.mica.music.data

/** 播放页封面区域的主题/交互行为。 */
enum class PlayerCoverFlowMode(
    val storageValue: String,
    val settingsLabel: String,
    val usesCoverFlowStage: Boolean = false,
    val usesParticleCover: Boolean = false,
    val forcesSquareCrop: Boolean = false,
) {
    /** 维持当前播放页大封面的标准表现。 */
    STANDARD("standard", "标准"),

    /** 粒子封面：沿用标准播放布局，只替换封面渲染与切歌过渡。 */
    PARTICLE_COVER(
        storageValue = "particle_cover",
        settingsLabel = "粒子封面",
        usesParticleCover = true,
        forcesSquareCrop = true,
    ),

    /** 暂停后切换为平行封面带，队列相邻封面从两侧并排展开。 */
    PAUSE_FOLD(
        storageValue = "pause_fold",
        settingsLabel = "平行封面带",
        usesCoverFlowStage = true,
        forcesSquareCrop = true,
    ),

    /** 复古 Cover Flow：中心封面正面展示，两侧封面带透视倾斜与倒影。 */
    RETRO_3D(
        storageValue = "retro_3d",
        settingsLabel = "复古立体封面",
        usesCoverFlowStage = true,
        forcesSquareCrop = true,
    ),
    ;

    companion object {
        fun fromStorage(value: String?): PlayerCoverFlowMode =
            entries.find { it.storageValue == value } ?: STANDARD
    }
}
