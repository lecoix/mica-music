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
import com.mica.music.data.MusicLibrary
import com.mica.music.data.ReplayGainMode
import com.mica.music.data.preferences.ChannelBalancePreferences
import com.mica.music.data.preferences.ReplayGainPreferences
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSettingsPresentation
import com.mica.music.media.loudness.LoudnessScanManager
import com.mica.music.media.MicaEqualizerManager
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsNavigationRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.components.SettingsToggleRow

@Composable
internal fun AudioSettingsPanel(
    uiSettings: AppUiSettings,
    library: MusicLibrary,
    onOpenUsbExclusive: () -> Unit,
) {
    val context = LocalContext.current
    var replayGainMode by remember { mutableStateOf(ReplayGainPreferences.mode(context)) }
    var channelBalancePercent by remember {
        mutableStateOf(ChannelBalancePreferences.balancePercent(context))
    }
    var usbMode by remember { mutableStateOf(UsbHybridPreferences.outputMode(context)) }
    val usbFacts by UsbHybridRuntimeMonitor.facts.collectAsState()
    val loudnessScan by LoudnessScanManager.state.collectAsState()

    DisposableEffect(context) {
        val unregister = UsbHybridPreferences.registerChangeListener(context) { mode ->
            usbMode = mode
        }
        onDispose(unregister)
    }

    SettingsSectionTitle("音频标准化")
    SettingsChoiceRow(
        title = "ReplayGain",
        subtitle = "优先使用文件标签；按曲目缺少标签时使用 Mica 响度分析数据",
        choices = ReplayGainChoices,
        selectedValue = replayGainMode.ordinal,
        onSelect = { ordinal ->
            replayGainMode = ReplayGainMode.entries[ordinal]
            ReplayGainPreferences.setMode(context, replayGainMode)
        },
    )
    SettingsActionRow(
        title = if (loudnessScan.running) "正在扫描曲库响度" else "扫描曲库响度",
        subtitle = when {
            loudnessScan.running -> buildString {
                append(loudnessScan.progressLabel)
                if (loudnessScan.currentTitle.isNotBlank()) append(" · ${loudnessScan.currentTitle}")
                append(" · 成功 ${loudnessScan.succeeded} · 跳过 ${loudnessScan.skipped} · 失败 ${loudnessScan.failed}")
            }
            loudnessScan.total > 0 ->
                "上次：成功 ${loudnessScan.succeeded} · 跳过 ${loudnessScan.skipped} · 失败 ${loudnessScan.failed}；文件未变的结果会复用"
            else -> "分析整套曲库；文件未变且已有结果的歌曲会跳过，PCM 不落盘"
        },
        onClick = {
            if (!loudnessScan.running) {
                LoudnessScanManager.startLibraryScan(context, library, missingOnly = true)
            }
        },
    )

    SettingsSectionTitle("声道处理")
    SettingsSliderRow(
        title = "左右声道平衡",
        subtitle = "仅 Shared PCM 生效",
        value = channelBalancePercent,
        valueRange = ChannelBalancePreferences.MIN_PERCENT..ChannelBalancePreferences.MAX_PERCENT,
        suffix = "%",
        onValueChange = { value ->
            channelBalancePercent = value
            MicaEqualizerManager.setChannelBalancePercent(context, value)
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
