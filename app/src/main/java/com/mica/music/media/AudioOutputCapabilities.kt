package com.mica.music.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.mica.music.util.DiagnosticLog

internal data class AudioRouteSnapshot(
    val deviceType: Int?,
    val deviceName: String,
    val bluetooth: Boolean,
    val usb: Boolean,
)

internal object SoftwareAudioRouteState {
    @Volatile
    private var routedDevice: AudioDeviceInfo? = null

    fun update(device: AudioDeviceInfo?) {
        routedDevice = device
    }

    fun current(): AudioDeviceInfo? = routedDevice
}

internal object AudioOutputCapabilities {
    private val mediaAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    fun route(context: Context): AudioRouteSnapshot {
        val manager = context.getSystemService(AudioManager::class.java)
        val device = SoftwareAudioRouteState.current()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    manager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()
                }.getOrNull()
            } else {
                null
            }
        return snapshot(device)
    }

    fun supports(context: Context, format: AlacPcmFormat): Boolean {
        if (format.bitsPerSample > 16 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        val audioFormat = format.toAudioFormat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val support = AudioManager.getDirectPlaybackSupport(audioFormat, mediaAttributes)
            DiagnosticLog.event(
                "AudioCapability",
                "direct format=${format.describe()} support=$support route=${route(context).deviceName}",
            )
            return support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
        return canInitializeAudioTrack(audioFormat, format)
    }

    fun snapshot(device: AudioDeviceInfo?): AudioRouteSnapshot {
        val type = device?.type
        return AudioRouteSnapshot(
            deviceType = type,
            deviceName = device?.productName?.toString()?.takeIf(String::isNotBlank)
                ?: type?.let { "type-$it" }
                ?: "unknown",
            bluetooth = type != null && isBluetooth(type),
            usb = type != null && isUsb(type),
        )
    }

    fun isBluetooth(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    fun isUsb(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun canInitializeAudioTrack(
        audioFormat: AudioFormat,
        format: AlacPcmFormat,
    ): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            format.channelMask,
            format.audioTrackEncoding,
        )
        if (minBuffer <= 0) return false
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(mediaAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }.getOrNull() ?: return false
        return try {
            track.state == AudioTrack.STATE_INITIALIZED
        } finally {
            runCatching { track.release() }
        }
    }

    private fun AlacPcmFormat.toAudioFormat(): AudioFormat =
        AudioFormat.Builder()
            .setSampleRate(sampleRateHz)
            .setEncoding(audioTrackEncoding)
            .setChannelMask(channelMask)
            .build()

    private fun AlacPcmFormat.describe(): String =
        "${bitsPerSample}bit/${sampleRateHz}Hz/ch$channelCount"
}
