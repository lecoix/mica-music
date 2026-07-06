package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun AdvancedSettingsPanel(
    includeNonMusic: Boolean,
    deepProbe: Boolean,
    hasSongs: Boolean,
    onIncludeNonMusicChange: (Boolean) -> Unit,
    onDeepProbeChange: (Boolean) -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SettingsSectionTitle("高级扫描与调试")

    SettingsToggleRow(
        title = "纳入非「音乐」标记的音频",
        subtitle = "开启后可扫描到部分 m4a / ALAC（MediaStore 里 IS_MUSIC=0）",
        checked = includeNonMusic,
        onCheckedChange = onIncludeNonMusicChange,
    )

    SettingsToggleRow(
        title = "深度分析音质与封面",
        subtitle = "我也不知道这玩意还有啥用，总之先别关",
        checked = deepProbe,
        onCheckedChange = onDeepProbeChange,
    )

    SettingsActionRow(
        title = "元数据调试",
        subtitle = "逐首查看应用内字段、ID3/Vorbis、Retriever 与解析器结果",
        onClick = onOpenMetadataDebug,
        enabled = hasSongs,
    )

    SettingsActionRow(
        title = "系统权限与应用信息",
        subtitle = "管理存储/音频读取、通知等权限",
        onClick = onOpenAppSettings,
    )
}
