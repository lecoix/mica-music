package com.mica.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.afalphy.sylvakru.UsbDacQuirks
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridVolumeControlMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.UsbHybridDiagnosticsReport
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSettingsPresentation
import com.mica.music.media.usbhybrid.UsbPlaybackFacts
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UsbHybridSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outputMode by remember { mutableStateOf(UsbHybridPreferences.outputMode(context)) }
    var volumeControlMode by remember { mutableStateOf(UsbHybridPreferences.volumeControlMode(context)) }
    var dsdGainCompensationDb by remember { mutableStateOf(UsbHybridPreferences.dsdGainCompensationDb(context)) }
    var volumeSmoothHandoff by remember { mutableStateOf(UsbHybridPreferences.volumeSmoothHandoff(context)) }
    var showNativeConfirmation by remember { mutableStateOf(false) }
    var showQuirkImport by remember { mutableStateOf(false) }
    var quirkJson by remember { mutableStateOf("") }
    var quirkImportMessage by remember { mutableStateOf<String?>(null) }
    val facts by UsbHybridRuntimeMonitor.facts.collectAsState()

    DisposableEffect(context) {
        val unregister = UsbHybridPreferences.registerChangeListener(context) { mode ->
            outputMode = mode
        }
        onDispose(unregister)
    }

    SettingsSectionTitle("DAC 与当前输出")
    UsbHybridStatusSummary(facts)

    SettingsSectionTitle("输出模式")
    SettingsChoiceRow(
        title = "USB 输出策略",
        subtitle = outputModeSubtitle(outputMode),
        choices = UsbHybridOutputChoices,
        selectedValue = outputMode.ordinal,
        onSelect = { ordinal ->
            val requested = UsbHybridOutputMode.entries[ordinal]
            if (requested == UsbHybridOutputMode.NativeDsdExperimental &&
                !UsbHybridPreferences.nativeAcknowledged(context)
            ) {
                showNativeConfirmation = true
            } else {
                outputMode = requested
                UsbHybridPreferences.setOutputMode(context, requested)
            }
        },
    )
    SettingsTipRow("同时只会自动选择一个 USB Audio 输出设备；检测到多个 DAC 时会拒绝猜测，请只保留目标设备。")
    SettingsTipRow("DoP 与 Native DSD 必须显式选择，任一模式失败都会停止，不自动切换到其他输出。")
    if (outputMode != UsbHybridOutputMode.SharedPcm) {
        SettingsTipRow("USB 独占禁用软件音量、ReplayGain、EQ、变速和重采样；请使用 DAC 硬件音量。")
    }

    if (outputMode != UsbHybridOutputMode.SharedPcm) {
        SettingsSectionTitle("音量")
        SettingsChoiceRow(
            title = "音量控制",
            subtitle = when (volumeControlMode) {
                UsbHybridVolumeControlMode.Auto -> "优先使用可验证的 DAC 硬件音量；PCM 不可用时回退数字音量"
                UsbHybridVolumeControlMode.Dac -> "使用 DAC 硬件音量；DSD 要求可读回验证"
                UsbHybridVolumeControlMode.Digital -> "仅 PCM 使用数字音量；DSD 不做数字衰减"
                UsbHybridVolumeControlMode.Raw -> "原始数字电平，满幅直通；应用音量不改变 USB 信号"
            },
            choices = UsbHybridVolumeControlChoices,
            selectedValue = volumeControlMode.ordinal,
            onSelect = { ordinal ->
                volumeControlMode = UsbHybridVolumeControlMode.entries[ordinal]
                UsbHybridPreferences.setVolumeControlMode(context, volumeControlMode)
                UsbHybridPreferences.requestRetry(context)
            },
        )
        SettingsChoiceRow(
            title = "DSD 增益补偿",
            subtitle = "仅在 DAC 硬件音量控制 DSD 时应用；范围 -12 到 +6 dB",
            choices = UsbHybridDsdGainChoices,
            selectedValue = dsdGainCompensationDb,
            onSelect = { value ->
                dsdGainCompensationDb = value
                UsbHybridPreferences.setDsdGainCompensationDb(context, value)
                UsbHybridPreferences.requestRetry(context)
            },
        )
        SettingsToggleRow(
            title = "平滑接管硬件音量",
            subtitle = "首次接管时优先从 DAC 当前已验证音量开始，降低突变风险",
            checked = volumeSmoothHandoff,
            onCheckedChange = { enabled ->
                volumeSmoothHandoff = enabled
                UsbHybridPreferences.setVolumeSmoothHandoff(context, enabled)
                UsbHybridPreferences.requestRetry(context)
            },
        )
        if (volumeControlMode == UsbHybridVolumeControlMode.Raw) {
            SettingsTipRow("原始数字电平不会跟随应用音量；请用 DAC 自身音量控制，避免高音量误操作。")
        }
    }
    SettingsSectionTitle("独占事实")
    UsbMetricRow(
        "PERMISSION" to UsbHybridSettingsPresentation.permissionLabel(facts.permission),
        "CLAIMED" to UsbHybridSettingsPresentation.yesNo(facts.claimed),
        "EXCLUSIVE" to UsbHybridSettingsPresentation.yesNo(facts.exclusive),
    )
    UsbMetricRow(
        "TRANSPORT EXACT" to UsbHybridSettingsPresentation.yesNo(facts.transportExact),
        "SIGNAL EXACT" to UsbHybridSettingsPresentation.yesNo(facts.signalExact),
    )
    SettingsTipRow("“独占”只说明 usbfs 已 claim；只有 signal exact 为“是”时才表示没有 DSP、SRC 或位深缩减。")

    SettingsSectionTitle("传输状态")
    UsbHybridTransportSummary(facts)
    facts.failure?.let { failure ->
        UsbHybridFailureRow("${failure.code} · ${failure.message}")
    }

    SettingsSectionTitle("操作与诊断")
    if (outputMode != UsbHybridOutputMode.SharedPcm) {
        SettingsActionRow(
            title = "授权并重试",
            subtitle = "重新读取 USB DAC identity/descriptor，并创建新的 request epoch",
            onClick = { UsbHybridPreferences.requestRetry(context) },
        )
        SettingsActionRow(
            title = "关闭 USB 独占",
            subtitle = "关闭 USB 独占传输，恢复 Android 系统共享音频输出",
            onClick = {
                outputMode = UsbHybridOutputMode.SharedPcm
                UsbHybridPreferences.setOutputMode(context, outputMode)
            },
        )
    }
    SettingsActionRow(
        title = "导出 USB 诊断",
        subtitle = "包含 raw descriptor、USB topology、quirk、协商事实、URB telemetry 与最近错误；不导出 serial",
        onClick = {
            scope.launch {
                val section = withContext(Dispatchers.IO) {
                    UsbHybridDiagnosticsReport.build(context, UsbHybridRuntimeMonitor.facts.value)
                }
                DiagnosticLog.shareReport(context, section)
            }
        },
    )
    SettingsActionRow(
        title = "导入 DAC quirk 配置",
        subtitle = "粘贴参考项目兼容的 JSON；override 优先于 APK 内置表，重连后验证",
        onClick = {
            quirkImportMessage = null
            showQuirkImport = true
        },
    )
    SettingsTipRow("测试 quirk 保存在应用本地 files 目录；验证通过后应合入内置兼容表随版本发布。")

    if (showQuirkImport) {
        AlertDialog(
            onDismissRequest = { showQuirkImport = false },
            shape = RectangleShape,
            title = { Text("导入 DAC quirk 配置") },
            text = {
                Column {
                    Text("粘贴参考项目兼容的 quirk JSON。导入只写本应用本地 override；不会自动开始播放或切换输出模式。")
                    OutlinedTextField(
                        value = quirkJson,
                        onValueChange = {
                            quirkJson = it
                            quirkImportMessage = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = HifiSpacing.md),
                        minLines = 8,
                        maxLines = 16,
                        label = { Text("quirk JSON") },
                    )
                    quirkImportMessage?.let { message ->
                        Text(
                            text = message,
                            style = MicaTheme.typography.caption,
                            color = MicaTheme.colors.textSecondary,
                            modifier = Modifier.padding(top = HifiSpacing.sm),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuirkImport = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = quirkJson.isNotBlank(),
                    onClick = {
                        val error = UsbDacQuirks.importOverride(context, quirkJson)
                        if (error == null) {
                            quirkImportMessage = "导入成功。请拔插 DAC 或使用“授权并重试”重新建立 USB 会话后验证。"
                        } else {
                            quirkImportMessage = error
                        }
                    },
                ) { Text("导入") }
            },
        )
    }

    if (showNativeConfirmation) {
        AlertDialog(
            onDismissRequest = { showNativeConfirmation = false },
            shape = RectangleShape,
            title = { Text("实验性 Native DSD") },
            text = {
                Text(
                    "Native DSD 仅在描述符或当前设备 profile 能明确证明 framing 时启用；" +
                        "未知设备不会猜测 Native framing，导入测试 quirk 也不会自动把 signalExact 提升为是；" +
                        "失败会停止，不会回退到 DoP 或 PCM。",
                )
            },
            dismissButton = {
                TextButton(onClick = { showNativeConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        UsbHybridPreferences.acknowledgeNative(context)
                        outputMode = UsbHybridOutputMode.NativeDsdExperimental
                        UsbHybridPreferences.setOutputMode(context, outputMode)
                        showNativeConfirmation = false
                    },
                ) { Text("理解并启用") }
            },
        )
    }
}

@Composable
private fun UsbHybridStatusSummary(facts: UsbPlaybackFacts) {
    val status = UsbHybridSettingsPresentation.statusLabel(facts)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = status }
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(HifiSpacing.sm)
                    .background(
                        if (facts.activeMode != null) {
                            MicaTheme.colors.accent
                        } else {
                            MicaTheme.colors.textTertiary
                        },
                    ),
            )
            Spacer(Modifier.width(HifiSpacing.sm))
            Text(
                text = "USB EXCLUSIVE",
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
        }
        Text(
            text = facts.identity?.let { "USB DAC · ${UsbHybridSettingsPresentation.targetLabel(facts)}" } ?: "USB DAC · 未建立目标",
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.padding(top = HifiSpacing.md),
        )
        UsbMetricRow(
            "FORMAT" to UsbHybridSettingsPresentation.formatLabel(facts),
            "RATE" to UsbHybridSettingsPresentation.rateLabel(facts),
            "DEPTH" to UsbHybridSettingsPresentation.depthLabel(facts),
            outerPadding = false,
        )
        UsbMetricRow(
            "CHANNELS" to UsbHybridSettingsPresentation.channelLabel(facts),
            "USB ID" to UsbHybridSettingsPresentation.targetLabel(facts),
            "EPOCH / SESSION" to "${facts.requestEpoch} / ${facts.sessionId ?: "-"}",
            outerPadding = false,
        )
    }
}

@Composable
private fun UsbHybridTransportSummary(facts: UsbPlaybackFacts) {
    val telemetry = facts.telemetry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = HifiSpacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
        ) {
            Text(
                text = "usbfs / ISO",
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = UsbHybridSettingsPresentation.transportHealthLabel(facts),
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
        }
        UsbMetricRow(
            "PENDING URB" to (telemetry?.pendingOutputUrbs?.toString() ?: "--"),
            "PENDING ISO" to (telemetry?.pendingIsoPackets?.toString() ?: "--"),
        )
        UsbMetricRow(
            "ISO TOTAL" to (telemetry?.totalIsoPackets?.toString() ?: "--"),
            "ISO ERRORS" to (telemetry?.isoErrorCount?.toString() ?: "--"),
        )
        SettingsTipRow("这里只显示 transport 实际上报的数据；没有精确水位时保持“--”，不估算或伪造。")
    }
}

@Composable
private fun UsbMetricRow(
    vararg metrics: Pair<String, String>,
    outerPadding: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (outerPadding) {
                    Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm)
                } else {
                    Modifier.padding(top = HifiSpacing.md)
                },
            ),
    ) {
        metrics.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MicaTheme.typography.monoSm,
                    color = MicaTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MicaTheme.typography.monoMd,
                    color = MicaTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = HifiSpacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun UsbHybridFailureRow(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Text(
            text = "最近失败",
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textTertiary,
        )
        Text(
            text = message,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

private fun outputModeSubtitle(mode: UsbHybridOutputMode): String = when (mode) {
    UsbHybridOutputMode.SharedPcm -> "关闭 USB 独占；使用 Android 系统共享音频输出。"
    UsbHybridOutputMode.ExactPcm -> "支持整数 PCM16 / PCM24 / PCM32；只允许无损扩宽到更高 USB 位深，float、缩位深、SRC 或 DSP 会明确拒绝。"
    UsbHybridOutputMode.Dop -> "DSF 使用显式 DoP carrier；普通歌曲仍走 USB Exact PCM。"
    UsbHybridOutputMode.NativeDsdExperimental ->
        "Native DSD 使用设备专属的已审核 profile；未知 framing 会明确拒绝。"
}

private val UsbHybridOutputChoices = listOf(
    UsbHybridOutputMode.SharedPcm.ordinal to "关闭 USB 独占",
    UsbHybridOutputMode.ExactPcm.ordinal to "USB Exact PCM",
    UsbHybridOutputMode.Dop.ordinal to "USB DoP",
    UsbHybridOutputMode.NativeDsdExperimental.ordinal to "USB Native DSD（实验）",
)
private val UsbHybridVolumeControlChoices = listOf(
    UsbHybridVolumeControlMode.Auto.ordinal to "自动",
    UsbHybridVolumeControlMode.Dac.ordinal to "DAC 硬件音量",
    UsbHybridVolumeControlMode.Digital.ordinal to "数字音量",
    UsbHybridVolumeControlMode.Raw.ordinal to "原始数字电平",
)

private val UsbHybridDsdGainChoices = (-12..6).map { value ->
    value to if (value > 0) "+${value} dB" else "${value} dB"
}
