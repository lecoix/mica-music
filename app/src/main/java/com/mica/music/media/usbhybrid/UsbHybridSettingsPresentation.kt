package com.mica.music.media.usbhybrid

import com.mica.music.data.preferences.UsbHybridOutputMode

object UsbHybridSettingsPresentation {
    fun entrySummary(facts: UsbPlaybackFacts, selectedMode: UsbHybridOutputMode): String = when {
        facts.activeMode != null ->
            "ACTIVE · ${modeLabel(facts.activeMode)} · ${formatLabel(facts)}"
        facts.failure != null ->
            "${preferenceModeLabel(selectedMode)} · ${facts.failure.code}"
        facts.permission == PermissionState.REQUESTED ->
            "${preferenceModeLabel(selectedMode)} · 等待 USB 权限"
        selectedMode == UsbHybridOutputMode.SharedPcm -> "Shared PCM · Android 共享输出"
        else -> "${preferenceModeLabel(selectedMode)} · 未激活"
    }

    fun statusLabel(facts: UsbPlaybackFacts): String = when {
        facts.activeMode != null -> "ACTIVE · ${modeLabel(facts.activeMode)}"
        facts.failure != null -> "已停止 · ${facts.failure.code}"
        facts.permission == PermissionState.REQUESTED -> "等待 USB 权限"
        facts.requestedMode == UsbExclusiveMode.SHARED_PCM -> "Android 共享输出"
        facts.permission == PermissionState.GRANTED -> "已授权 · 未激活"
        facts.permission == PermissionState.DENIED -> "USB 权限已拒绝"
        else -> "等待授权并重试"
    }

    fun formatLabel(facts: UsbPlaybackFacts): String = facts.streamFormat ?: when (facts.activeMode) {
        UsbExclusiveMode.USB_EXACT_PCM -> "PCM"
        UsbExclusiveMode.USB_DOP -> "DSD · DoP"
        UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL -> "DSD · Native"
        UsbExclusiveMode.SHARED_PCM -> "Shared PCM"
        null -> "--"
    }

    fun rateLabel(facts: UsbPlaybackFacts): String {
        val sampleRate = facts.sampleRate ?: return "--"
        return when {
            sampleRate >= 2_822_400 && sampleRate % 44_100 == 0 -> "DSD${sampleRate / 44_100}"
            sampleRate % 1_000 == 0 -> "${sampleRate / 1_000} kHz"
            sampleRate >= 1_000 -> "${sampleRate / 1_000.0} kHz"
            else -> "$sampleRate Hz"
        }
    }

    fun depthLabel(facts: UsbPlaybackFacts): String =
        facts.usbBitResolution?.let { "$it bit" } ?: "--"

    fun channelLabel(facts: UsbPlaybackFacts): String =
        facts.channels?.let { "$it ch" } ?: "--"

    fun targetLabel(facts: UsbPlaybackFacts): String = facts.identity?.let {
        "0x%04x:0x%04x".format(it.vendorId and 0xffff, it.productId and 0xffff)
    } ?: "--"

    fun permissionLabel(state: PermissionState): String = when (state) {
        PermissionState.NOT_REQUIRED -> "无需授权"
        PermissionState.REQUESTED -> "等待授权"
        PermissionState.GRANTED -> "已授权"
        PermissionState.DENIED -> "已拒绝"
    }

    fun yesNo(value: Boolean): String = if (value) "是" else "否"

    fun transportHealthLabel(facts: UsbPlaybackFacts): String = when {
        facts.activeMode == null -> "待机"
        facts.telemetry?.isoErrorCount?.let { it > 0L } == true -> "ISO 错误"
        facts.telemetry?.pendingOutputUrbs?.let { it > 0L } == true -> "稳定"
        else -> "活动"
    }

    fun lines(facts: UsbPlaybackFacts): List<String> = buildList {
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
        if (facts.requestedMode == UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL ||
            facts.activeMode == UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL
        ) {
            add("Native DSD：实验；framing 尚未重新资格化，使用硬件音量")
        }
    }

    fun modeLabel(mode: UsbExclusiveMode): String = when (mode) {
        UsbExclusiveMode.SHARED_PCM -> "Shared PCM"
        UsbExclusiveMode.USB_EXACT_PCM -> "USB Exact PCM"
        UsbExclusiveMode.USB_DOP -> "USB DoP"
        UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL -> "USB Native DSD（实验）"
    }

    fun preferenceModeLabel(mode: UsbHybridOutputMode): String = when (mode) {
        UsbHybridOutputMode.SharedPcm -> "Shared PCM"
        UsbHybridOutputMode.ExactPcm -> "USB Exact PCM"
        UsbHybridOutputMode.Dop -> "USB DoP"
        UsbHybridOutputMode.NativeDsdExperimental -> "USB Native DSD（实验）"
    }
}
