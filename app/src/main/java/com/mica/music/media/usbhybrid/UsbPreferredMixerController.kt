package com.mica.music.media.usbhybrid

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build

internal fun supportsAttributedAudioDeviceQuery(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

internal fun supportsPreferredMixerAttributes(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

internal data class UsbPreferredMixerDevice(
    val id: Int,
    val name: String,
    val type: String,
    val sampleRates: List<Int>,
    val encodings: List<String>,
    val channelCounts: List<Int>,
    val supportedMixerSampleRates: List<Int>,
    val supportsBitPerfectMixer: Boolean,
)

internal data class UsbPreferredMixerStatus(
    val supported: Boolean,
    val androidSdk: Int,
    val activeDeviceId: Int?,
    val preferredApplied: Boolean,
    val preferredSampleRate: Int?,
    val preferredEncoding: String?,
    val preferredBitPerfect: Boolean,
    val outputDeviceName: String?,
    val outputSampleRate: Int?,
    val outputEncoding: String?,
    val message: String,
    val devices: List<UsbPreferredMixerDevice>,
)

private data class PreferredMixerSnapshot(
    val sampleRate: Int,
    val encoding: Int,
    val bitPerfect: Boolean,
)

/** Android shared-path preferred mixer controller adapted from the reference MainActivity bridge. */
internal class UsbPreferredMixerController(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun status(message: String? = null, preferredApplied: Boolean = false): UsbPreferredMixerStatus {
        val devices = getUsbAudioDevices()
        val activeDevice = getActiveUsbAudioDevice(devices)
        val outputDevice = activeDevice ?: getActiveOutputDevice()
        val preferred = if (supportsPreferredMixerAttributes(Build.VERSION.SDK_INT) && activeDevice != null) {
            getPreferredMixerSnapshotApi34(activeDevice)
        } else {
            null
        }
        return UsbPreferredMixerStatus(
            supported = devices.isNotEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            activeDeviceId = activeDevice?.id,
            preferredApplied = preferredApplied,
            preferredSampleRate = preferred?.sampleRate,
            preferredEncoding = preferred?.encoding?.let(::encodingName),
            preferredBitPerfect = preferred?.bitPerfect == true,
            outputDeviceName = outputDevice?.productName?.toString(),
            outputSampleRate = outputSampleRate(outputDevice),
            outputEncoding = outputEncoding(outputDevice),
            message = message ?: defaultStatusMessage(devices),
            devices = devices.map(::toStatus),
        )
    }

    fun applyBitPerfect(requestedSampleRate: Int? = null, requestedDeviceId: Int? = null): UsbPreferredMixerStatus {
        val devices = getUsbAudioDevices()
        val device = findRequestedDevice(devices, requestedDeviceId)
            ?: return status("No USB audio output device detected.")
        if (!supportsPreferredMixerAttributes(Build.VERSION.SDK_INT)) {
            return status("USB mixer attributes require Android 14 or newer.")
        }
        return applyPreferredOutputApi34(device, requestedSampleRate)
    }

    fun clear(requestedDeviceId: Int? = null): UsbPreferredMixerStatus {
        val devices = getUsbAudioDevices()
        val device = findRequestedDevice(devices, requestedDeviceId)
            ?: return status("No USB audio output device detected.")
        if (!supportsPreferredMixerAttributes(Build.VERSION.SDK_INT)) {
            return status("USB mixer attributes require Android 14 or newer.")
        }
        val cleared = clearPreferredOutputApi34(device)
        return status(
            preferredApplied = false,
            message = if (cleared) "Cleared preferred USB mixer attributes." else "No preferred USB mixer attributes were cleared.",
        )
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyPreferredOutputApi34(
        device: AudioDeviceInfo,
        requestedSampleRate: Int?,
    ): UsbPreferredMixerStatus {
        if (requestedSampleRate != null && !isValidMixerSampleRate(requestedSampleRate)) {
            return status("Skipped preferred USB mixer attributes: Android rejected sample rate $requestedSampleRate.")
        }
        return try {
            val supported = audioManager.getSupportedMixerAttributes(device)
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val chosenRate = chooseBitPerfectMixerSampleRate(
                requestedSampleRate,
                supported.map { it.format.sampleRate },
            )
            val chosen = chosenRate?.let { rate -> supported.firstOrNull { it.format.sampleRate == rate } }
            if (chosen != null) {
                val applied = audioManager.setPreferredMixerAttributes(mediaAudioAttributes(), device, chosen)
                return status(
                    preferredApplied = applied,
                    message = if (applied) "Applied bit-perfect USB mixer attributes." else "Device rejected bit-perfect USB mixer attributes.",
                )
            }
            status("Device has no matching bit-perfect mixer attributes.")
        } catch (error: RuntimeException) {
            status("Failed to apply USB mixer attributes: ${error.message}")
        }
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearPreferredOutputApi34(device: AudioDeviceInfo): Boolean = try {
        audioManager.clearPreferredMixerAttributes(mediaAudioAttributes(), device)
    } catch (_: RuntimeException) {
        false
    }

    private fun getUsbAudioDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isUsbAudioOutput() }

    private fun getActiveUsbAudioDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        if (!supportsAttributedAudioDeviceQuery(Build.VERSION.SDK_INT)) return null
        val activeDevices = getAudioDevicesForAttributesApi33()
        return activeDevices.firstOrNull { active -> devices.any { it.id == active.id } }
    }

    private fun getActiveOutputDevice(): AudioDeviceInfo? {
        if (supportsAttributedAudioDeviceQuery(Build.VERSION.SDK_INT)) {
            getAudioDevicesForAttributesApi33().firstOrNull()?.let { return it }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
    }

    private fun outputSampleRate(device: AudioDeviceInfo?): Int? =
        chooseStableSampleRate(device?.sampleRates?.toList().orEmpty())
            ?: AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 }

    private fun outputEncoding(device: AudioDeviceInfo?): String? =
        device?.encodings?.firstOrNull { it == AudioFormat.ENCODING_PCM_16BIT }?.let(::encodingName)
            ?: device?.encodings?.firstOrNull()?.let(::encodingName)

    private fun findRequestedDevice(devices: List<AudioDeviceInfo>, requestedDeviceId: Int?): AudioDeviceInfo? {
        if (requestedDeviceId != null) return devices.firstOrNull { it.id == requestedDeviceId }
        return getActiveUsbAudioDevice(devices) ?: devices.firstOrNull()
    }

    private fun chooseBitPerfectMixerSampleRate(requestedSampleRate: Int?, supportedSampleRates: List<Int>): Int? {
        val validRates = supportedSampleRates.filter { it > 0 }
        return if (requestedSampleRate != null) validRates.firstOrNull { it == requestedSampleRate } else validRates.maxOrNull()
    }

    private fun chooseStableSampleRate(rates: List<Int>): Int? {
        if (rates.isEmpty()) return null
        val validRates = rates.filter(::isValidMixerSampleRate).toSet()
        for (rate in listOf(48_000, 44_100, 96_000, 88_200, 192_000, 176_400)) {
            if (rate in validRates) return rate
        }
        return validRates.maxOrNull()
    }

    private fun isValidMixerSampleRate(sampleRate: Int): Boolean = sampleRate in 4_000..192_000

    private fun mediaAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun defaultStatusMessage(devices: List<AudioDeviceInfo>): String = when {
        devices.isEmpty() -> "No USB audio output device detected."
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "USB audio device detected. Preferred mixer attributes require Android 14 or newer."
        else -> "USB audio device detected."
    }

    private fun AudioDeviceInfo.isUsbAudioOutput(): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun toStatus(device: AudioDeviceInfo): UsbPreferredMixerDevice = UsbPreferredMixerDevice(
        id = device.id,
        name = device.productName.toString(),
        type = audioDeviceTypeName(device.type),
        sampleRates = device.sampleRates.toList(),
        encodings = device.encodings.map(::encodingName),
        channelCounts = device.channelCounts.toList(),
        supportedMixerSampleRates = if (supportsPreferredMixerAttributes(Build.VERSION.SDK_INT)) getSupportedMixerSampleRates(device) else emptyList(),
        supportsBitPerfectMixer = if (supportsPreferredMixerAttributes(Build.VERSION.SDK_INT)) supportsBitPerfectMixer(device) else false,
    )

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun getAudioDevicesForAttributesApi33(): List<AudioDeviceInfo> =
        audioManager.getAudioDevicesForAttributes(mediaAudioAttributes())

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getSupportedMixerSampleRates(device: AudioDeviceInfo): List<Int> = try {
        audioManager.getSupportedMixerAttributes(device).map { it.format.sampleRate }.filter { it > 0 }.distinct().sorted()
    } catch (_: RuntimeException) {
        emptyList()
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun supportsBitPerfectMixer(device: AudioDeviceInfo): Boolean = try {
        audioManager.getSupportedMixerAttributes(device).any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
    } catch (_: RuntimeException) {
        false
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun getPreferredMixerSnapshotApi34(device: AudioDeviceInfo): PreferredMixerSnapshot? = try {
        audioManager.getPreferredMixerAttributes(mediaAudioAttributes(), device)?.let { preferred ->
            PreferredMixerSnapshot(
                sampleRate = preferred.format.sampleRate,
                encoding = preferred.format.encoding,
                bitPerfect = preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            )
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        else -> "unknown"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "pcm_8bit"
        AudioFormat.ENCODING_PCM_16BIT -> "pcm_16bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_24bit_packed"
        AudioFormat.ENCODING_PCM_32BIT -> "pcm_32bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "pcm_float"
        else -> "encoding_$encoding"
    }
}
