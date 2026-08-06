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

internal object AudioOutputCapabilities {
    /** Android [AudioFormat] / probe ladder upper bound; DSD native rates (MHz) are excluded. */
    internal const val MAX_PROBE_SAMPLE_RATE_HZ = 768_000

    internal fun isProbeableSampleRate(sampleRateHz: Int): Boolean =
        sampleRateHz in 1..MAX_PROBE_SAMPLE_RATE_HZ

    private val mediaAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    fun route(context: Context): AudioRouteSnapshot {
        val manager = context.getSystemService(AudioManager::class.java) ?: return snapshot(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = runCatching {
                manager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()
            }.getOrNull()
            if (device != null) return snapshot(device)
        }
        return inferRouteFromOutputs(manager)
    }

    /**
     * Best-effort active media output when [AudioManager.getAudioDevicesForAttributes] is unavailable.
     * Priority: USB > BT A2DP > other BT > wired > speaker.
     */
    internal fun inferRouteFromOutputs(manager: AudioManager): AudioRouteSnapshot {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferred = outputs.firstOrNull { isUsb(it.type) }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { isBluetooth(it.type) }
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull()
        return snapshot(preferred)
    }

    fun supports(context: Context, format: AlacPcmFormat): Boolean {
        val supported = queryIntSupport(context, format)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DiagnosticLog.event(
                "AudioCapability",
                "direct format=${format.describe()} support=${queryDirectSupportLevel(format)} " +
                    "route=${route(context).deviceName}",
            )
        }
        return supported
    }

    fun queryIntSupport(context: Context, format: AlacPcmFormat): Boolean {
        if (!isProbeableSampleRate(format.sampleRateHz)) return false
        if (format.bitsPerSample > 16 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        val audioFormat = format.toAudioFormat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return queryDirectSupportLevel(format) != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
        return canInitializeAudioTrack(audioFormat, format)
    }

    fun queryFloatSupport(
        context: Context,
        sampleRateHz: Int,
        channelCount: Int,
    ): Boolean {
        if (!isProbeableSampleRate(sampleRateHz) || channelCount <= 0) return false
        val channelMask = channelMask(channelCount)
        val audioFormat = runCatching {
            AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(channelMask)
                .build()
        }.getOrNull() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return AudioManager.getDirectPlaybackSupport(audioFormat, mediaAttributes) !=
                AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
        return canInitializeAudioTrack(audioFormat, sampleRateHz, channelMask)
    }

    fun queryDirectSupportLevel(format: AlacPcmFormat): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
        }
        return AudioManager.getDirectPlaybackSupport(format.toAudioFormat(), mediaAttributes)
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
        return canInitializeAudioTrack(audioFormat, format.sampleRateHz, format.channelMask)
    }

    private fun canInitializeAudioTrack(
        audioFormat: AudioFormat,
        sampleRateHz: Int,
        channelMask: Int,
    ): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            channelMask,
            audioFormat.encoding,
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

    private fun channelMask(channelCount: Int): Int =
        if (channelCount.coerceIn(1, 2) == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
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
