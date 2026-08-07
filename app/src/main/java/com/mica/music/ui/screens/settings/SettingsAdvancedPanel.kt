package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.preferences.AudioOffloadDisabledReason
import com.mica.music.data.preferences.AudioOffloadPreferenceState
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun DiagnosticsSettingsPanel(
    hasSongs: Boolean,
    audioOffloadState: AudioOffloadPreferenceState,
    onAudioOffloadChanged: (Boolean) -> Unit,
    onOpenMetadataDebug: () -> Unit,
    onOpenSpatialAudio: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SettingsSectionTitle("诊断与系统")

    SettingsToggleRow(
        title = "音频硬件卸载（Offload）",
        subtitle = when (audioOffloadState.disabledReason) {
            AudioOffloadDisabledReason.BUILT_IN_DENYLIST ->
                "当前设备与系统存在已知兼容问题，已默认关闭；重新开启将忽略内置保护并再次尝试。"
            AudioOffloadDisabledReason.VERIFIED_RUNTIME_FAILURE ->
                "检测到卸载播放失速，切回 PCM 后已恢复；重新开启会清除本机记录并再次尝试。"
            null -> if (audioOffloadState.enabled) {
                "允许系统用音频 DSP 降低长时间播放功耗；均衡器或频谱工作时会临时关闭，失速时自动切回 PCM。"
            } else {
                "已手动关闭，所有格式使用 PCM 播放路径。"
            }
        },
        checked = audioOffloadState.enabled,
        onCheckedChange = onAudioOffloadChanged,
    )

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
