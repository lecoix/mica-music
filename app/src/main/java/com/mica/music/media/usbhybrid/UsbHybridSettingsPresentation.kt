package com.mica.music.media.usbhybrid

object UsbHybridSettingsPresentation {
    fun lines(facts: UsbPlaybackFacts): List<String> = buildList {
        add("请求：${modeLabel(facts.requestedMode)}")
        add("状态：${facts.activeMode?.let { "ACTIVE · ${modeLabel(it)}" } ?: "未激活"}")
        add("权限：${permissionLabel(facts.permission)}")
        add("已 claim：${yesNo(facts.claimed)}")
        add("独占：${yesNo(facts.exclusive)}")
        add("传输保持：${yesNo(facts.transportExact)}")
        add("信号保持：${yesNo(facts.signalExact)}")
        if (facts.sampleRate != null && facts.channels != null) {
            add("实际格式：${facts.sampleRate} Hz · ${facts.channels} ch · USB ${facts.usbBitResolution ?: "?"} bit")
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

    private fun permissionLabel(state: PermissionState): String = when (state) {
        PermissionState.NOT_REQUIRED -> "无需授权"
        PermissionState.REQUESTED -> "等待授权"
        PermissionState.GRANTED -> "已授权"
        PermissionState.DENIED -> "已拒绝"
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}
