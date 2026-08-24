package com.mica.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mica.music.data.AppUiSettings
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSettingsPresentation
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun AudioSettingsPanel(
    uiSettings: AppUiSettings,
    onOpenUsbExclusive: () -> Unit,
) {
    val context = LocalContext.current
    var replayGainMode by remember { mutableStateOf(ReplayGainPreferences.mode(context)) }
    var usbMode by remember { mutableStateOf(UsbHybridPreferences.outputMode(context)) }
    val usbFacts by UsbHybridRuntimeMonitor.facts.collectAsState()

    DisposableEffect(context) {
        val unregister = UsbHybridPreferences.registerChangeListener(context) { mode ->
            usbMode = mode
        }
        onDispose(unregister)
    }

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

    SettingsSectionTitle("USB 输出")
    SettingsNavigationRow(
        title = "USB 独占输出",
        subtitle = UsbHybridSettingsPresentation.entrySummary(usbFacts, usbMode),
        onClick = onOpenUsbExclusive,
    )
}
