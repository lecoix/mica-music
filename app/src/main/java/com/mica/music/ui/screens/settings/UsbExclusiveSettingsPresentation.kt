package com.mica.music.ui.screens.settings

import com.mica.music.media.usb.PlaybackOutputFacts
import com.mica.music.media.usb.UsbOutputPhase
import com.mica.music.media.usb.UsbPcmEncoding
import com.mica.music.media.usb.UsbPermissionState

internal enum class UsbExclusiveSettingsAction {
    REQUEST_PERMISSION,
}

internal enum class UsbExclusiveSettingsState {
    DISABLED,
    WAITING_FOR_DEVICE,
    PERMISSION_REQUIRED,
    PERMISSION_PENDING,
    CONNECTING,
    ACTIVE,
    SHARED_PCM_FALLBACK,
    ERROR,
    READY,
}

internal data class UsbExclusiveSettingsPresentation(
    val state: UsbExclusiveSettingsState,
    val title: String,
    val subtitle: String,
    val action: UsbExclusiveSettingsAction? = null,
)

internal fun presentUsbExclusiveSettings(
    intentEnabled: Boolean,
    deviceAvailable: Boolean,
    devicePermission: UsbPermissionState,
    facts: PlaybackOutputFacts,
): UsbExclusiveSettingsPresentation {
    if (facts.failure?.fallbackToSharedPcm == true && !intentEnabled) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.SHARED_PCM_FALLBACK,
            title = "已自动切回系统输出",
            subtitle = "USB 连续恢复失败，当前由 SharedPcm 继续播放；重新开启后可再次尝试。",
        )
    }
    if (!intentEnabled) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.DISABLED,
            title = "USB 独占已关闭",
            subtitle = "当前使用 Android 系统音频输出。",
        )
    }
    if (!facts.attached && facts.failure?.stage == "detach") {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.SHARED_PCM_FALLBACK,
            title = "设备已拔出",
            subtitle = "SharedPcm 继续播放；重插并确认系统授权后，恢复之前的播放意图。",
        )
    }
    if (facts.permission == UsbPermissionState.REQUESTED) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.PERMISSION_PENDING,
            title = "等待 USB 授权",
            subtitle = "请在 Android 系统弹窗中确认；确认后自动继续建立独占输出。",
        )
    }
    if (facts.permission == UsbPermissionState.DENIED) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.PERMISSION_REQUIRED,
            title = "USB 授权被拒绝",
            subtitle = "当前使用 SharedPcm；可重新发起授权，系统弹窗仍需手动确认。",
            action = if (deviceAvailable) UsbExclusiveSettingsAction.REQUEST_PERMISSION else null,
        )
    }
    if (
        facts.phase == UsbOutputPhase.REQUESTED ||
        facts.phase == UsbOutputPhase.OPENING ||
        facts.phase == UsbOutputPhase.RELEASING
    ) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.CONNECTING,
            title = if (facts.phase == UsbOutputPhase.RELEASING) "正在释放 USB 输出" else "正在连接 SK02",
            subtitle = "正在切换音频路径，请稍候。",
        )
    }
    if (facts.phase == UsbOutputPhase.ACTIVE) {
        val format = facts.negotiatedFormat?.let {
            listOf(
                "${it.sampleRateHz / 1_000} kHz",
                when (it.encoding) {
                    UsbPcmEncoding.PCM_16 -> "16-bit"
                    UsbPcmEncoding.PCM_24_PACKED -> "24-bit"
                    UsbPcmEncoding.PCM_32 -> "32-bit"
                },
                "${it.channelCount} 声道",
            ).joinToString(" · ")
        }
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.ACTIVE,
            title = "USB 独占输出中",
            subtitle = buildList {
                add("Fosi Audio SK02")
                if (format != null) add(format)
                if (facts.signalExact) add("信号保持原样")
            }.joinToString(" · "),
        )
    }
    if (!deviceAvailable) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.WAITING_FOR_DEVICE,
            title = "等待连接设备",
            subtitle = "请接入已验证的 Fosi Audio SK02；接入后将请求 Android USB 权限。",
        )
    }
    if (devicePermission != UsbPermissionState.GRANTED) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.PERMISSION_REQUIRED,
            title = "需要 USB 授权",
            subtitle = "Android 要求你在系统弹窗中手动确认，Mica 无法静默绕过。",
            action = UsbExclusiveSettingsAction.REQUEST_PERMISSION,
        )
    }
    if (facts.phase == UsbOutputPhase.FAILED) {
        return UsbExclusiveSettingsPresentation(
            state = UsbExclusiveSettingsState.ERROR,
            title = "USB 独占未能启动",
            subtitle = "当前使用 SharedPcm；可关闭后重新开启并再次尝试。",
        )
    }
    return UsbExclusiveSettingsPresentation(
        state = UsbExclusiveSettingsState.READY,
        title = "SK02 已授权",
        subtitle = "开始播放时将建立 USB 独占输出。",
    )
}
