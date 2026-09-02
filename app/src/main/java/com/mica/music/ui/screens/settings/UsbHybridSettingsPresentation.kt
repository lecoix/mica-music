package com.mica.music.ui.screens.settings

import com.mica.music.data.preferences.UsbHybridOutputMode
import com.mica.music.usb.UsbPermissionStatus
import com.mica.music.usb.UsbPlaybackMode
import com.mica.music.usb.UsbPlaybackSnapshot

object UsbHybridSettingsPresentation {
    fun entrySummary(facts: UsbPlaybackSnapshot, selectedMode: UsbHybridOutputMode): String = when {
        facts.activeMode != null ->
            "ACTIVE · ${modeLabel(facts.activeMode)} · ${formatLabel(facts)}"
        facts.failure != null ->
            "${preferenceModeLabel(selectedMode)} · ${facts.failure.code}"
        facts.permission == UsbPermissionStatus.REQUESTED ->
            "${preferenceModeLabel(selectedMode)} · 等待 USB 权限"
        selectedMode == UsbHybridOutputMode.SharedPcm -> "USB 独占已关闭 · Android 共享输出"
        else -> "${preferenceModeLabel(selectedMode)} · 未激活"
    }

    fun statusLabel(facts: UsbPlaybackSnapshot): String = when {
        facts.activeMode != null -> "ACTIVE · ${modeLabel(facts.activeMode)}"
        facts.failure != null -> "已停止 · ${facts.failure.code}"
        facts.permission == UsbPermissionStatus.REQUESTED -> "等待 USB 权限"
        facts.requestedMode == UsbPlaybackMode.SHARED_PCM -> "Android 共享输出"
        facts.permission == UsbPermissionStatus.GRANTED -> "已授权 · 未激活"
        facts.permission == UsbPermissionStatus.DENIED -> "USB 权限已拒绝"
        else -> "等待授权并重试"
    }

    fun formatLabel(facts: UsbPlaybackSnapshot): String = facts.streamFormat ?: when (facts.activeMode) {
        UsbPlaybackMode.USB_EXACT_PCM -> "PCM"
        UsbPlaybackMode.USB_DOP -> "DSD · DoP"
        UsbPlaybackMode.USB_NATIVE_DSD_EXPERIMENTAL -> "DSD · Native"
        UsbPlaybackMode.SHARED_PCM -> "USB 独占已关闭"
        null -> "--"
    }

    fun rateLabel(facts: UsbPlaybackSnapshot): String {
        val sampleRate = facts.sampleRate ?: return "--"
        return when {
            sampleRate >= 2_822_400 && sampleRate % 44_100 == 0 -> "DSD${sampleRate / 44_100}"
            sampleRate % 1_000 == 0 -> "${sampleRate / 1_000} kHz"
            sampleRate >= 1_000 -> "${sampleRate / 1_000.0} kHz"
            else -> "$sampleRate Hz"
        }
    }

    fun depthLabel(facts: UsbPlaybackSnapshot): String =
        facts.usbBitResolution?.let { "$it bit" } ?: "--"

    fun channelLabel(facts: UsbPlaybackSnapshot): String =
        facts.channels?.let { "$it ch" } ?: "--"

    fun targetLabel(facts: UsbPlaybackSnapshot): String = facts.identity?.let {
        "0x%04x:0x%04x".format(it.vendorId and 0xffff, it.productId and 0xffff)
    } ?: "--"

    fun permissionLabel(state: UsbPermissionStatus): String = when (state) {
        UsbPermissionStatus.NOT_REQUIRED -> "无需授权"
        UsbPermissionStatus.REQUESTED -> "等待授权"
        UsbPermissionStatus.GRANTED -> "已授权"
        UsbPermissionStatus.DENIED -> "已拒绝"
    }

    fun yesNo(value: Boolean): String = if (value) "是" else "否"

    fun transportHealthLabel(facts: UsbPlaybackSnapshot): String = when {
        facts.activeMode == null -> "待机"
        facts.telemetry?.isoErrorCount?.let { it > 0L } == true -> "ISO 错误"
        facts.telemetry?.pendingOutputUrbs?.let { it > 0L } == true -> "稳定"
        else -> "活动"
    }

    fun lines(facts: UsbPlaybackSnapshot): List<String> = buildList {
        add("请求：${modeLabel(facts.requestedMode)}")
        add("状态：${facts.activeMode?.let { "ACTIVE · ${modeLabel(it)}" } ?: "未激活"}")
        add("权限：${permissionLabel(facts.permission)}")
        add("已 claim：${yesNo(facts.claimed)}")
        add("独占：${yesNo(facts.exclusive)}")
        add("传输保持：${yesNo(facts.transportExact)}")
        add("信号保持：${yesNo(facts.signalExact)}")
        if (facts.sampleRate != null && facts.channels != null) {
            add(
                "实际格式：${facts.streamFormat ?: "unknown"} · ${facts.sampleRate} Hz · " +
                    "${facts.channels} ch · USB ${facts.usbBitResolution ?: "?"} bit",
            )
        }
        add("epoch/session：${facts.requestEpoch}/${facts.sessionId ?: "-"}")
        facts.failure?.let { add("失败：${it.code} · ${it.message}") }
        if (facts.requestedMode == UsbPlaybackMode.USB_NATIVE_DSD_EXPERIMENTAL ||
            facts.activeMode == UsbPlaybackMode.USB_NATIVE_DSD_EXPERIMENTAL
        ) {
            add("Native DSD：实验；framing 尚未重新资格化，使用硬件音量")
        }
    }

    fun modeLabel(mode: UsbPlaybackMode): String = when (mode) {
        UsbPlaybackMode.SHARED_PCM -> "USB 独占已关闭"
        UsbPlaybackMode.USB_EXACT_PCM -> "USB Exact PCM"
        UsbPlaybackMode.USB_DOP -> "USB DoP"
        UsbPlaybackMode.USB_NATIVE_DSD_EXPERIMENTAL -> "USB Native DSD（实验）"
    }

    fun preferenceModeLabel(mode: UsbHybridOutputMode): String = when (mode) {
        UsbHybridOutputMode.SharedPcm -> "关闭 USB 独占"
        UsbHybridOutputMode.ExactPcm -> "USB Exact PCM"
        UsbHybridOutputMode.Dop -> "USB DoP"
        UsbHybridOutputMode.NativeDsdExperimental -> "USB Native DSD（实验）"
    }
}
