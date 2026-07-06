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
    onSelectCategory: (SettingsCategory) -> Unit,
) {
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
    SettingsTipRow("长按迷你播放栏可以定位当前歌曲")
    SettingsTipRow("在播放页长按专辑封面可以打开菜单，里面有睡眠定时")
    SettingsTipRow("在文件夹页可以通过左右滑动在不同深度的文件夹统合页切换")
}
