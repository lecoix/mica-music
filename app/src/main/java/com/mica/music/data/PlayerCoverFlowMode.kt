package com.mica.music.data

/** Now Playing cover-area visual and interaction modes. */
enum class PlayerCoverFlowMode(
    val storageValue: String,
    val settingsLabel: String,
    val usesCoverFlowStage: Boolean = false,
    val usesParticleCover: Boolean = false,
    val usesPhotoStack: Boolean = false,
    val forcesSquareCrop: Boolean = false,
    val supportsImmersiveLower: Boolean = true,
    val usesHorizontalLyricsPage: Boolean = false,
) {
    STANDARD("standard", "标准"),

    CUSTOM_STANDARD(
        storageValue = "custom_standard",
        settingsLabel = "自定义标准",
        supportsImmersiveLower = false,
        usesHorizontalLyricsPage = true,
    ),

    PARTICLE_COVER(
        storageValue = "particle_cover",
        settingsLabel = "粒子封面",
        usesParticleCover = true,
        forcesSquareCrop = true,
        supportsImmersiveLower = false,
    ),

    PAUSE_FOLD(
        storageValue = "pause_fold",
        settingsLabel = "平行封面带",
        usesCoverFlowStage = true,
        forcesSquareCrop = true,
    ),

    RETRO_3D(
        storageValue = "retro_3d",
        settingsLabel = "复古立体封面",
        usesCoverFlowStage = true,
        forcesSquareCrop = true,
    ),

    PHOTO_STACK(
        storageValue = "photo_stack",
        settingsLabel = "拍立得回忆",
        usesPhotoStack = true,
        forcesSquareCrop = true,
        supportsImmersiveLower = false,
    ),
    ;

    companion object {
        fun fromStorage(value: String?): PlayerCoverFlowMode =
            entries.find { it.storageValue == value } ?: STANDARD
    }
}
