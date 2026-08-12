package com.mica.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Text
import com.mica.music.media.UsbHostOutputPreferences
import com.mica.music.media.UsbOutputRebuildRuntime
import com.mica.music.media.usb.AndroidUsbAudioDeviceRepository
import com.mica.music.media.usb.Sk02UsbContract
import com.mica.music.media.usb.UsbOutputDeviceLifecycle
import com.mica.music.media.usb.UsbOutputRequest
import com.mica.music.media.usb.UsbOutputRuntime
import com.mica.music.media.usb.UsbPermissionState
import com.mica.music.ui.components.SettingsActionRow
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsTipRow
import com.mica.music.ui.components.SettingsToggleRow
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun UsbExclusiveSettingsSection(context: Context) {
    val usbRepository = remember(context) { AndroidUsbAudioDeviceRepository(context) }
    var usbEnabled by remember { mutableStateOf(UsbHostOutputPreferences.isEnabled(context)) }
    var usbFacts by remember { mutableStateOf(UsbOutputRuntime.owner.facts) }
    var usbDevice by remember { mutableStateOf(usbRepository.snapshot().singleOrNull()) }

    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            usbEnabled = UsbHostOutputPreferences.isEnabled(context)
            usbFacts = UsbOutputRuntime.owner.facts
            usbDevice = usbRepository.snapshot().singleOrNull()
            delay(1_000)
        }
    }

    val presentation = presentUsbExclusiveSettings(
        intentEnabled = usbEnabled,
        deviceAvailable = usbDevice != null,
        devicePermission = usbDevice?.permission ?: UsbPermissionState.UNKNOWN,
        facts = usbFacts,
    )

    SettingsSectionTitle("USB 输出")
    SettingsToggleRow(
        title = "USB 独占输出",
        subtitle = "连接 Fosi Audio SK02 时绕过 Android 系统混音，按音源格式直接输出。",
        checked = usbEnabled,
        onCheckedChange = { requested ->
            val previous = usbEnabled
            UsbHostOutputPreferences.setEnabled(context, requested)
            usbEnabled = requested
            if (!requested) {
                UsbOutputRebuildRuntime.request(false, previous)
            } else {
                usbDevice?.let { device ->
                    if (device.permission == UsbPermissionState.GRANTED) {
                        UsbOutputRebuildRuntime.request(true, previous)
                    } else {
                        requestUsbPermission(context, usbRepository)
                    }
                }
            }
        },
    )

    UsbExclusiveStatusRow(presentation)

    if (presentation.action == UsbExclusiveSettingsAction.REQUEST_PERMISSION) {
        SettingsActionRow(
            title = "授权并重试",
            subtitle = "将打开 Android 系统授权弹窗；必须由你手动确认。",
            onClick = { requestUsbPermission(context, usbRepository) },
            enabled = usbDevice != null,
        )
    }

    SettingsTipRow("当前仅支持已验证的 Fosi Audio SK02。")
    SettingsTipRow("独占路径不启用 EQ、频谱、变速或 ReplayGain，也不会静默降低位深或采样率。")
    SettingsTipRow("应用音量不作为数字增益；请使用 DAC 的硬件音量旋钮。")
    SettingsTipRow("设备拔出或恢复失败时自动切回 SharedPcm，避免与系统输出同时占用设备。")
}

@Composable
private fun UsbExclusiveStatusRow(presentation: UsbExclusiveSettingsPresentation) {
    val indicatorColor: Color = when (presentation.state) {
        UsbExclusiveSettingsState.ACTIVE -> MicaTheme.colors.accent
        UsbExclusiveSettingsState.PERMISSION_REQUIRED,
        UsbExclusiveSettingsState.ERROR,
        -> MicaTheme.colors.like
        else -> MicaTheme.colors.textTertiary
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "${presentation.title}。${presentation.subtitle}"
            }
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
    ) {
        Box(
            Modifier
                .padding(top = HifiSpacing.xs)
                .size(HifiSize.activeDot)
                .background(indicatorColor),
        )
        Column(modifier = Modifier.padding(start = HifiSpacing.sm)) {
            Text(
                text = presentation.title,
                style = MicaTheme.typography.bodyLg,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = presentation.subtitle,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(top = HifiSpacing.xxs),
            )
        }
    }
}

private fun requestUsbPermission(
    context: Context,
    repository: AndroidUsbAudioDeviceRepository,
) {
    if (repository.snapshot().singleOrNull() == null) return
    runCatching {
        UsbOutputDeviceLifecycle.requestPermission(
            context,
            UsbOutputRequest(device = Sk02UsbContract.identity),
        )
    }.onFailure { error ->
        DiagnosticLog.event("UsbExclusiveSettings", "permission request failed", error)
    }
}
