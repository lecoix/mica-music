package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import com.mica.music.data.preferences.AudioOffloadDisabledReason
import com.mica.music.data.preferences.AudioOffloadPreferenceState
import com.mica.music.util.DiagnosticDetailConfig
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun DiagnosticsSettingsPanel(
    hasSongs: Boolean,
    audioOffloadState: AudioOffloadPreferenceState,
    onAudioOffloadChanged: (Boolean) -> Unit,
    detailedDiagnostics: DiagnosticDetailConfig,
    onDetailedDiagnosticsChanged: (DiagnosticDetailConfig) -> Unit,
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
                "允许系统用音频 DSP 降低长时间播放功耗；均衡器、频谱或音效实验室工作时会临时关闭，失速时自动切回 PCM。"
            } else {
                "已手动关闭，所有格式使用 PCM 播放路径。"
            }
        },
        checked = audioOffloadState.enabled,
        onCheckedChange = onAudioOffloadChanged,
    )
    SettingsTipRow("部分 MP3 无法播放时可尝试关闭 Offload")

    SettingsSectionTitle("详细诊断")
    SettingsToggleRow(
        title = "详细诊断总开关",
        subtitle = "默认关闭；开启后只记录下方选中的领域，会增加日志写入与少量耗电。崩溃、带异常的错误和关键故障事件不受此开关影响。",
        checked = detailedDiagnostics.enabled,
        onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(enabled = it)) },
    )
    if (detailedDiagnostics.enabled) {
        SettingsToggleRow(
            title = "曲库与扫描",
            subtitle = "自动同步、扫描、缓存、数据库、远程曲库与封面修复",
            checked = detailedDiagnostics.libraryScan,
            onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(libraryScan = it)) },
        )
        SettingsToggleRow(
            title = "播放与媒体会话",
            subtitle = "队列同步、播放状态、恢复、切歌、MediaSession 与歌词输出",
            checked = detailedDiagnostics.playbackMedia,
            onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(playbackMedia = it)) },
        )
        SettingsToggleRow(
            title = "音频链路",
            subtitle = "PCM/DSD、DSP、频谱、ReplayGain、Offload 与格式探针",
            checked = detailedDiagnostics.audioPipeline,
            onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(audioPipeline = it)) },
        )
        SettingsToggleRow(
            title = "USB 与设备",
            subtitle = "USB 独占输出、DAC 状态、授权、传输与设备路由",
            checked = detailedDiagnostics.usbDevice,
            onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(usbDevice = it)) },
        )
        SettingsToggleRow(
            title = "UI 与渲染",
            subtitle = "粒子封面、星图、动态背景、小窗/分屏触摸与界面渲染",
            checked = detailedDiagnostics.uiRendering,
            onCheckedChange = { onDetailedDiagnosticsChanged(detailedDiagnostics.copy(uiRendering = it)) },
        )
    }

    SettingsActionRow(
        title = "元数据调试",
        subtitle = "查看标签与各解析器结果",
        onClick = onOpenMetadataDebug,
        enabled = hasSongs,
    )

    SettingsNavigationRow(
        title = "系统空间音频",
        subtitle = "查看 Spatializer、输出能力与头部跟踪",
        onClick = onOpenSpatialAudio,
    )

    SettingsActionRow(
        title = "系统权限与应用信息",
        onClick = onOpenAppSettings,
    )
}
