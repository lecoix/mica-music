package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppUiSettings
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.UsbHybridDiagnosticsReport
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSettingsPresentation
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AudioSettingsPanel(uiSettings: AppUiSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var replayGainMode by remember { mutableStateOf(ReplayGainPreferences.mode(context)) }
    var usbMode by remember { mutableStateOf(UsbHybridPreferences.outputMode(context)) }
    val usbFacts by UsbHybridRuntimeMonitor.facts.collectAsState()

    SettingsSectionTitle("音频标准化")
    SettingsChoiceRow(
        title = "ReplayGain",
        subtitle = "按标签降低音量并防止削波；无有效标签时保持原始音量",
        choices = ReplayGainChoices,
        selectedValue = replayGainMode.ordinal,
        onSelect = { ordinal ->
            replayGainMode = ReplayGainMode.entries[ordinal]
            ReplayGainPreferences.setMode(context, replayGainMode)
        },
    )

    SettingsSectionTitle("播放行为")
    SettingsToggleRow(
        title = "独占音频焦点",
        subtitle = "开启时播放会让其他应用暂停；关闭后允许与其他应用一起播放",
        checked = uiSettings.audioFocusEnabled,
        onCheckedChange = { uiSettings.updateAudioFocusEnabled(it) },
    )

    SettingsSectionTitle("USB 独占输出")
    SettingsChoiceRow(
        title = "输出模式",
        subtitle = "首版仅验证 Fosi Audio SK02；USB 模式使用硬件音量，失败会停止且不会自动回退。",
        choices = listOf(
            UsbHybridOutputMode.SharedPcm.ordinal to "Shared PCM",
            UsbHybridOutputMode.ExactPcm.ordinal to "USB Exact PCM",
        ),
        selectedValue = usbMode.ordinal,
        onSelect = { ordinal ->
            usbMode = UsbHybridOutputMode.entries[ordinal]
            UsbHybridPreferences.setOutputMode(context, usbMode)
        },
    )
    UsbHybridSettingsPresentation.lines(usbFacts).forEach { line ->
        SettingsTipRow(line)
    }
    if (usbMode != UsbHybridOutputMode.SharedPcm) {
        SettingsActionRow(
            title = "授权并重试",
            subtitle = "重新读取 SK02 identity/descriptor 并创建新的 request epoch",
            onClick = { UsbHybridPreferences.requestRetry(context) },
        )
        SettingsActionRow(
            title = "切回 Shared PCM",
            subtitle = "完整关闭 USB session 后同步重建共享输出",
            onClick = {
                usbMode = UsbHybridOutputMode.SharedPcm
                UsbHybridPreferences.setOutputMode(context, usbMode)
            },
        )
    }
    SettingsActionRow(
        title = "导出 USB 诊断",
        subtitle = "包含 APK hash、identity、协商事实、URB telemetry 与最近错误；不导出 serial",
        onClick = {
            scope.launch {
                val section = withContext(Dispatchers.IO) {
                    UsbHybridDiagnosticsReport.build(context, UsbHybridRuntimeMonitor.facts.value)
                }
                DiagnosticLog.shareReport(context, section)
            }
        },
    )
}
