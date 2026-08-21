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
import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.data.preferences.UsbHybridPreferences
import com.mica.music.media.usbhybrid.UsbHybridDiagnosticsReport
import com.mica.music.media.usbhybrid.UsbHybridRuntimeMonitor
import com.mica.music.media.usbhybrid.UsbHybridSettingsPresentation
import com.mica.music.media.usbhybrid.UsbPlaybackFacts
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsChoiceRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
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
    var showNativeConfirmation by remember { mutableStateOf(false) }
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
    SettingsTipRow("首版只支持 Fosi Audio SK02；多个相同候选或未知 DAC 会拒绝打开。")
    SettingsTipRow("DoP 与 Native DSD 必须显式选择，任一模式失败都会停止，不自动切换到其他输出。")
    if (outputMode != UsbHybridOutputMode.SharedPcm) {
        SettingsTipRow("USB 独占禁用软件音量、ReplayGain、EQ、变速和重采样；请使用 DAC 硬件音量。")
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
            subtitle = "重新读取 SK02 identity/descriptor，并创建新的 request epoch",
            onClick = { UsbHybridPreferences.requestRetry(context) },
        )
        SettingsActionRow(
            title = "切回 Shared PCM",
            subtitle = "完整关闭 USB session 后同步重建 Android 共享输出",
            onClick = {
                outputMode = UsbHybridOutputMode.SharedPcm
                UsbHybridPreferences.setOutputMode(context, outputMode)
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
    SettingsTipRow("DAC quirk 只随 APK 发布；此页面不接受运行时 JSON override。")

    if (showNativeConfirmation) {
        AlertDialog(
            onDismissRequest = { showNativeConfirmation = false },
            shape = RectangleShape,
            title = { Text("实验性 Native DSD") },
            text = {
                Text(
                    "该模式仅使用 rewrite 的 SK02 u32le 参考 profile，framing 尚未由 Hybrid " +
                        "重新资格化，signalExact 固定为否。请使用 DAC 硬件音量；失败会停止，" +
                        "不会回退到 DoP 或 PCM。",
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
            text = facts.identity?.let { "Fosi Audio SK02" } ?: "Fosi Audio SK02 · 未建立目标",
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
    UsbHybridOutputMode.SharedPcm -> "Android 共享输出；USB transport 不会打开。"
    UsbHybridOutputMode.ExactPcm -> "只接受整数 PCM16 / PCM32；float、packed PCM24、SRC 或 DSP 会明确拒绝。"
    UsbHybridOutputMode.Dop -> "DSF 使用显式 DoP carrier；普通歌曲仍走 USB Exact PCM。"
    UsbHybridOutputMode.NativeDsdExperimental ->
        "SK02 u32le 实验 profile；framing 未重新资格化，signalExact 固定为否。"
}

private val UsbHybridOutputChoices = listOf(
    UsbHybridOutputMode.SharedPcm.ordinal to "Shared PCM",
    UsbHybridOutputMode.ExactPcm.ordinal to "USB Exact PCM",
    UsbHybridOutputMode.Dop.ordinal to "USB DoP",
    UsbHybridOutputMode.NativeDsdExperimental.ordinal to "USB Native DSD（实验）",
)
