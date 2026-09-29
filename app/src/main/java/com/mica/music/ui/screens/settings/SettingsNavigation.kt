package com.mica.music.ui.screens.settings

internal enum class SettingsDetailPage(val title: String) {
    WALLPAPER("壁纸"),
    MINI_PLAYER("迷你播放栏"),
    PLAYER_INFO("信息行"),
    USB("USB 独占输出"),
    REMOTE("远程曲库"),
    EXTERNAL_LYRICS("外部歌词"),
}

/** Stable search IDs also determine the destination; no preference is changed by navigation. */
internal fun SettingsIndexEntry.detailPage(): SettingsDetailPage? = when {
    id.startsWith("appearance.wallpaper") || id == "appearance.restore-wallpaper" -> SettingsDetailPage.WALLPAPER
    target.sectionId == SettingsIndexSections.MINI_PLAYER -> SettingsDetailPage.MINI_PLAYER
    target.sectionId == SettingsIndexSections.PLAYBACK_INFO -> SettingsDetailPage.PLAYER_INFO
    id == "audio.usb-exclusive" -> SettingsDetailPage.USB
    id == "library.remote" -> SettingsDetailPage.REMOTE
    id == "lyrics.external" -> SettingsDetailPage.EXTERNAL_LYRICS
    else -> null
}

/** 设置子页系统返回是否应由 Settings 消费（而非交给外层导航）。 */
internal fun canSettingsSubpageBack(
    selectedCategory: SettingsCategory?,
    playerOverlayOpen: Boolean,
): Boolean = selectedCategory != null && !playerOverlayOpen

/** 从设置子页返回分类列表；始终回到根列表（`null`）。 */
internal fun consumeSettingsBack(selectedCategory: SettingsCategory?): SettingsCategory? = null

internal enum class SettingsTopBarBackAction {
    ExitSettings,
    PopCategory,
}

/** 顶栏返回键：在分类列表时退出设置，在子页时回到分类列表。 */
internal fun resolveSettingsTopBarBackAction(
    selectedCategory: SettingsCategory?,
): SettingsTopBarBackAction = if (selectedCategory == null) {
    SettingsTopBarBackAction.ExitSettings
} else {
    SettingsTopBarBackAction.PopCategory
}

internal fun settingsScreenTitle(
    selectedCategory: SettingsCategory?,
    usbHybridSubpageOpen: Boolean = false,
    remoteMusicSubpageOpen: Boolean = false,
    externalLyricsSubpageOpen: Boolean = false,
): String = when {
    usbHybridSubpageOpen -> "USB 独占输出"
    remoteMusicSubpageOpen -> "远程曲库"
    externalLyricsSubpageOpen -> "外部歌词"
    else -> selectedCategory?.title ?: "设置"
}

internal fun SettingsIndexEntry.navigateFromSettingsRoot(
    onSelectCategory: (SettingsCategory) -> Unit,
    onOpenUsageTutorial: () -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    when {
        id == "help.tutorial" -> onOpenUsageTutorial()
        target.surface == SettingsIndexSurface.EQUALIZER -> onOpenEqualizer()
        else -> target.category?.let(onSelectCategory)
    }
}
