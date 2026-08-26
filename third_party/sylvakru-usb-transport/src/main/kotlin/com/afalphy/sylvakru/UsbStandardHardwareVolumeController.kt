/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
package com.afalphy.sylvakru

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

internal data class HardwareVolumeControl(
    val features: List<HardwareVolumeFeature>,
    val range: HardwareVolumeRange,
    val source: String,
)

/**
 * Standard UAC Feature Unit hardware-volume path extracted from the reference UsbExclusiveAudioEngine.
 * The selection, range probing, SET_CUR/readback verification and rollback rules intentionally match
 * the reference implementation.
 */
internal class UsbStandardHardwareVolumeController(
    private val context: Context,
) {
    fun collectDiagnostics(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        descriptors: ByteArray?,
    ): Map<String, Any?> {
        val features = parseHardwareVolumeFeatures(descriptors).toMutableList()
        val quirk = UsbDacQuirks.forDevice(context, device.vendorId, device.productId)
        val quirkOverride = buildMap<String, Any> {
            quirk.hardwareVolumeFeatureUnitId?.let { put("featureUnitId", it) }
            quirk.hardwareVolumeControlInterface?.let { put("controlInterface", it) }
            if (quirk.hardwareVolumeChannels.isNotEmpty()) put("channels", quirk.hardwareVolumeChannels)
            quirk.hardwareVolumeProtocol?.let { put("protocol", it) }
            put("recipient", quirk.hardwareVolumeRecipient)
            hardwareVolumeRangeOverride(quirk)?.let {
                put("range", mapOf(
                    "minQ8_8Db" to it.minQ8_8,
                    "maxQ8_8Db" to it.maxQ8_8,
                    "stepQ8_8Db" to it.stepQ8_8,
                    "muteQ8_8Db" to it.muteQ8_8,
                ))
            }
            quirk.hardwareVolumeEnabled?.let { put("enabled", it) }
            quirk.hardwareVolumeDsdSupported?.let { put("dsdSupported", it) }
        }
        val quirkUnitId = quirk.hardwareVolumeFeatureUnitId
        val quirkInterface = quirk.hardwareVolumeControlInterface
        if (
            quirkUnitId != null && quirkInterface != null &&
            features.none { it.unitId == quirkUnitId && it.controlInterface == quirkInterface }
        ) {
            val protocol = quirk.hardwareVolumeProtocol
                ?.takeIf { it == "uac1" || it == "uac2" }
                ?: if (findAudioControlInterface(device, quirkInterface)?.interfaceProtocol == 0x20) "uac2" else "uac1"
            features += quirk.hardwareVolumeChannels.ifEmpty { listOf(0) }.map { channel ->
                HardwareVolumeFeature(
                    protocol = protocol,
                    controlInterface = quirkInterface,
                    unitId = quirkUnitId,
                    sourceId = -1,
                    channel = channel,
                    writable = true,
                    recipient = quirk.hardwareVolumeRecipient,
                )
            }
        }
        if (features.isEmpty()) {
            return mapOf(
                "available" to false,
                "featureUnits" to emptyList<String>(),
                "quirkOverride" to quirkOverride,
            )
        }
        val overrideRange = hardwareVolumeRangeOverride(quirk)
        val probes = features.groupBy { Triple(it.protocol, it.controlInterface, it.unitId) }
            .values.flatMap { group ->
                val feature = group.first()
                val controlInterface = findAudioControlInterface(device, feature.controlInterface)
                val requiresClaim = hardwareVolumeRequiresInterfaceClaim(feature.recipient)
                val claimed = !requiresClaim || controlInterface?.let {
                    runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
                } == true
                if (!claimed) {
                    listOf(mapOf(
                        "protocol" to feature.protocol,
                        "controlInterface" to feature.controlInterface,
                        "featureUnitId" to feature.unitId,
                        "error" to "Failed to claim AudioControl interface.",
                    ))
                } else {
                    try {
                        group.sortedBy { it.channel }.map { readHardwareVolumeProbe(connection, it, overrideRange) }
                    } finally {
                        if (requiresClaim) runCatching { connection.releaseInterface(controlInterface!!) }
                    }
                }
            }
        return mapOf(
            "available" to true,
            "featureUnits" to features.map { it.description() },
            "probes" to probes,
            "quirkOverride" to quirkOverride,
        )
    }
    fun resolve(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        target: UsbStreamingTarget,
        quirk: DacQuirk,
    ): HardwareVolumeControl? {
        val descriptors = connection.rawDescriptors
        val parsed = parseHardwareVolumeFeatures(descriptors).toMutableList()
        val quirkUnitId = quirk.hardwareVolumeFeatureUnitId
        val quirkInterface = quirk.hardwareVolumeControlInterface
        if (quirkUnitId != null && quirkInterface != null) {
            val protocol = quirk.hardwareVolumeProtocol
                ?.takeIf { it == "uac1" || it == "uac2" } ?: if (
                findAudioControlInterface(device, quirkInterface)?.interfaceProtocol == 0x20
            ) {
                "uac2"
            } else {
                "uac1"
            }
            val matching = parsed.filter {
                it.unitId == quirkUnitId && it.controlInterface == quirkInterface
            }
            val channels = quirk.hardwareVolumeChannels.ifEmpty {
                if (matching.isEmpty()) listOf(0) else emptyList()
            }
            parsed += channels.filter { channel ->
                matching.none { it.channel == channel }
            }.map { channel ->
                HardwareVolumeFeature(
                    protocol = protocol,
                    controlInterface = quirkInterface,
                    unitId = quirkUnitId,
                    sourceId = target.formatInfo?.terminalLink ?: -1,
                    channel = channel,
                    writable = true,
                    recipient = quirk.hardwareVolumeRecipient,
                )
            }
        }
        val selected = selectHardwareVolumeFeatures(
            features = parsed,
            terminalLink = target.formatInfo?.terminalLink,
            outputTerminalSources = parseOutputTerminalSources(descriptors),
            quirk = quirk,
        ) ?: run {
            UsbDiagnostics.w(TAG, "hardware volume resolve failed: no unique feature selected.")
            return null
        }
        if (quirkUnitId == null && parsed.map { it.controlInterface }.distinct().size != 1) {
            UsbDiagnostics.w(TAG, "hardware volume resolve failed: ambiguous control interfaces.")
            return null
        }
        val controlInterface = findAudioControlInterface(device, selected.first().controlInterface)
            ?: run {
                UsbDiagnostics.w(
                    TAG,
                    "hardware volume resolve failed: control interface ${selected.first().controlInterface} is unavailable.",
                )
                return null
            }
        val dedicatedConnection = hardwareVolumeRequiresDedicatedConnection(selected.first().recipient)
        val transferConnection = if (dedicatedConnection) {
            context.getSystemService(UsbManager::class.java).openDevice(device)
        } else {
            connection
        } ?: run {
            UsbDiagnostics.w(TAG, "hardware volume resolve failed: dedicated connection is unavailable.")
            return null
        }
        val requiresClaim = hardwareVolumeRequiresInterfaceClaim(selected.first().recipient)
        val claimResult = if (requiresClaim) {
            runCatching { transferConnection.claimInterface(controlInterface, true) }
        } else {
            Result.success(true)
        }
        if (!claimResult.getOrDefault(false)) {
            UsbDiagnostics.w(
                TAG,
                "hardware volume resolve failed: claim interface ${controlInterface.id} failed" +
                    (claimResult.exceptionOrNull()?.let { ": ${it.message}" } ?: "."),
            )
            if (dedicatedConnection) runCatching { transferConnection.close() }
            return null
        }
        return try {
            val overrideRange = hardwareVolumeRangeOverride(quirk)
            val ranges = selected.mapNotNull {
                overrideRange ?: readHardwareVolumeRangeValue(transferConnection, it)
            }
            val range = uniformHardwareVolumeRange(ranges, selected.size)
            if (range == null) {
                null
            } else if (
                overrideRange != null && selected.any { readHardwareVolumeCurrent(transferConnection, it) == null }
            ) {
                UsbDiagnostics.w(
                    TAG,
                    "hardware volume resolve failed: GET_CUR verification failed for ${selected.map { it.description() }}.",
                )
                null
            } else {
                HardwareVolumeControl(
                    features = selected,
                    range = range,
                    source = if (quirkUnitId != null && quirkInterface != null) "quirk" else "descriptor",
                )
            }
        } finally {
            if (requiresClaim) runCatching { transferConnection.releaseInterface(controlInterface) }
            if (dedicatedConnection) runCatching { transferConnection.close() }
        }
    }

    fun readValues(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        control: HardwareVolumeControl,
    ): List<Int>? {
        val controlInterface = findAudioControlInterface(device, control.features.first().controlInterface) ?: return null
        val dedicated = hardwareVolumeRequiresDedicatedConnection(control.features.first().recipient)
        val transferConnection = if (dedicated) {
            context.getSystemService(UsbManager::class.java).openDevice(device)
        } else {
            connection
        } ?: return null
        val requiresClaim = hardwareVolumeRequiresInterfaceClaim(control.features.first().recipient)
        val claimed = !requiresClaim ||
            runCatching { transferConnection.claimInterface(controlInterface, true) }.getOrDefault(false)
        if (!claimed) {
            if (dedicated) transferConnection.close()
            return null
        }
        return try {
            control.features.map { readHardwareVolumeCurrent(transferConnection, it) }
                .takeIf { values -> values.all { it != null } }
                ?.filterNotNull()
        } finally {
            if (requiresClaim) runCatching { transferConnection.releaseInterface(controlInterface) }
            if (dedicated) runCatching { transferConnection.close() }
        }
    }

    fun write(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        control: HardwareVolumeControl,
        gainQ16: Int,
        generationMatches: () -> Boolean,
    ): HardwareVolumeWriteResult {
        val controlInterface = findAudioControlInterface(device, control.features.first().controlInterface)
            ?: return HardwareVolumeWriteResult(error = "AudioControl interface is unavailable.")
        val dedicatedConnection = hardwareVolumeRequiresDedicatedConnection(control.features.first().recipient)
        val transferConnection = if (dedicatedConnection) {
            context.getSystemService(UsbManager::class.java).openDevice(device)
        } else {
            connection
        } ?: return HardwareVolumeWriteResult(error = "Dedicated hardware volume connection is unavailable.")
        val requiresClaim = hardwareVolumeRequiresInterfaceClaim(control.features.first().recipient)
        val claimed = !requiresClaim ||
            runCatching { transferConnection.claimInterface(controlInterface, true) }.getOrDefault(false)
        if (!claimed) {
            if (dedicatedConnection) runCatching { transferConnection.close() }
            return HardwareVolumeWriteResult(error = "Failed to claim the AudioControl interface.")
        }
        val previous = mutableMapOf<HardwareVolumeFeature, Int>()
        val written = mutableListOf<HardwareVolumeFeature>()
        val readBackValues = mutableListOf<Int>()
        val targetQ8_8 = hardwareVolumeQ8_8(gainQ16, control.range)
        return try {
            for (feature in control.features) {
                val current = readHardwareVolumeCurrent(transferConnection, feature)
                if (current == null) {
                    return HardwareVolumeWriteResult(error = "Failed to read hardware volume channel ${feature.channel}.")
                }
                previous[feature] = current
            }
            synchronized(writeLock) {
                if (!generationMatches()) {
                    throw java.util.concurrent.CancellationException(
                        "USB volume write cancelled because the session changed.",
                    )
                }
                for (feature in control.features) {
                    if (!writeHardwareVolumeValue(transferConnection, feature, targetQ8_8)) {
                        rollbackHardwareVolume(transferConnection, written, previous)
                        return@synchronized HardwareVolumeWriteResult(
                            error = "Failed to set hardware volume channel ${feature.channel}.",
                        )
                    }
                    written += feature
                    val readBack = readHardwareVolumeCurrent(transferConnection, feature)
                    if (
                        readBack == null ||
                        !hardwareVolumeReadbackMatches(targetQ8_8, readBack, control.range.stepQ8_8)
                    ) {
                        rollbackHardwareVolume(transferConnection, written, previous)
                        return@synchronized HardwareVolumeWriteResult(
                            error = "Hardware volume readback mismatch on channel ${feature.channel}: " +
                                "targetQ8_8=$targetQ8_8, actualQ8_8=${readBack ?: "unavailable"}.",
                        )
                    }
                    readBackValues += readBack
                }
                val actual = actualHardwareVolume(readBackValues, control.range.muteQ8_8)
                    ?: return@synchronized HardwareVolumeWriteResult(
                        error = "Hardware volume readback is unavailable.",
                    )
                UsbDiagnostics.i(
                    TAG,
                    "hardware volume SET_CUR targetQ8_8=$targetQ8_8, actualQ8_8=${actual.raw}, " +
                        "channels=${control.features.map { it.channel }}, " +
                        "recipient=${control.features.first().recipient}, source=${control.source}",
                )
                HardwareVolumeWriteResult(actual = actual)
            }
        } finally {
            if (requiresClaim) runCatching { transferConnection.releaseInterface(controlInterface) }
            if (dedicatedConnection) runCatching { transferConnection.close() }
        }
    }

    fun diagnostics(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        quirk: DacQuirk,
    ): Map<String, Any?> {
        val descriptors = connection.rawDescriptors
        val features = parseHardwareVolumeFeatures(descriptors).toMutableList()
        val quirkOverride = buildMap<String, Any> {
            quirk.hardwareVolumeFeatureUnitId?.let { put("featureUnitId", it) }
            quirk.hardwareVolumeControlInterface?.let { put("controlInterface", it) }
            if (quirk.hardwareVolumeChannels.isNotEmpty()) put("channels", quirk.hardwareVolumeChannels)
            quirk.hardwareVolumeProtocol?.let { put("protocol", it) }
            put("recipient", quirk.hardwareVolumeRecipient)
            hardwareVolumeRangeOverride(quirk)?.let {
                put(
                    "range",
                    mapOf(
                        "minQ8_8Db" to it.minQ8_8,
                        "maxQ8_8Db" to it.maxQ8_8,
                        "stepQ8_8Db" to it.stepQ8_8,
                        "muteQ8_8Db" to it.muteQ8_8,
                    ),
                )
            }
            quirk.hardwareVolumeEnabled?.let { put("enabled", it) }
            quirk.hardwareVolumeDsdSupported?.let { put("dsdSupported", it) }
        }
        val quirkUnitId = quirk.hardwareVolumeFeatureUnitId
        val quirkInterface = quirk.hardwareVolumeControlInterface
        if (
            quirkUnitId != null && quirkInterface != null &&
            features.none { it.unitId == quirkUnitId && it.controlInterface == quirkInterface }
        ) {
            val protocol = quirk.hardwareVolumeProtocol
                ?.takeIf { it == "uac1" || it == "uac2" } ?: if (
                findAudioControlInterface(device, quirkInterface)?.interfaceProtocol == 0x20
            ) "uac2" else "uac1"
            features += quirk.hardwareVolumeChannels.ifEmpty { listOf(0) }.map { channel ->
                HardwareVolumeFeature(
                    protocol = protocol,
                    controlInterface = quirkInterface,
                    unitId = quirkUnitId,
                    sourceId = -1,
                    channel = channel,
                    writable = true,
                    recipient = quirk.hardwareVolumeRecipient,
                )
            }
        }
        if (features.isEmpty()) {
            return mapOf(
                "available" to false,
                "featureUnits" to emptyList<String>(),
                "quirkOverride" to quirkOverride,
            )
        }
        val overrideRange = hardwareVolumeRangeOverride(quirk)
        val probes = features
            .groupBy { Triple(it.protocol, it.controlInterface, it.unitId) }
            .values
            .flatMap { group ->
                val feature = group.first()
                val controlInterface = findAudioControlInterface(device, feature.controlInterface)
                val requiresClaim = hardwareVolumeRequiresInterfaceClaim(feature.recipient)
                val claimed = !requiresClaim || controlInterface?.let {
                    runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
                } == true
                if (!claimed) {
                    listOf(
                        mapOf(
                            "protocol" to feature.protocol,
                            "controlInterface" to feature.controlInterface,
                            "featureUnitId" to feature.unitId,
                            "error" to "Failed to claim AudioControl interface.",
                        ),
                    )
                } else {
                    try {
                        group.sortedBy { it.channel }.map {
                            readHardwareVolumeProbe(connection, it, overrideRange)
                        }
                    } finally {
                        if (requiresClaim && controlInterface != null) {
                            runCatching { connection.releaseInterface(controlInterface) }
                        }
                    }
                }
            }
        return mapOf(
            "available" to true,
            "featureUnits" to features.map { it.description() },
            "probes" to probes,
            "quirkOverride" to quirkOverride,
        )
    }

    private fun parseHardwareVolumeFeatures(descriptors: ByteArray?): List<HardwareVolumeFeature> {
        if (descriptors == null) return emptyList()
        val features = mutableListOf<HardwareVolumeFeature>()
        var offset = 0
        var interfaceNumber = -1
        var interfaceClass = -1
        var interfaceSubclass = -1
        var interfaceProtocol = -1
        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) break
            if (descriptorType == 0x04 && length >= 9) {
                interfaceNumber = descriptors[offset + 2].toInt() and 0xff
                interfaceClass = descriptors[offset + 5].toInt() and 0xff
                interfaceSubclass = descriptors[offset + 6].toInt() and 0xff
                interfaceProtocol = descriptors[offset + 7].toInt() and 0xff
            } else if (
                descriptorType == 0x24 &&
                interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                interfaceSubclass == 1 &&
                length >= 7 &&
                (descriptors[offset + 2].toInt() and 0xff) == 0x06
            ) {
                if (interfaceProtocol == 0x20) {
                    val controlCount = (length - 6) / 4
                    for (channel in 0 until controlCount) {
                        val controlOffset = offset + 5 + channel * 4
                        val controls = (descriptors[controlOffset].toInt() and 0xff) or
                            ((descriptors[controlOffset + 1].toInt() and 0xff) shl 8) or
                            ((descriptors[controlOffset + 2].toInt() and 0xff) shl 16) or
                            ((descriptors[controlOffset + 3].toInt() and 0xff) shl 24)
                        val volumeControl = (controls ushr 2) and 0x03
                        val writable = when (volumeControl) {
                            0x01 -> false
                            0x03 -> true
                            else -> null
                        } ?: continue
                        features += HardwareVolumeFeature(
                            protocol = "uac2",
                            controlInterface = interfaceNumber,
                            unitId = descriptors[offset + 3].toInt() and 0xff,
                            sourceId = descriptors[offset + 4].toInt() and 0xff,
                            channel = channel,
                            writable = writable,
                        )
                    }
                } else {
                    val controlSize = descriptors[offset + 5].toInt() and 0xff
                    if (controlSize in 1..4) {
                        val controlCount = (length - 7) / controlSize
                        for (channel in 0 until controlCount) {
                            var controls = 0
                            for (byteIndex in 0 until controlSize) {
                                controls = controls or (
                                    (descriptors[offset + 6 + channel * controlSize + byteIndex].toInt() and 0xff) shl
                                        (byteIndex * 8)
                                    )
                            }
                            if (controls and 0x02 == 0) continue
                            features += HardwareVolumeFeature(
                                protocol = "uac1",
                                controlInterface = interfaceNumber,
                                unitId = descriptors[offset + 3].toInt() and 0xff,
                                sourceId = descriptors[offset + 4].toInt() and 0xff,
                                channel = channel,
                                writable = true,
                            )
                        }
                    }
                }
            }
            offset += length
        }
        return features
    }

    private fun parseOutputTerminalSources(descriptors: ByteArray?): Set<Int> {
        if (descriptors == null) return emptySet()
        val sources = mutableSetOf<Int>()
        var offset = 0
        var interfaceClass = -1
        var interfaceSubclass = -1
        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) break
            if (descriptorType == 0x04 && length >= 9) {
                interfaceClass = descriptors[offset + 5].toInt() and 0xff
                interfaceSubclass = descriptors[offset + 6].toInt() and 0xff
            } else if (
                descriptorType == 0x24 &&
                interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                interfaceSubclass == 1 &&
                length >= 8 &&
                (descriptors[offset + 2].toInt() and 0xff) == 0x03
            ) {
                sources += descriptors[offset + 7].toInt() and 0xff
            }
            offset += length
        }
        return sources
    }

    private fun readHardwareVolumeRangeValue(
        connection: UsbDeviceConnection,
        feature: HardwareVolumeFeature,
    ): HardwareVolumeRange? {
        val requestType = hardwareVolumeRequestType(UsbConstants.USB_DIR_IN, feature.recipient)
        val value = (0x02 shl 8) or feature.channel
        val index = (feature.unitId shl 8) or feature.controlInterface
        val range = if (feature.protocol == "uac2") {
            val header = ByteArray(2)
            if (connection.controlTransfer(requestType, 0x02, value, index, header, header.size, 300) != 2) return null
            val count = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
            if (count != 1) return null
            val data = ByteArray(8)
            if (connection.controlTransfer(requestType, 0x02, value, index, data, data.size, 300) != data.size) return null
            HardwareVolumeRange(
                minQ8_8 = readSignedQ8_8(data, 2),
                maxQ8_8 = readSignedQ8_8(data, 4),
                stepQ8_8 = readSignedQ8_8(data, 6),
            )
        } else {
            fun readAttribute(request: Int): Int? {
                val data = ByteArray(2)
                return if (
                    connection.controlTransfer(requestType, request, value, index, data, data.size, 300) == data.size
                ) readSignedQ8_8(data, 0) else null
            }
            HardwareVolumeRange(
                minQ8_8 = readAttribute(0x82) ?: return null,
                maxQ8_8 = readAttribute(0x83) ?: return null,
                stepQ8_8 = readAttribute(0x84) ?: return null,
            )
        }
        return range.takeIf {
            it.minQ8_8 != Short.MIN_VALUE.toInt() && it.minQ8_8 <= it.maxQ8_8 && it.stepQ8_8 > 0
        }
    }

    private fun readHardwareVolumeCurrent(
        connection: UsbDeviceConnection,
        feature: HardwareVolumeFeature,
    ): Int? {
        val data = ByteArray(2)
        val result = connection.controlTransfer(
            hardwareVolumeRequestType(UsbConstants.USB_DIR_IN, feature.recipient),
            if (feature.protocol == "uac2") 0x01 else 0x81,
            (0x02 shl 8) or feature.channel,
            (feature.unitId shl 8) or feature.controlInterface,
            data,
            data.size,
            300,
        )
        return if (result == data.size) readSignedQ8_8(data, 0) else null
    }

    private fun writeHardwareVolumeValue(
        connection: UsbDeviceConnection,
        feature: HardwareVolumeFeature,
        valueQ8_8: Int,
    ): Boolean {
        val data = byteArrayOf(valueQ8_8.toByte(), (valueQ8_8 shr 8).toByte())
        return connection.controlTransfer(
            hardwareVolumeRequestType(UsbConstants.USB_DIR_OUT, feature.recipient),
            0x01,
            (0x02 shl 8) or feature.channel,
            (feature.unitId shl 8) or feature.controlInterface,
            data,
            data.size,
            300,
        ) == data.size
    }

    private fun rollbackHardwareVolume(
        connection: UsbDeviceConnection,
        written: List<HardwareVolumeFeature>,
        previous: Map<HardwareVolumeFeature, Int>,
    ) {
        written.asReversed().forEach { feature ->
            previous[feature]?.let { writeHardwareVolumeValue(connection, feature, it) }
        }
    }

    private fun readHardwareVolumeProbe(
        connection: UsbDeviceConnection,
        feature: HardwareVolumeFeature,
        overrideRange: HardwareVolumeRange? = null,
    ): Map<String, Any?> {
        val requestType = hardwareVolumeRequestType(UsbConstants.USB_DIR_IN, feature.recipient)
        val value = (0x02 shl 8) or feature.channel
        val index = (feature.unitId shl 8) or feature.controlInterface
        val current = ByteArray(2)
        val currentResult = connection.controlTransfer(
            requestType,
            if (feature.protocol == "uac2") 0x01 else 0x81,
            value,
            index,
            current,
            current.size,
            300,
        )
        return buildMap {
            put("protocol", feature.protocol)
            put("controlInterface", feature.controlInterface)
            put("featureUnitId", feature.unitId)
            put("channel", feature.channel)
            put("recipient", feature.recipient)
            put("writeState", if (feature.writable) "read-write" else "read-only")
            put("currentResult", currentResult)
            if (currentResult == current.size) put("currentQ8_8Db", readSignedQ8_8(current, 0))
            if (overrideRange != null) {
                put(
                    "range",
                    mapOf(
                        "source" to "quirk",
                        "minQ8_8Db" to overrideRange.minQ8_8,
                        "maxQ8_8Db" to overrideRange.maxQ8_8,
                        "stepQ8_8Db" to overrideRange.stepQ8_8,
                        "muteQ8_8Db" to overrideRange.muteQ8_8,
                    ),
                )
            } else if (feature.protocol == "uac2") {
                put("range", readUac2VolumeRange(connection, requestType, value, index))
            } else {
                put("range", readUac1VolumeRange(connection, requestType, value, index))
            }
        }
    }

    private fun readUac2VolumeRange(
        connection: UsbDeviceConnection,
        requestType: Int,
        value: Int,
        index: Int,
    ): Map<String, Any?> {
        val header = ByteArray(2)
        val headerResult = connection.controlTransfer(requestType, 0x02, value, index, header, header.size, 300)
        if (headerResult != header.size) return mapOf("result" to headerResult)
        val count = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        if (count !in 1..16) return mapOf("result" to headerResult, "subrangeCount" to count)
        val data = ByteArray(2 + count * 6)
        val result = connection.controlTransfer(requestType, 0x02, value, index, data, data.size, 300)
        if (result != data.size) return mapOf("result" to result, "subrangeCount" to count)
        return mapOf(
            "result" to result,
            "subranges" to (0 until count).map { subrange ->
                val offset = 2 + subrange * 6
                mapOf(
                    "minQ8_8Db" to readSignedQ8_8(data, offset),
                    "maxQ8_8Db" to readSignedQ8_8(data, offset + 2),
                    "stepQ8_8Db" to readSignedQ8_8(data, offset + 4),
                )
            },
        )
    }

    private fun readUac1VolumeRange(
        connection: UsbDeviceConnection,
        requestType: Int,
        value: Int,
        index: Int,
    ): Map<String, Any?> {
        fun readAttribute(request: Int): Int? {
            val data = ByteArray(2)
            return if (
                connection.controlTransfer(requestType, request, value, index, data, data.size, 300) == data.size
            ) readSignedQ8_8(data, 0) else null
        }
        return mapOf(
            "minQ8_8Db" to readAttribute(0x82),
            "maxQ8_8Db" to readAttribute(0x83),
            "stepQ8_8Db" to readAttribute(0x84),
        )
    }

    private fun readSignedQ8_8(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)).toShort().toInt()

    private fun findAudioControlInterface(device: UsbDevice, interfaceNumber: Int? = null): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == 1 &&
                (interfaceNumber == null || usbInterface.id == interfaceNumber)
            ) {
                return usbInterface
            }
        }
        return null
    }

    companion object {
        private val writeLock = Any()
        private const val TAG = "UsbStandardHardwareVolume"
    }
}
