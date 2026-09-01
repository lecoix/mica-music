package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.theme.HifiSpacing

@Composable
internal fun SettingsCategoryList(
    query: String,
    onSelectCategory: (SettingsCategory) -> Unit,
    onOpenUsageTutorial: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
) {
    if (query.isBlank()) {
        SettingsSectionTitle("浏览设置")
        SettingsCategory.entries.forEach { category ->
            SettingsNavigationRow(
                title = category.title,
                subtitle = category.subtitle,
                onClick = { onSelectCategory(category) },
            )
        }

        Spacer(Modifier.height(HifiSpacing.lg))

        SettingsSectionTitle("使用技巧")
        SettingsNavigationRow(
            title = "重新查看教程",
            subtitle = "用动态示意了解 6 个常用操作",
            onClick = onOpenUsageTutorial,
        )
        SettingsTipRow("长按迷你播放栏可以定位当前歌曲")
        SettingsTipRow("在主页单指右滑可以打开侧栏，双指缩放可以修改歌曲显示样式")
        SettingsTipRow("在播放页长按专辑封面可以打开菜单，里面有睡眠定时、变速/变调")
        SettingsTipRow("在文件夹页可以通过左右滑动在不同深度的文件夹统合页切换")
        SettingsTipRow("歌曲列表排序里，已选「自定义」时再次点击，可进入「自定义·锁定」状态")
        SettingsTipRow("无法播放mp3可以尝试关闭Offload")
    } else {
        val results = SettingsSearchIndex.searchFromSettingsRoot(query)
        if (results.isEmpty()) {
            SettingsTipRow("未找到「$query」相关设置")
        } else {
            SettingsSectionTitle("搜索结果")
            results.forEach { entry ->
                SettingsNavigationRow(
                    title = entry.title,
                    subtitle = entry.searchSubtitle(),
                    onClick = { entry.navigateFromSettingsRoot(onSelectCategory, onOpenUsageTutorial, onOpenEqualizer) },
                )
            }
        }
    }
}

private fun SettingsIndexEntry.searchSubtitle(): String = buildList {
    target.category?.let { add(it.title) }
    target.sectionId?.let { sectionId ->
        if (sectionId != SettingsIndexSections.APPEARANCE) add(sectionId.toSearchLabel())
    }
    availability?.let(::add)
    if (isExperimental) add("实验功能")
    if (target.surface == SettingsIndexSurface.EQUALIZER && isEmpty()) add("侧栏入口")
}.joinToString(" · ")

private fun String.toSearchLabel(): String = when (this) {
    SettingsIndexSections.MINI_PLAYER -> "迷你播放"
    SettingsIndexSections.PLAYBACK_THEME -> "主题"
    SettingsIndexSections.PLAYBACK_COVER -> "封面与布局"
    SettingsIndexSections.PLAYBACK_INFO -> "信息行"
    SettingsIndexSections.LYRICS_THEME -> "主题"
    SettingsIndexSections.LYRICS_LETTER -> "信笺主题"
    SettingsIndexSections.LYRICS_GENERAL -> "歌词显示"
    SettingsIndexSections.LYRICS_OUTPUT -> "歌词输出"
    SettingsIndexSections.LYRICS_CLASSIC -> "经典列表"
    SettingsIndexSections.LYRICS_FONT -> "字体"
    SettingsIndexSections.LIBRARY_SOURCE -> "曲库来源"
    SettingsIndexSections.LIBRARY_SCAN -> "扫描"
    SettingsIndexSections.LIBRARY_ARTIST -> "艺术家"
    SettingsIndexSections.AUDIO -> "音频"
    SettingsIndexSections.DIAGNOSTICS -> "诊断"
    SettingsIndexSections.TUTORIAL -> "使用技巧"
    else -> this
}
