package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle

@Composable
internal fun DiagnosticsSettingsPanel(
    hasSongs: Boolean,
    onOpenMetadataDebug: () -> Unit,
    onOpenSpatialAudio: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SettingsSectionTitle("诊断与系统")

    SettingsActionRow(
        title = "元数据调试",
        subtitle = "逐首查看应用内字段、ID3/Vorbis、MediaMetadataRetriever 与解析器结果",
        onClick = onOpenMetadataDebug,
        enabled = hasSongs,
    )

    SettingsNavigationRow(
        title = "系统空间音频",
        subtitle = "查看系统 Spatializer、当前输出与 2.1 PCM 能力和头部跟踪状态",
        onClick = onOpenSpatialAudio,
    )

    SettingsActionRow(
        title = "系统权限与应用信息",
        subtitle = "管理存储/音频读取、通知等权限",
        onClick = onOpenAppSettings,
    )
}
