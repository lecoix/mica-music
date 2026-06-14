package com.mica.music.util

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 记录蓝牙 / 有线等输出设备变化，便于对照「耳机断连」报告。
 */
object BluetoothAudioDiagnostics {
    private val lock = Any()
    private var audioManager: AudioManager? = null

    fun install(context: Context) {
        synchronized(lock) {
            if (audioManager != null) return
            val manager = context.applicationContext
                .getSystemService(AudioManager::class.java) ?: return
            audioManager = manager
            logRoute("install")
            manager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                        logDevices("added", addedDevices)
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                        logDevices("removed", removedDevices)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    fun logPlaybackRoute(reason: String, extra: String = "") {
        logRoute(reason, extra)
    }

    private fun logRoute(reason: String, extra: String = "") {
        val manager = synchronized(lock) { audioManager } ?: return
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString { describeDevice(it) }
        val message = buildString {
            append("reason=$reason")
            if (extra.isNotBlank()) {
                append("; ")
                append(extra)
            }
            append("; musicActive=${manager.isMusicActive}")
            append("; outputs=[$outputs]")
        }
        DiagnosticLog.event("AudioRoute", message)
    }

    private fun logDevices(event: String, devices: Array<out AudioDeviceInfo>) {
        if (devices.isEmpty()) return
        val names = devices.joinToString { describeDevice(it) }
        DiagnosticLog.event("AudioRoute", "devices-$event: $names")
    }

    private fun describeDevice(device: AudioDeviceInfo): String =
        buildString {
            append(deviceTypeLabel(device.type))
            append(':')
            append(device.productName?.toString()?.trim().orEmpty().ifBlank { "unknown" })
            if (device.isSink) append("(sink)")
        }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HS"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HS"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        else -> "type-$type"
    }
}
