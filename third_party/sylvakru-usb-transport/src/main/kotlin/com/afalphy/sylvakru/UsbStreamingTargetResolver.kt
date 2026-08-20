package com.afalphy.sylvakru

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build

/**
 * Adapted from sylvakru-usb UsbExclusiveAudioEngine.
 *
 * The descriptor parser, candidate ordering, feedback-endpoint detection and UAC1/UAC2 clock
 * programming intentionally follow the reference implementation instead of introducing a second
 * Mica-specific USB interpretation layer.
 */
object UsbStreamingTargetResolver {
    private const val TAG = "UsbStreamingTargetResolver"
    private const val USB_RECIP_INTERFACE = 0x01
    private const val USB_RECIP_ENDPOINT = 0x02

    fun resolvePcmTarget(
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): UsbStreamingTarget? = resolveTarget(
        device = device,
        rawDescriptors = rawDescriptors,
        sampleRate = sampleRate,
        channels = channels,
        bitDepth = bitDepth,
        requireRawData = false,
    )

    /** Reference DoP rule: use the DSD/16 frame rate and require a 24/32-bit USB slot. */
    fun resolveDopTarget(
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        dopFrameRate: Int,
        channels: Int,
    ): UsbStreamingTarget? = resolveTarget(
        device = device,
        rawDescriptors = rawDescriptors,
        sampleRate = dopFrameRate,
        channels = channels,
        bitDepth = null,
        requireRawData = false,
    )?.takeIf { it.usbBytesPerSample >= 3 }

    /**
     * Reference native-DSD rule: quirk format wins, otherwise infer from a RAW_DATA descriptor;
     * the selected alt must have the exact same subslot width because DSD bytes may not be scaled.
     */
    fun resolveNativeDsdTarget(
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        dsdSampleRate: Int,
        channels: Int,
        quirk: DacQuirk,
    ): NativeDsdTarget? {
        val streamingFormats = parseStreamingFormatInfo(rawDescriptors)
        val candidate = classifyNativeCandidate(
            hasRawData = streamingFormats.values.any { it.isRawData },
            quirk = quirk,
        )
        val resolvedNativeFormat = (candidate as? NativeCandidate.Proven)?.format ?: return null
        val nativeBytesPerSample = nativeDsdBytesPerSample(resolvedNativeFormat) ?: return null
        val frameRate = dsdSampleRate / 8 / nativeBytesPerSample
        val target = resolveTarget(
            device = device,
            rawDescriptors = rawDescriptors,
            sampleRate = frameRate,
            channels = channels,
            bitDepth = nativeBytesPerSample * 8,
            requireRawData = streamingFormats.values.any { it.isRawData },
        ) ?: return null
        if (target.usbBytesPerSample != nativeBytesPerSample ||
            (target.usbBitResolution != null && target.usbBitResolution != nativeBytesPerSample * 8)
        ) {
            return null
        }
        return NativeDsdTarget(
            target = target,
            nativeFormat = resolvedNativeFormat,
            frameRate = frameRate,
            bytesPerSample = nativeBytesPerSample,
        )
    }

    /** RAW_DATA proves only a transport type. Endian/subslot framing requires scoped evidence. */
    fun classifyNativeCandidate(hasRawData: Boolean, quirk: DacQuirk): NativeCandidate = when {
        quirk.nativeDsdFormat != null -> NativeCandidate.Proven(quirk.nativeDsdFormat)
        hasRawData -> NativeCandidate.FramingUnproven
        else -> NativeCandidate.Unavailable
    }

    private fun resolveTarget(
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int?,
        requireRawData: Boolean,
    ): UsbStreamingTarget? {
        val streamingFormats = parseStreamingFormatInfo(rawDescriptors)
        val candidates = collectOutputCandidates(device, streamingFormats)
            .filter { !requireRawData || it.isRawData }
        if (candidates.isEmpty()) {
            return null
        }

        val sortedCandidates = candidates.sortedWith(
            compareBy<UsbStreamingTarget> { it.endpoint.maxPacketSize }
                .thenBy { it.alternateSetting },
        )
        val fittingCandidates = sortedCandidates.filter {
            it.endpoint.maxPacketSize >= requiredIsoPacketBytes(
                sampleRate,
                it.packetsPerSecond,
                channels,
                it.usbBytesPerSample,
            )
        }
        val exactBitDepthCandidates = if (bitDepth != null) {
            fittingCandidates.filter { it.usbBitResolution == bitDepth }
        } else {
            emptyList()
        }
        // Keep the reference auto-depth preference exactly: DoP/auto chooses 24, then 32,
        // then 16. Without this, a smaller 16-bit alt can win before a valid DoP alt.
        val autoBitDepthCandidates = if (bitDepth == null) {
            listOf(24, 32, 16)
                .firstNotNullOfOrNull { preferred ->
                    fittingCandidates.filter { it.usbBitResolution == preferred }
                        .takeIf { it.isNotEmpty() }
                }
                ?: fittingCandidates
        } else {
            emptyList()
        }
        val selectedPool = when {
            exactBitDepthCandidates.isNotEmpty() -> exactBitDepthCandidates
            autoBitDepthCandidates.isNotEmpty() -> autoBitDepthCandidates
            fittingCandidates.isNotEmpty() -> fittingCandidates
            else -> sortedCandidates
        }
        val selected = selectedPool.minWith(
            compareBy<UsbStreamingTarget> { it.usbBytesPerSample }
                .thenBy { it.endpoint.maxPacketSize }
                .thenBy { it.alternateSetting },
        )
        val requiredPacketBytes = requiredIsoPacketBytes(
            sampleRate,
            selected.packetsPerSecond,
            channels,
            selected.usbBytesPerSample,
        )
        UsbDiagnostics.i(
            TAG,
            "selected interface=${selected.usbInterface.id} alt=${selected.alternateSetting} " +
                "endpoint=0x${selected.endpoint.address.toString(16)} maxPacket=${selected.endpoint.maxPacketSize} " +
                "requiredPacketBytes=$requiredPacketBytes requestedBitDepth=$bitDepth " +
                "selectedBitDepth=${selected.usbBitResolution} raw=${selected.isRawData} " +
                "feedback=${selected.feedbackEndpointLabel}",
        )
        return selected
    }

    fun configureUsbAudioClock(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        target: UsbStreamingTarget,
        sampleRate: Int,
        quirk: DacQuirk,
    ): String? {
        val controlInterface = findAudioControlInterface(device)
        val controlInterfaceNumber = controlInterface?.id ?: target.usbInterface.id
        val clockSourceId = findUac2ClockSourceId(
            connection.rawDescriptors,
            streamingInterfaceNumber = target.usbInterface.id,
            streamingAlternateSetting = target.alternateSetting,
        )

        val claimedControl = controlInterface?.let {
            runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
        } == true
        try {
            if (clockSourceId != null) {
                readUac2ClockSampleRate(
                    connection,
                    clockSourceId,
                    controlInterfaceNumber,
                    "before",
                )
                val data = byteArrayOf(
                    (sampleRate and 0xff).toByte(),
                    ((sampleRate ushr 8) and 0xff).toByte(),
                    ((sampleRate ushr 16) and 0xff).toByte(),
                    ((sampleRate ushr 24) and 0xff).toByte(),
                )
                val result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
                    0x01,
                    0x01 shl 8,
                    (clockSourceId shl 8) or controlInterfaceNumber,
                    data,
                    data.size,
                    1000,
                )
                UsbDiagnostics.i(
                    TAG,
                    "UAC2 clock SET_CUR sampleRate=$sampleRate clockSourceId=$clockSourceId " +
                        "controlInterface=$controlInterfaceNumber result=$result",
                )
                if (quirk.clockSetCurDelayMs > 0) {
                    Thread.sleep(quirk.clockSetCurDelayMs.toLong())
                }
                if (quirk.clockSkipGetCurValidation) {
                    return null
                }
                val readBack = readUac2ClockSampleRate(
                    connection,
                    clockSourceId,
                    controlInterfaceNumber,
                    "after",
                )
                if (readBack != null && readBack > 0 && readBack != sampleRate) {
                    UsbDiagnostics.w(
                        TAG,
                        "UAC2 clock mismatch requested=$sampleRate readBack=$readBack",
                    )
                    return "DAC did not accept ${sampleRate}Hz (read back ${readBack}Hz)."
                }
                return null
            }

            val data = byteArrayOf(
                (sampleRate and 0xff).toByte(),
                ((sampleRate ushr 8) and 0xff).toByte(),
                ((sampleRate ushr 16) and 0xff).toByte(),
            )
            val result = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_ENDPOINT,
                0x01,
                0x01 shl 8,
                target.endpoint.address,
                data,
                data.size,
                1000,
            )
            UsbDiagnostics.i(
                TAG,
                "UAC1 endpoint SET_CUR sampleRate=$sampleRate endpoint=0x${
                    target.endpoint.address.toString(16)
                } result=$result",
            )
            if (quirk.clockSetCurDelayMs > 0) {
                Thread.sleep(quirk.clockSetCurDelayMs.toLong())
            }
            return null
        } catch (error: RuntimeException) {
            UsbDiagnostics.w(TAG, "USB audio clock configuration failed.", error)
            return null
        } finally {
            if (claimedControl && controlInterface != null) {
                runCatching { connection.releaseInterface(controlInterface) }
            }
        }
    }

    fun requiredIsoPacketBytes(
        sampleRate: Int,
        packetsPerSecond: Int,
        channels: Int,
        bytesPerSample: Int,
    ): Int {
        val maxFramesPerPacket = (sampleRate + packetsPerSecond - 1) / packetsPerSecond
        return maxFramesPerPacket * channels * bytesPerSample
    }

    private fun collectOutputCandidates(
        device: UsbDevice,
        streamingFormats: Map<Pair<Int, Int>, UsbStreamingFormatInfo>,
    ): List<UsbStreamingTarget> {
        val candidates = mutableListOf<UsbStreamingTarget>()
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) {
                continue
            }
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (
                    endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    val alt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        usbInterface.alternateSetting
                    } else {
                        0
                    }
                    candidates += UsbStreamingTarget(
                        usbInterface = usbInterface,
                        endpoint = endpoint,
                        feedbackEndpoint = findFeedbackEndpoint(usbInterface),
                        formatInfo = streamingFormats[usbInterface.id to alt],
                    )
                }
            }
        }
        return candidates
    }

    private fun findFeedbackEndpoint(usbInterface: UsbInterface): UsbEndpoint? {
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            val isIsochronous = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            val isInput = endpoint.direction == UsbConstants.USB_DIR_IN
            val usageType = endpoint.attributes and 0x30
            if (isIsochronous && isInput && usageType == 0x10) {
                return endpoint
            }
        }
        return null
    }

    private fun parseStreamingFormatInfo(
        descriptors: ByteArray?,
    ): Map<Pair<Int, Int>, UsbStreamingFormatInfo> {
        if (descriptors == null) {
            UsbDiagnostics.w(TAG, "USB raw descriptors unavailable; cannot parse AS format descriptors.")
            return emptyMap()
        }

        val formats = mutableMapOf<Pair<Int, Int>, UsbStreamingFormatInfo>()
        var offset = 0
        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentInterfaceSubclass = -1
        var currentInterfaceProtocol = -1

        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) {
                break
            }

            if (descriptorType == 0x04 && length >= 9) {
                currentInterfaceNumber = descriptors[offset + 2].toInt() and 0xff
                currentAlternateSetting = descriptors[offset + 3].toInt() and 0xff
                currentInterfaceSubclass = descriptors[offset + 6].toInt() and 0xff
                currentInterfaceProtocol = descriptors[offset + 8].toInt() and 0xff
            } else if (
                descriptorType == 0x24 &&
                currentInterfaceSubclass == 2 &&
                length >= 3
            ) {
                val key = currentInterfaceNumber to currentAlternateSetting
                val subtype = descriptors[offset + 2].toInt() and 0xff
                val existing = formats[key] ?: UsbStreamingFormatInfo(
                    interfaceNumber = currentInterfaceNumber,
                    alternateSetting = currentAlternateSetting,
                    protocol = currentInterfaceProtocol,
                )
                when (subtype) {
                    0x01 -> {
                        val terminalLink = if (length >= 4) {
                            descriptors[offset + 3].toInt() and 0xff
                        } else {
                            existing.terminalLink
                        }
                        val formatType = if (length >= 6) {
                            descriptors[offset + 5].toInt() and 0xff
                        } else {
                            existing.formatType
                        }
                        val bmFormats = if (length >= 10) {
                            (descriptors[offset + 6].toInt() and 0xff) or
                                ((descriptors[offset + 7].toInt() and 0xff) shl 8) or
                                ((descriptors[offset + 8].toInt() and 0xff) shl 16) or
                                ((descriptors[offset + 9].toInt() and 0xff) shl 24)
                        } else {
                            existing.bmFormats
                        }
                        val channels = if (length >= 11) {
                            descriptors[offset + 10].toInt() and 0xff
                        } else {
                            existing.channels
                        }
                        formats[key] = existing.copy(
                            terminalLink = terminalLink,
                            formatType = formatType,
                            bmFormats = bmFormats,
                            channels = channels,
                        )
                    }
                    0x02 -> {
                        if (length >= 7) {
                            formats[key] = existing.copy(
                                formatType = descriptors[offset + 3].toInt() and 0xff,
                                channels = descriptors[offset + 4].toInt() and 0xff,
                                subslotSize = descriptors[offset + 5].toInt() and 0xff,
                                bitResolution = descriptors[offset + 6].toInt() and 0xff,
                            )
                        } else if (length >= 6) {
                            formats[key] = existing.copy(
                                formatType = descriptors[offset + 3].toInt() and 0xff,
                                subslotSize = descriptors[offset + 4].toInt() and 0xff,
                                bitResolution = descriptors[offset + 5].toInt() and 0xff,
                            )
                        }
                    }
                }
            }
            offset += length
        }
        UsbDiagnostics.i(
            TAG,
            "USB AS formats parsed: ${formats.values.sortedWith(
                compareBy<UsbStreamingFormatInfo> { it.interfaceNumber }.thenBy { it.alternateSetting },
            ).joinToString()}",
        )
        return formats
    }

    private fun findUac2ClockSourceId(
        descriptors: ByteArray?,
        streamingInterfaceNumber: Int,
        streamingAlternateSetting: Int,
    ): Int? {
        if (descriptors == null) {
            return null
        }

        var offset = 0
        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentInterfaceSubclass = -1
        var terminalLink: Int? = null
        var firstClockSourceId: Int? = null
        var hasClockSource = false
        val inputTerminalClockIds = mutableMapOf<Int, Int>()
        val outputTerminalClockIds = mutableMapOf<Int, Int>()

        while (offset + 1 < descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val descriptorType = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) {
                break
            }

            if (descriptorType == 0x04 && length >= 9) {
                currentInterfaceNumber = descriptors[offset + 2].toInt() and 0xff
                currentAlternateSetting = descriptors[offset + 3].toInt() and 0xff
                currentInterfaceSubclass = descriptors[offset + 6].toInt() and 0xff
            } else if (descriptorType == 0x24 && length >= 3) {
                when (descriptors[offset + 2].toInt() and 0xff) {
                    0x0a -> {
                        hasClockSource = true
                        if (length >= 4 && firstClockSourceId == null) {
                            firstClockSourceId = descriptors[offset + 3].toInt() and 0xff
                        }
                    }
                    0x02 -> if (length >= 8) {
                        val terminalId = descriptors[offset + 3].toInt() and 0xff
                        inputTerminalClockIds[terminalId] = descriptors[offset + 7].toInt() and 0xff
                    }
                    0x03 -> if (length >= 9) {
                        val terminalId = descriptors[offset + 3].toInt() and 0xff
                        outputTerminalClockIds[terminalId] = descriptors[offset + 8].toInt() and 0xff
                    }
                    0x01 -> if (
                        currentInterfaceNumber == streamingInterfaceNumber &&
                        currentAlternateSetting == streamingAlternateSetting &&
                        currentInterfaceSubclass == 2 &&
                        length >= 4
                    ) {
                        terminalLink = descriptors[offset + 3].toInt() and 0xff
                    }
                }
            }
            offset += length
        }

        if (!hasClockSource) {
            UsbDiagnostics.i(TAG, "no UAC2 clock source entity; using endpoint SET_CUR")
            return null
        }
        val result = terminalLink?.let {
            inputTerminalClockIds[it] ?: outputTerminalClockIds[it]
        } ?: firstClockSourceId
        UsbDiagnostics.i(
            TAG,
            "parsed UAC2 clock source streamingInterface=$streamingInterfaceNumber " +
                "alt=$streamingAlternateSetting terminalLink=$terminalLink clockSourceId=$result",
        )
        return result
    }

    private fun readUac2ClockSampleRate(
        connection: UsbDeviceConnection,
        clockSourceId: Int,
        controlInterfaceNumber: Int,
        label: String,
    ): Int? {
        val data = ByteArray(4)
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            0x81,
            0x01 shl 8,
            (clockSourceId shl 8) or controlInterfaceNumber,
            data,
            data.size,
            1000,
        )
        val sampleRate = if (result == 4) {
            (data[0].toInt() and 0xff) or
                ((data[1].toInt() and 0xff) shl 8) or
                ((data[2].toInt() and 0xff) shl 16) or
                ((data[3].toInt() and 0xff) shl 24)
        } else {
            null
        }
        UsbDiagnostics.i(
            TAG,
            "UAC2 clock GET_CUR $label result=$result clockSourceId=$clockSourceId " +
                "controlInterface=$controlInterfaceNumber sampleRate=${sampleRate ?: "n/a"}",
        )
        return sampleRate
    }

    private fun findAudioControlInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == 1
            ) {
                return usbInterface
            }
        }
        return null
    }
}

sealed interface NativeCandidate {
    data class Proven(val format: String) : NativeCandidate
    data object FramingUnproven : NativeCandidate
    data object Unavailable : NativeCandidate
}

data class UsbStreamingTarget(
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint,
    val feedbackEndpoint: UsbEndpoint? = null,
    val formatInfo: UsbStreamingFormatInfo? = null,
) {
    val alternateSetting: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            usbInterface.alternateSetting
        } else {
            0
        }

    val feedbackEndpointLabel: String
        get() = feedbackEndpoint?.let {
            "0x${it.address.toString(16)}/max=${it.maxPacketSize}/interval=${it.interval}/attr=0x${
                it.attributes.toString(16)
            }"
        } ?: "none"

    val packetsPerSecond: Int
        get() {
            if (usbInterface.interfaceProtocol == 32) {
                val interval = endpoint.interval.coerceIn(1, 4)
                return 8000 / (1 shl (interval - 1))
            }
            return 1000
        }

    val usbBytesPerSample: Int
        get() = formatInfo?.subslotSize?.takeIf { it > 0 } ?: 2

    val usbBitResolution: Int?
        get() = formatInfo?.bitResolution?.takeIf { it > 0 }

    val isRawData: Boolean
        get() = formatInfo?.isRawData == true
}

data class UsbStreamingFormatInfo(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val protocol: Int,
    val terminalLink: Int? = null,
    val formatType: Int? = null,
    val channels: Int? = null,
    val subslotSize: Int? = null,
    val bitResolution: Int? = null,
    val bmFormats: Int? = null,
) {
    /** UAC2 bmFormats D31 = RAW_DATA, used by native DSD alternate settings. */
    val isRawData: Boolean
        get() = bmFormats != null && (bmFormats and (1 shl 31)) != 0
}

data class NativeDsdTarget(
    val target: UsbStreamingTarget,
    val nativeFormat: String,
    val frameRate: Int,
    val bytesPerSample: Int,
)
