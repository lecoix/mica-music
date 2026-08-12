package com.mica.music.media.usb

private const val USB_DEVICE_DESCRIPTOR_LENGTH = 18
private const val USB_DT_DEVICE = 0x01

internal data class UsbRawAudioDescriptorSet(
    val bytes: ByteArray,
    val busSpeed: UsbBusSpeed,
)

internal data class UsbDeviceDescriptorFacts(
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
)

/** Reads only the standard USB device descriptor; unknown/malformed input stays unknown. */
internal object StandardUsbDeviceDescriptorParser {
    fun parse(rawDescriptors: ByteArray): UsbDeviceDescriptorFacts? {
        if (rawDescriptors.size < USB_DEVICE_DESCRIPTOR_LENGTH) return null
        fun u8(index: Int): Int = rawDescriptors[index].toInt() and 0xff
        fun u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
        if (u8(0) < USB_DEVICE_DESCRIPTOR_LENGTH || u8(1) != USB_DT_DEVICE) return null
        return UsbDeviceDescriptorFacts(
            vendorId = u16le(8),
            productId = u16le(10),
            bcdDevice = u16le(12),
        )
    }
}

internal sealed interface UsbRawStreamingFormatIdentity {
    data class Uac1(
        val formatTag: Int,
        val formatType: Int?,
    ) : UsbRawStreamingFormatIdentity

    data class Uac2(
        val formatType: Int,
        val formatsBitmap: Long,
    ) : UsbRawStreamingFormatIdentity
}

internal data class UsbParsedEndpoint(
    val address: Int,
    val attributes: Int,
    val rawMaxPacketSize: Int,
    val interval: Int,
    val synchAddress: Int?,
    val samplingFrequencyControl: Boolean = false,
) {
    val transferType: Int get() = attributes and 0x03
    val syncTypeCode: Int get() = (attributes ushr 2) and 0x03
    val usageTypeCode: Int get() = (attributes ushr 4) and 0x03
    val directionIn: Boolean get() = address and 0x80 != 0
    val packetPayloadBytes: Int get() = rawMaxPacketSize and 0x07ff
    val highBandwidthMultiplierCode: Int get() = (rawMaxPacketSize ushr 11) and 0x03
    val transactionsPerServiceInterval: Int get() = 1 + highBandwidthMultiplierCode
    val maxServiceIntervalBytes: Int get() = packetPayloadBytes * transactionsPerServiceInterval
}

internal data class UsbParsedTypeIPcmFormat(
    val channelCount: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val sampleRates: UsbSampleRateSupport,
)

internal data class UsbParsedStreamingAlternate(
    val protocol: UsbAudioProtocol,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val terminalLink: Int?,
    val formatIsPcm: Boolean,
    val format: UsbParsedTypeIPcmFormat?,
    val endpoints: List<UsbParsedEndpoint>,
    val rawFormatIdentity: UsbRawStreamingFormatIdentity? = null,
)

internal sealed interface UsbUac2ClockEntity {
    val id: Int

    data class Source(
        override val id: Int,
        val attributes: Int,
        val controls: Int,
    ) : UsbUac2ClockEntity

    data class Selector(
        override val id: Int,
        val sourceIds: List<Int>,
        val controls: Int,
    ) : UsbUac2ClockEntity

    data class Multiplier(
        override val id: Int,
        val sourceId: Int,
        val controls: Int,
    ) : UsbUac2ClockEntity
}

internal data class UsbParsedAudioDescriptorFacts(
    val audioFunction: UsbAudioFunction,
    val busSpeed: UsbBusSpeed,
    val streamingAlternates: List<UsbParsedStreamingAlternate>,
    val uac2ClockEntities: Map<Int, UsbUac2ClockEntity>,
    val uac2TerminalClockLinks: Map<Int, Int>,
    val deviceDescriptor: UsbDeviceDescriptorFacts? = null,
)

internal sealed interface UsbAudioDescriptorParseResult {
    data class Parsed(val facts: UsbParsedAudioDescriptorFacts) : UsbAudioDescriptorParseResult
    data class Rejected(val rejection: UsbAudioRejection) : UsbAudioDescriptorParseResult
}

/**
 * Non-real-time USB Audio Class descriptor parser.
 *
 * It deliberately records descriptor facts only. Eligibility, endpoint capacity, feedback topology,
 * clock validity, and exact-only negotiation are decided by later P3 stages.
 */
internal object StandardUacDescriptorParser {
    fun parse(descriptorSet: UsbRawAudioDescriptorSet): UsbAudioDescriptorParseResult {
        val raw = descriptorSet.bytes
        val deviceDescriptor = StandardUsbDeviceDescriptorParser.parse(raw)
        val alternates = linkedMapOf<Pair<Int, Int>, MutableStreamingAlternate>()
        val streamingInterfaces = linkedSetOf<Int>()
        val clockEntities = linkedMapOf<Int, UsbUac2ClockEntity>()
        val terminalClockLinks = linkedMapOf<Int, Int>()

        var currentInterfaceNumber = -1
        var currentAlternateSetting = -1
        var currentClass = -1
        var currentSubclass = -1
        var currentProtocol: UsbAudioProtocol? = null
        var audioControlInterface = -1
        var audioProtocol: UsbAudioProtocol? = null
        var offset = 0

        while (offset < raw.size) {
            if (offset + 2 > raw.size) {
                return malformed("descriptor header truncated at offset=$offset")
            }
            val length = raw.u8(offset)
            val type = raw.u8(offset + 1)
            if (length < 2 || offset + length > raw.size) {
                return malformed("invalid descriptor length=$length at offset=$offset remaining=${raw.size - offset}")
            }

            when (type) {
                USB_DT_INTERFACE -> {
                    if (length < 9) return malformed("interface descriptor too short at offset=$offset")
                    currentInterfaceNumber = raw.u8(offset + 2)
                    currentAlternateSetting = raw.u8(offset + 3)
                    currentClass = raw.u8(offset + 5)
                    currentSubclass = raw.u8(offset + 6)
                    currentProtocol = protocolFromInterface(raw.u8(offset + 7))
                    if (currentClass == USB_CLASS_AUDIO && currentSubclass == AUDIO_SUBCLASS_CONTROL) {
                        val protocol = currentProtocol ?: return unsupportedProtocol(raw.u8(offset + 7))
                        if (audioControlInterface >= 0 && audioControlInterface != currentInterfaceNumber) {
                            return rejected(
                                UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
                                "multiple AudioControl interfaces: $audioControlInterface and $currentInterfaceNumber",
                            )
                        }
                        audioControlInterface = currentInterfaceNumber
                        audioProtocol = protocol
                    }
                    if (currentClass == USB_CLASS_AUDIO && currentSubclass == AUDIO_SUBCLASS_STREAMING) {
                        val protocol = currentProtocol ?: return unsupportedProtocol(raw.u8(offset + 7))
                        streamingInterfaces += currentInterfaceNumber
                        alternates.getOrPut(currentInterfaceNumber to currentAlternateSetting) {
                            MutableStreamingAlternate(
                                protocol = protocol,
                                interfaceNumber = currentInterfaceNumber,
                                alternateSetting = currentAlternateSetting,
                            )
                        }
                    }
                }

                CS_INTERFACE -> if (currentClass == USB_CLASS_AUDIO) {
                    when (currentSubclass) {
                        AUDIO_SUBCLASS_STREAMING -> {
                            val alternate = alternates[currentInterfaceNumber to currentAlternateSetting]
                            if (alternate != null) {
                                when (alternate.protocol) {
                                    UsbAudioProtocol.UAC1 -> parseUac1StreamingDescriptor(raw, offset, length, alternate)
                                    UsbAudioProtocol.UAC2 -> parseUac2StreamingDescriptor(raw, offset, length, alternate)
                                }?.let { return it }
                            }
                        }

                        AUDIO_SUBCLASS_CONTROL -> if (currentProtocol == UsbAudioProtocol.UAC2) {
                            parseUac2ControlDescriptor(
                                raw = raw,
                                offset = offset,
                                length = length,
                                clockEntities = clockEntities,
                                terminalClockLinks = terminalClockLinks,
                            )?.let { return it }
                        }
                    }
                }

                USB_DT_ENDPOINT -> if (
                    currentClass == USB_CLASS_AUDIO && currentSubclass == AUDIO_SUBCLASS_STREAMING
                ) {
                    if (length < 7) return malformed("endpoint descriptor too short at offset=$offset")
                    val alternate = alternates[currentInterfaceNumber to currentAlternateSetting]
                    if (alternate != null) {
                        alternate.endpoints += UsbParsedEndpoint(
                            address = raw.u8(offset + 2),
                            attributes = raw.u8(offset + 3),
                            rawMaxPacketSize = raw.u16le(offset + 4),
                            interval = raw.u8(offset + 6),
                            synchAddress = if (length >= 9) raw.u8(offset + 8).takeIf { it != 0 } else null,
                        )
                    }
                }

                CS_ENDPOINT -> if (
                    currentClass == USB_CLASS_AUDIO && currentSubclass == AUDIO_SUBCLASS_STREAMING
                ) {
                    val alternate = alternates[currentInterfaceNumber to currentAlternateSetting]
                    if (alternate != null) {
                        parseClassEndpoint(raw, offset, length, alternate)?.let { return it }
                    }
                }
            }
            offset += length
        }

        val protocol = audioProtocol
            ?: alternates.values.firstOrNull()?.protocol
            ?: return rejected(UsbAudioRejectionCode.UNSUPPORTED_PROTOCOL, "no UAC1/UAC2 Audio Function found")
        if (audioControlInterface < 0) {
            return rejected(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, "AudioControl interface missing")
        }
        if (streamingInterfaces.isEmpty()) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_FORMAT, "AudioStreaming interface missing")
        }
        if (alternates.values.any { it.protocol != protocol }) {
            return rejected(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, "mixed UAC protocol inside one Audio Function")
        }

        return UsbAudioDescriptorParseResult.Parsed(
            UsbParsedAudioDescriptorFacts(
                audioFunction = UsbAudioFunction(
                    protocol = protocol,
                    controlInterfaceNumber = audioControlInterface,
                    streamingInterfaceNumbers = streamingInterfaces,
                ),
                busSpeed = descriptorSet.busSpeed,
                streamingAlternates = alternates.values.map { it.freeze() },
                uac2ClockEntities = clockEntities,
                uac2TerminalClockLinks = terminalClockLinks,
                deviceDescriptor = deviceDescriptor,
            ),
        )
    }

    private fun parseUac1StreamingDescriptor(
        raw: ByteArray,
        offset: Int,
        length: Int,
        alternate: MutableStreamingAlternate,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (length < 3) return malformed("UAC1 class interface descriptor too short at offset=$offset")
        when (raw.u8(offset + 2)) {
            AS_GENERAL -> {
                if (length < 7) return malformed("UAC1 AS_GENERAL too short at offset=$offset")
                alternate.terminalLink = raw.u8(offset + 3)
                val formatTag = raw.u16le(offset + 5)
                alternate.uac1FormatTag = formatTag
                alternate.formatIsPcm = formatTag == UAC_FORMAT_PCM
            }

            FORMAT_TYPE -> {
                if (length < 8) return malformed("UAC1 FORMAT_TYPE too short at offset=$offset")
                val formatType = raw.u8(offset + 3)
                alternate.rawFormatType = formatType
                if (formatType != FORMAT_TYPE_I) {
                    alternate.formatIsPcm = false
                    return null
                }
                val channels = raw.u8(offset + 4)
                val subslotBytes = raw.u8(offset + 5)
                val bitResolution = raw.u8(offset + 6)
                val frequencyType = raw.u8(offset + 7)
                val rates = if (frequencyType == 0) {
                    if (length < 14) return malformed("UAC1 continuous sample-rate range truncated at offset=$offset")
                    val min = raw.u24le(offset + 8)
                    val max = raw.u24le(offset + 11)
                    if (min <= 0 || max < min) return malformed("UAC1 continuous sample-rate range invalid")
                    UsbSampleRateSupport.Ranges(listOf(UsbSampleRateRange(min, max, 1)))
                } else {
                    val expected = 8 + frequencyType * 3
                    if (length < expected) return malformed("UAC1 discrete sample rates truncated at offset=$offset")
                    val values = (0 until frequencyType).map { raw.u24le(offset + 8 + it * 3) }.toSet()
                    if (values.any { it <= 0 }) return malformed("UAC1 discrete sample rate contains zero")
                    if (values.size == 1) UsbSampleRateSupport.Fixed(values.first())
                    else UsbSampleRateSupport.Discrete(values)
                }
                alternate.format = UsbParsedTypeIPcmFormat(
                    channelCount = channels,
                    subslotBytes = subslotBytes,
                    bitResolution = bitResolution,
                    sampleRates = rates,
                )
            }
        }
        return null
    }

    private fun parseUac2StreamingDescriptor(
        raw: ByteArray,
        offset: Int,
        length: Int,
        alternate: MutableStreamingAlternate,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (length < 3) return malformed("UAC2 class interface descriptor too short at offset=$offset")
        when (raw.u8(offset + 2)) {
            AS_GENERAL -> {
                if (length < 16) return malformed("UAC2 AS_GENERAL too short at offset=$offset")
                alternate.terminalLink = raw.u8(offset + 3)
                val formatType = raw.u8(offset + 5)
                val formats = raw.u32le(offset + 6)
                alternate.rawFormatType = formatType
                alternate.uac2FormatsBitmap = formats
                alternate.formatIsPcm = formatType == FORMAT_TYPE_I && formats and UAC2_FORMAT_PCM_BIT != 0L
                alternate.uac2ChannelCount = raw.u8(offset + 10)
            }

            FORMAT_TYPE -> {
                if (length < 6) return malformed("UAC2 FORMAT_TYPE too short at offset=$offset")
                if (raw.u8(offset + 3) != FORMAT_TYPE_I) {
                    alternate.formatIsPcm = false
                    return null
                }
                alternate.format = UsbParsedTypeIPcmFormat(
                    channelCount = alternate.uac2ChannelCount ?: 0,
                    subslotBytes = raw.u8(offset + 4),
                    bitResolution = raw.u8(offset + 5),
                    sampleRates = UsbSampleRateSupport.Unverified,
                )
            }
        }
        return null
    }

    private fun parseUac2ControlDescriptor(
        raw: ByteArray,
        offset: Int,
        length: Int,
        clockEntities: MutableMap<Int, UsbUac2ClockEntity>,
        terminalClockLinks: MutableMap<Int, Int>,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (length < 3) return malformed("UAC2 control descriptor too short at offset=$offset")
        val subtype = raw.u8(offset + 2)
        when (subtype) {
            UAC2_CLOCK_SOURCE -> {
                if (length < 8) return malformed("UAC2 ClockSource too short at offset=$offset")
                val entity = UsbUac2ClockEntity.Source(
                    id = raw.u8(offset + 3),
                    attributes = raw.u8(offset + 4),
                    controls = raw.u8(offset + 5),
                )
                putUniqueClock(clockEntities, entity)?.let { return it }
            }

            UAC2_CLOCK_SELECTOR -> {
                if (length < 7) return malformed("UAC2 ClockSelector too short at offset=$offset")
                val pins = raw.u8(offset + 4)
                if (length < 7 + pins) return malformed("UAC2 ClockSelector sources truncated at offset=$offset")
                val entity = UsbUac2ClockEntity.Selector(
                    id = raw.u8(offset + 3),
                    sourceIds = (0 until pins).map { raw.u8(offset + 5 + it) },
                    controls = raw.u8(offset + 5 + pins),
                )
                putUniqueClock(clockEntities, entity)?.let { return it }
            }

            UAC2_CLOCK_MULTIPLIER -> {
                if (length < 7) return malformed("UAC2 ClockMultiplier too short at offset=$offset")
                val entity = UsbUac2ClockEntity.Multiplier(
                    id = raw.u8(offset + 3),
                    sourceId = raw.u8(offset + 4),
                    controls = raw.u8(offset + 5),
                )
                putUniqueClock(clockEntities, entity)?.let { return it }
            }

            UAC2_INPUT_TERMINAL -> {
                if (length < 17) return malformed("UAC2 InputTerminal too short at offset=$offset")
                putUniqueTerminalClock(
                    terminalClockLinks,
                    terminalId = raw.u8(offset + 3),
                    clockEntityId = raw.u8(offset + 7),
                )?.let { return it }
            }

            UAC2_OUTPUT_TERMINAL -> {
                if (length < 12) return malformed("UAC2 OutputTerminal too short at offset=$offset")
                putUniqueTerminalClock(
                    terminalClockLinks,
                    terminalId = raw.u8(offset + 3),
                    clockEntityId = raw.u8(offset + 8),
                )?.let { return it }
            }
        }
        return null
    }

    private fun parseClassEndpoint(
        raw: ByteArray,
        offset: Int,
        length: Int,
        alternate: MutableStreamingAlternate,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (length < 3 || raw.u8(offset + 2) != EP_GENERAL) return null
        val samplingFrequencyControl = when (alternate.protocol) {
            UsbAudioProtocol.UAC1 -> {
                if (length < 7) return malformed("UAC1 class endpoint descriptor too short at offset=$offset")
                raw.u8(offset + 3) and 0x01 != 0
            }
            UsbAudioProtocol.UAC2 -> {
                if (length < 8) return malformed("UAC2 class endpoint descriptor too short at offset=$offset")
                false
            }
        }
        if (alternate.endpoints.isEmpty()) {
            return malformed("class endpoint descriptor has no preceding standard endpoint")
        }
        val index = alternate.endpoints.lastIndex
        alternate.endpoints[index] = alternate.endpoints[index].copy(
            samplingFrequencyControl = samplingFrequencyControl,
        )
        return null
    }

    private fun putUniqueClock(
        entities: MutableMap<Int, UsbUac2ClockEntity>,
        entity: UsbUac2ClockEntity,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (entity.id == 0) return malformed("UAC2 clock entity id 0 is invalid")
        if (entities.putIfAbsent(entity.id, entity) != null) {
            return rejected(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, "duplicate UAC2 clock entity id=${entity.id}")
        }
        return null
    }

    private fun putUniqueTerminalClock(
        links: MutableMap<Int, Int>,
        terminalId: Int,
        clockEntityId: Int,
    ): UsbAudioDescriptorParseResult.Rejected? {
        if (terminalId == 0 || clockEntityId == 0) return malformed("UAC2 terminal/clock id 0 is invalid")
        if (links.putIfAbsent(terminalId, clockEntityId) != null) {
            return rejected(UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY, "duplicate UAC2 terminal id=$terminalId")
        }
        return null
    }

    private fun protocolFromInterface(value: Int): UsbAudioProtocol? = when (value) {
        UAC1_PROTOCOL -> UsbAudioProtocol.UAC1
        UAC2_PROTOCOL -> UsbAudioProtocol.UAC2
        else -> null
    }

    private fun unsupportedProtocol(value: Int) = rejected(
        UsbAudioRejectionCode.UNSUPPORTED_PROTOCOL,
        "unsupported Audio interface protocol=0x${value.toString(16)}",
    )

    private fun malformed(detail: String) = rejected(UsbAudioRejectionCode.MALFORMED_DESCRIPTOR, detail)

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        UsbAudioDescriptorParseResult.Rejected(UsbAudioRejection(code, detail))

    private class MutableStreamingAlternate(
        val protocol: UsbAudioProtocol,
        val interfaceNumber: Int,
        val alternateSetting: Int,
    ) {
        var terminalLink: Int? = null
        var formatIsPcm: Boolean = false
        var format: UsbParsedTypeIPcmFormat? = null
        var uac1FormatTag: Int? = null
        var rawFormatType: Int? = null
        var uac2FormatsBitmap: Long? = null
        var uac2ChannelCount: Int? = null
        val endpoints = mutableListOf<UsbParsedEndpoint>()

        fun freeze(): UsbParsedStreamingAlternate {
            val parsedFormat = format?.let {
                if (protocol == UsbAudioProtocol.UAC2 && it.channelCount == 0 && uac2ChannelCount != null) {
                    it.copy(channelCount = checkNotNull(uac2ChannelCount))
                } else {
                    it
                }
            }
            val rawFormatIdentity = when (protocol) {
                UsbAudioProtocol.UAC1 -> uac1FormatTag?.let {
                    UsbRawStreamingFormatIdentity.Uac1(
                        formatTag = it,
                        formatType = rawFormatType,
                    )
                }
                UsbAudioProtocol.UAC2 -> {
                    val formatType = rawFormatType
                    val formatsBitmap = uac2FormatsBitmap
                    if (formatType != null && formatsBitmap != null) {
                        UsbRawStreamingFormatIdentity.Uac2(
                            formatType = formatType,
                            formatsBitmap = formatsBitmap,
                        )
                    } else {
                        null
                    }
                }
            }
            return UsbParsedStreamingAlternate(
                protocol = protocol,
                interfaceNumber = interfaceNumber,
                alternateSetting = alternateSetting,
                terminalLink = terminalLink,
                formatIsPcm = formatIsPcm,
                format = parsedFormat,
                endpoints = endpoints.toList(),
                rawFormatIdentity = rawFormatIdentity,
            )
        }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
    private fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u24le(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16)
    private fun ByteArray.u32le(offset: Int): Long =
        u16le(offset).toLong() or (u16le(offset + 2).toLong() shl 16)

    private const val USB_DT_INTERFACE = 0x04
    private const val USB_DT_ENDPOINT = 0x05
    private const val CS_INTERFACE = 0x24
    private const val CS_ENDPOINT = 0x25
    private const val USB_CLASS_AUDIO = 0x01
    private const val AUDIO_SUBCLASS_CONTROL = 0x01
    private const val AUDIO_SUBCLASS_STREAMING = 0x02
    private const val UAC1_PROTOCOL = 0x00
    private const val UAC2_PROTOCOL = 0x20
    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01
    private const val EP_GENERAL = 0x01
    private const val UAC_FORMAT_PCM = 0x0001
    private const val UAC2_FORMAT_PCM_BIT = 0x00000001L
    private const val UAC2_INPUT_TERMINAL = 0x02
    private const val UAC2_OUTPUT_TERMINAL = 0x03
    private const val UAC2_CLOCK_SOURCE = 0x0a
    private const val UAC2_CLOCK_SELECTOR = 0x0b
    private const val UAC2_CLOCK_MULTIPLIER = 0x0c
}
