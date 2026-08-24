package com.afalphy.sylvakru

import android.hardware.usb.UsbConstants
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val USB_RECIP_INTERFACE = 0x01
private const val UNITY_GAIN_Q16 = 65536

internal fun isUsbVolumeControlEngaged(
    active: Boolean,
    hardwareVolumeActive: Boolean,
    hardwareVolumeSyncPending: Boolean,
    digitalVolumeActive: Boolean,
    bitDepth: Int?,
): Boolean =
    active &&
        (hardwareVolumeActive ||
            hardwareVolumeSyncPending ||
            (digitalVolumeActive && bitDepth != 1))

internal data class HardwareVolumeFeature(
    val protocol: String,
    val controlInterface: Int,
    val unitId: Int,
    val sourceId: Int,
    val channel: Int,
    val writable: Boolean,
    val recipient: String = "interface",
) {
    fun description(): String =
        "interface=$controlInterface/unit=$unitId/source=$sourceId/channel=$channel/" +
            "volume=${if (writable) "read-write" else "read-only"}/protocol=$protocol/" +
            "recipient=$recipient"
}

internal data class HardwareVolumeRange(
    val minQ8_8: Int,
    val maxQ8_8: Int,
    val stepQ8_8: Int,
    val muteQ8_8: Int = Short.MIN_VALUE.toInt(),
)

internal fun hardwareVolumeRequestType(direction: Int, recipient: String): Int =
    direction or UsbConstants.USB_TYPE_CLASS or
        if (recipient == "device") 0 else USB_RECIP_INTERFACE

internal fun hardwareVolumeRequiresInterfaceClaim(recipient: String): Boolean =
    recipient != "device"

internal fun hardwareVolumeRequiresDedicatedConnection(recipient: String): Boolean =
    recipient == "device"

internal fun hardwareVolumeRangeOverride(quirk: DacQuirk): HardwareVolumeRange? {
    val min = quirk.hardwareVolumeMinQ8_8 ?: return null
    val max = quirk.hardwareVolumeMaxQ8_8 ?: return null
    val step = quirk.hardwareVolumeStepQ8_8 ?: return null
    return HardwareVolumeRange(
        minQ8_8 = min,
        maxQ8_8 = max,
        stepQ8_8 = step,
        muteQ8_8 = quirk.hardwareVolumeMuteQ8_8 ?: Short.MIN_VALUE.toInt(),
    ).takeIf { min <= max && step > 0 }
}

internal fun uniformHardwareVolumeRange(
    ranges: List<HardwareVolumeRange>,
    expectedCount: Int,
): HardwareVolumeRange? = ranges.firstOrNull()?.takeIf {
    ranges.size == expectedCount && ranges.all { range -> range == it }
}

internal fun selectHardwareVolumeFeatures(
    features: List<HardwareVolumeFeature>,
    terminalLink: Int?,
    outputTerminalSources: Set<Int>,
    quirk: DacQuirk,
): List<HardwareVolumeFeature>? {
    if (quirk.hardwareVolumeEnabled == false) {
        return null
    }
    val featureUnitId = quirk.hardwareVolumeFeatureUnitId
    val controlInterface = quirk.hardwareVolumeControlInterface
    if (featureUnitId != null && controlInterface != null) {
        val matching = features.filter {
            it.unitId == featureUnitId && it.controlInterface == controlInterface
        }
        val channels = quirk.hardwareVolumeChannels
        val selected = if (channels.isEmpty()) {
            matching.firstOrNull { it.channel == 0 }?.let(::listOf)
                ?: matching.sortedBy { it.channel }
        } else {
            channels.mapNotNull { channel -> matching.firstOrNull { it.channel == channel } }
        }
        return selected.takeIf {
            it.isNotEmpty() && (channels.isEmpty() || it.size == channels.size)
        }
    }

    val linkedTerminal = terminalLink ?: return null
    val candidates = features
        .filter {
            it.writable &&
                it.sourceId == linkedTerminal &&
                it.unitId in outputTerminalSources
        }
        .groupBy { Triple(it.protocol, it.controlInterface, it.unitId) }
    if (candidates.size != 1) {
        return null
    }
    val group = candidates.values.single()
    return group.firstOrNull { it.channel == 0 }?.let(::listOf)
        ?: group.sortedBy { it.channel }
}

internal fun hardwareVolumeQ8_8(gainQ16: Int, range: HardwareVolumeRange): Int {
    if (gainQ16 <= 0) {
        return range.muteQ8_8
    }
    val gain = gainQ16.coerceAtMost(UNITY_GAIN_Q16).toDouble() / UNITY_GAIN_Q16
    val raw = (20.0 * log10(gain) * 256.0).roundToInt()
        .coerceIn(range.minQ8_8, range.maxQ8_8)
    if (range.stepQ8_8 <= 0) {
        return raw
    }
    val steps = ((raw - range.minQ8_8).toDouble() / range.stepQ8_8).roundToInt()
    return (range.minQ8_8 + steps * range.stepQ8_8)
        .coerceIn(range.minQ8_8, range.maxQ8_8)
}

internal fun hardwareVolumeGainQ16(valueQ8_8: Int, muteQ8_8: Int): Int {
    if (valueQ8_8 <= muteQ8_8 || valueQ8_8 == Int.MIN_VALUE) return 0
    val gain = 10.0.pow(valueQ8_8.toDouble() / (20.0 * 256.0)) * UNITY_GAIN_Q16
    return when {
        !gain.isFinite() || gain >= UNITY_GAIN_Q16 -> UNITY_GAIN_Q16
        gain <= 0 -> 0
        else -> gain.roundToInt()
    }
}

internal fun hardwareVolumeReadbackMatches(targetQ8_8: Int, actualQ8_8: Int, stepQ8_8: Int): Boolean {
    if (targetQ8_8 == Short.MIN_VALUE.toInt()) {
        return actualQ8_8 == targetQ8_8
    }
    return kotlin.math.abs(actualQ8_8 - targetQ8_8) <= stepQ8_8.coerceAtLeast(1)
}
