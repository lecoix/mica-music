package com.mica.music.ui.screens.settings

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
): String = when {
    usbHybridSubpageOpen -> "USB 独占输出"
    remoteMusicSubpageOpen -> "远程曲库"
    else -> selectedCategory?.title ?: "设置"
}
