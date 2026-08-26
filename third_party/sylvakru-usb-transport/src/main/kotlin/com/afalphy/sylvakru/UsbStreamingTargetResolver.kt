/*
 * Derived in whole or in part from the SylvaKru USB-exclusive implementation
 * (https://github.com/huya688zdx/sylvakru), Apache License 2.0.
 * Modified/adapted for Mica; see third_party/sylvakru-usb-transport/NOTICE.
 */
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
    private const val UAC2_PROTOCOL = 0x20
    private const val UAC2_REQUEST_RANGE = 0x82

    fun resolvePcmTarget(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): UsbStreamingTarget? = resolveTarget(
        connection = connection,
        device = device,
        rawDescriptors = rawDescriptors,
        sampleRate = sampleRate,
        channels = channels,
        bitDepth = bitDepth,
        requireRawData = false,
    )

    /** Reference DoP rule: use the DSD/16 frame rate and require a 24/32-bit USB slot. */
    fun resolveDopTarget(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        dopFrameRate: Int,
        channels: Int,
    ): UsbStreamingTarget? = resolveTarget(
        connection = connection,
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
        connection: UsbDeviceConnection,
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        dsdSampleRate: Int,
        channels: Int,
        quirk: DacQuirk,
    ): NativeDsdTarget? {
        val streamingFormats = parseStreamingFormatInfo(rawDescriptors)
        val rawDataSubslotSizes = streamingFormats.values
            .filter { it.isRawData }
            .mapNotNull { it.subslotSize?.takeIf { size -> size > 0 } }
            .distinct()
        val candidate = classifyNativeCandidate(
            rawDataSubslotSizes = rawDataSubslotSizes,
            quirk = quirk,
        )
        val resolvedNativeFormat = (candidate as? NativeCandidate.Proven)?.format ?: return null
        val nativeBytesPerSample = nativeDsdBytesPerSample(resolvedNativeFormat) ?: return null
        val frameRate = dsdSampleRate / 8 / nativeBytesPerSample
        val target = resolveTarget(
            connection = connection,
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

    /**
     * Reference Native DSD rule: an explicit quirk wins. Otherwise a UAC2 RAW_DATA alternate
     * with one unambiguous subslot width maps to the corresponding little-endian ALSA-style
     * framing. Unknown/ambiguous widths remain fail-closed; vendor/chip names are never guessed.
     */
    fun classifyNativeCandidate(
        rawDataSubslotSizes: List<Int>,
        quirk: DacQuirk,
    ): NativeCandidate {
        quirk.nativeDsdFormat?.let { return NativeCandidate.Proven(it) }
        if (rawDataSubslotSizes.isEmpty()) return NativeCandidate.Unavailable
        if (rawDataSubslotSizes.size != 1) return NativeCandidate.FramingUnproven
        val format = when (rawDataSubslotSizes.single()) {
            1 -> "u8"
            2 -> "u16le"
            4 -> "u32le"
            else -> null
        }
        return format?.let(NativeCandidate::Proven) ?: NativeCandidate.FramingUnproven
    }

    private fun resolveTarget(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int?,
        requireRawData: Boolean,
    ): UsbStreamingTarget? {
        val streamingFormats = parseStreamingFormatInfo(rawDescriptors)
        val candidates = collectOutputCandidates(device, streamingFormats)
            .filter { it.isRawData == requireRawData }
        if (candidates.isEmpty()) {
            return null
        }

        val channelCandidates = candidates.filter { candidate ->
            val advertisedChannels = candidate.formatInfo?.channels?.takeIf { it > 0 }
            val accepted = advertisedChannels == null || advertisedChannels == channels
            if (!accepted) {
                UsbDiagnostics.i(
                    TAG,
                    "rejecting interface=${candidate.usbInterface.id} alt=${candidate.alternateSetting} " +
                        "channels=$channels advertisedChannels=$advertisedChannels",
                )
            }
            accepted
        }
        if (channelCandidates.isEmpty()) {
            return null
        }

        val sortedCandidates = channelCandidates.sortedWith(
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
        if (fittingCandidates.isEmpty()) {
            return null
        }
        val sampleRateCandidates = qualifyAdvertisedSampleRates(
            connection = connection,
            device = device,
            rawDescriptors = rawDescriptors,
            candidates = fittingCandidates,
            sampleRate = sampleRate,
        )
        if (sampleRateCandidates.isEmpty()) {
            UsbDiagnostics.w(
                TAG,
                "no USB alternate advertises requested sampleRate=${sampleRate}Hz",
            )
            return null
        }
        val compatibleBitDepthCandidates = if (bitDepth != null) {
            sampleRateCandidates.filter { candidate ->
                val resolution = candidate.usbBitResolution ?: candidate.usbBytesPerSample * 8
                UsbExactPcmTargetPolicy.accepts(bitDepth, candidate.usbBytesPerSample, resolution)
            }
        } else {
            emptyList()
        }
        val exactBitDepthCandidates = if (bitDepth != null) {
            compatibleBitDepthCandidates.filter { it.usbBitResolution == bitDepth }
        } else {
            emptyList()
        }
        // Keep the reference auto-depth preference exactly: DoP/auto chooses 24, then 32,
        // then 16. Without this, a smaller 16-bit alt can win before a valid DoP alt.
        val autoBitDepthCandidates = if (bitDepth == null) {
            listOf(24, 32, 16)
                .firstNotNullOfOrNull { preferred ->
                    sampleRateCandidates.filter { it.usbBitResolution == preferred }
                        .takeIf { it.isNotEmpty() }
                }
                ?: sampleRateCandidates
        } else {
            emptyList()
        }
        val selectedPool = when {
            exactBitDepthCandidates.isNotEmpty() -> exactBitDepthCandidates
            bitDepth != null && compatibleBitDepthCandidates.isNotEmpty() -> compatibleBitDepthCandidates
            autoBitDepthCandidates.isNotEmpty() -> autoBitDepthCandidates
            else -> sampleRateCandidates
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
                "feedback=${selected.feedbackEndpointLabel} " +
                "sampleRates=${selected.sampleRateCapability?.description ?: "unknown"}",
        )
        return selected
    }

    private fun qualifyAdvertisedSampleRates(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        rawDescriptors: ByteArray?,
        candidates: List<UsbStreamingTarget>,
        sampleRate: Int,
    ): List<UsbStreamingTarget> {
        val uac2CapabilityCache = mutableMapOf<Pair<Int, Int>, UsbSampleRateCapability?>()
        return candidates.mapNotNull { candidate ->
            val capability = if (candidate.isUac2) {
                val clockSourceId = findUac2ClockSourceId(
                    rawDescriptors,
                    streamingInterfaceNumber = candidate.usbInterface.id,
                    streamingAlternateSetting = candidate.alternateSetting,
                )
                if (clockSourceId == null) {
                    UsbDiagnostics.w(
                        TAG,
                        "UAC2 sample-rate capability unknown: no clock source for " +
                            "interface=${candidate.usbInterface.id} alt=${candidate.alternateSetting}",
                    )
                    null
                } else {
                    val controlInterfaceNumber = findAudioControlInterface(device)?.id
                        ?: candidate.usbInterface.id
                    val key = clockSourceId to controlInterfaceNumber
                    if (uac2CapabilityCache.containsKey(key)) {
                        uac2CapabilityCache[key]
                    } else {
                        queryUac2ClockSampleRateCapability(
                            connection = connection,
                            device = device,
                            clockSourceId = clockSourceId,
                            controlInterfaceNumber = controlInterfaceNumber,
                        ).also { uac2CapabilityCache[key] = it }
                    }
                }
            } else {
                candidate.formatInfo?.sampleRateCapability
            }

            if (capability != null && !capability.supports(sampleRate)) {
                UsbDiagnostics.i(
                    TAG,
                    "rejecting interface=${candidate.usbInterface.id} alt=${candidate.alternateSetting} " +
                        "sampleRate=${sampleRate}Hz advertised=${capability.description}",
                )
                null
            } else {
                candidate.copy(sampleRateCapability = capability)
            }
        }
    }

    private fun queryUac2ClockSampleRateCapability(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        clockSourceId: Int,
        controlInterfaceNumber: Int,
    ): UsbSampleRateCapability? {
        val controlInterface = findAudioControlInterface(device, controlInterfaceNumber)
        val claimedControl = controlInterface?.let {
            runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
        } == true
        return try {
            val data = ByteArray(1024)
            val result = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
                UAC2_REQUEST_RANGE,
                0x01 shl 8,
                (clockSourceId shl 8) or controlInterfaceNumber,
                data,
                data.size,
                1000,
            )
            val capability = parseUac2ClockRangeResponse(data, result)
            UsbDiagnostics.i(
                TAG,
                "UAC2 clock GET_RANGE result=$result clockSourceId=$clockSourceId " +
                    "controlInterface=$controlInterfaceNumber sampleRates=${
                        capability?.description ?: "unknown"
                    } raw=${hexPreview(data, result.coerceIn(0, 32))}",
            )
            capability
        } catch (error: RuntimeException) {
            UsbDiagnostics.w(TAG, "UAC2 clock GET_RANGE failed; capability remains unknown.", error)
            null
        } finally {
            if (claimedControl) {
                runCatching { connection.releaseInterface(checkNotNull(controlInterface)) }
            }
        }
    }

    internal fun parseUac2ClockRangeResponse(
        data: ByteArray,
        result: Int,
    ): UsbSampleRateCapability? {
        if (result < 2 || result > data.size) return null
        val count = readLe16(data, 0)
        if (count <= 0) return null
        val expected = 2L + count.toLong() * 12L
        if (expected > result.toLong()) return null
        val ranges = mutableListOf<UsbSampleRateRange>()
        repeat(count) { index ->
            val offset = 2 + index * 12
            val minimum = readLe32Positive(data, offset) ?: return null
            val maximum = readLe32Positive(data, offset + 4) ?: return null
            val resolution = readLe32NonNegative(data, offset + 8) ?: return null
            if (maximum < minimum) return null
            ranges += UsbSampleRateRange(minimum, maximum, resolution)
        }
        return ranges.takeIf { it.isNotEmpty() }?.let {
            UsbSampleRateCapability(it, source = "uac2-clock-range")
        }
    }

    internal fun streamingActivationPlan(
        isUac2: Boolean,
        resetAltQuirk: Boolean,
    ): UsbStreamingActivationPlan = UsbStreamingActivationPlan(
        deferTargetAltUntilConfigured = isUac2,
        resetAltBeforeConfigured = isUac2 && resetAltQuirk,
    )

    fun configureUsbAudioClock(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        target: UsbStreamingTarget,
        sampleRate: Int,
        quirk: DacQuirk,
    ): String? {
        val controlInterface = findAudioControlInterface(device)
        val controlInterfaceNumber = controlInterface?.id ?: target.usbInterface.id
        val clockSourceId = if (target.isUac2) {
            findUac2ClockSourceId(
                connection.rawDescriptors,
                streamingInterfaceNumber = target.usbInterface.id,
                streamingAlternateSetting = target.alternateSetting,
            ) ?: return "UAC2 clock source is unavailable for interface=${target.usbInterface.id} " +
                "alt=${target.alternateSetting}."
        } else {
            null
        }

        val claimedControl = controlInterface?.let {
            runCatching { connection.claimInterface(it, true) }.getOrDefault(false)
        } == true
        try {
            if (target.isUac2) {
                val uac2ClockSourceId = checkNotNull(clockSourceId)
                readUac2ClockSampleRate(
                    connection,
                    uac2ClockSourceId,
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
                    (uac2ClockSourceId shl 8) or controlInterfaceNumber,
                    data,
                    data.size,
                    1000,
                )
                UsbDiagnostics.i(
                    TAG,
                    "UAC2 clock SET_CUR sampleRate=$sampleRate clockSourceId=$uac2ClockSourceId " +
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
                    uac2ClockSourceId,
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
            if (claimedControl) {
                runCatching { connection.releaseInterface(checkNotNull(controlInterface)) }
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

    fun isoIntervalMicroframes(interval: Int): Int =
        1 shl (interval.coerceIn(1, 4) - 1)

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

    internal fun parseStreamingFormatInfo(
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
                currentInterfaceProtocol = descriptors[offset + 7].toInt() and 0xff
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
                                sampleRateCapability = parseUac1SampleRateCapability(
                                    descriptors,
                                    offset,
                                    length,
                                    existing.protocol,
                                ),
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

    private fun parseUac1SampleRateCapability(
        descriptors: ByteArray,
        offset: Int,
        length: Int,
        protocol: Int,
    ): UsbSampleRateCapability? {
        if (protocol == UAC2_PROTOCOL || length < 8) return null
        val sampleFrequencyType = descriptors[offset + 7].toInt() and 0xff
        val ranges = if (sampleFrequencyType == 0) {
            if (length < 14) return null
            val minimum = readLe24(descriptors, offset + 8)
            val maximum = readLe24(descriptors, offset + 11)
            if (minimum <= 0 || maximum < minimum) return null
            listOf(UsbSampleRateRange(minimum, maximum, resolutionHz = 0))
        } else {
            val requiredLength = 8 + sampleFrequencyType * 3
            if (length < requiredLength) return null
            buildList {
                repeat(sampleFrequencyType) { index ->
                    val rate = readLe24(descriptors, offset + 8 + index * 3)
                    if (rate > 0) {
                        add(UsbSampleRateRange(rate, rate, resolutionHz = 0))
                    }
                }
            }
        }
        return ranges.takeIf { it.isNotEmpty() }?.let {
            UsbSampleRateCapability(it, source = "uac1-format-descriptor")
        }
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
                "controlInterface=$controlInterfaceNumber sampleRate=${sampleRate ?: "n/a"} " +
                "raw=${hexPreview(data)}",
        )
        return sampleRate
    }

    private fun readLe16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16)

    private fun readLe32Positive(data: ByteArray, offset: Int): Int? =
        readLe32NonNegative(data, offset)?.takeIf { it > 0 }

    private fun readLe32NonNegative(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > data.size) return null
        val value =
            (data[offset].toLong() and 0xffL) or
                ((data[offset + 1].toLong() and 0xffL) shl 8) or
                ((data[offset + 2].toLong() and 0xffL) shl 16) or
                ((data[offset + 3].toLong() and 0xffL) shl 24)
        return value.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun hexPreview(data: ByteArray, limit: Int = 16): String =
        data.take(minOf(data.size, limit)).joinToString(" ") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

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
    val sampleRateCapability: UsbSampleRateCapability? = formatInfo?.sampleRateCapability,
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

    val isUac2: Boolean
        get() = (formatInfo?.protocol ?: usbInterface.interfaceProtocol) == 0x20
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
    val sampleRateCapability: UsbSampleRateCapability? = null,
) {
    /** UAC2 bmFormats D31 = RAW_DATA, used by native DSD alternate settings. */
    val isRawData: Boolean
        get() = bmFormats != null && (bmFormats and (1 shl 31)) != 0
}

data class UsbSampleRateCapability(
    val ranges: List<UsbSampleRateRange>,
    val source: String,
) {
    fun supports(sampleRate: Int): Boolean = ranges.any { it.supports(sampleRate) }

    val description: String
        get() = "$source:${ranges.joinToString("|") { it.description }}"
}

data class UsbSampleRateRange(
    val minHz: Int,
    val maxHz: Int,
    val resolutionHz: Int,
) {
    fun supports(sampleRate: Int): Boolean {
        if (sampleRate !in minHz..maxHz) return false
        return resolutionHz <= 0 || (sampleRate - minHz) % resolutionHz == 0
    }

    val description: String
        get() = when {
            minHz == maxHz -> minHz.toString()
            resolutionHz > 0 -> "$minHz-$maxHz/$resolutionHz"
            else -> "$minHz-$maxHz"
        }
}

internal data class UsbStreamingActivationPlan(
    val deferTargetAltUntilConfigured: Boolean,
    val resetAltBeforeConfigured: Boolean,
)

data class NativeDsdTarget(
    val target: UsbStreamingTarget,
    val nativeFormat: String,
    val frameRate: Int,
    val bytesPerSample: Int,
)
