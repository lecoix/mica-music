package com.mica.music.media.usb

internal data class UsbExactRatio(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(numerator > 0)
        require(denominator > 0)
        require(gcd(numerator, denominator) == 1L)
    }

    fun toWholeMicrosOrNull(): Long? {
        val scaled = checkedMultiply(numerator, MICROS_PER_SECOND) ?: return null
        return if (scaled % denominator == 0L) scaled / denominator else null
    }

    companion object {
        fun of(numerator: Long, denominator: Long): UsbExactRatio? {
            if (numerator <= 0 || denominator <= 0) return null
            val divisor = gcd(numerator, denominator)
            return UsbExactRatio(numerator / divisor, denominator / divisor)
        }

        private const val MICROS_PER_SECOND = 1_000_000L
    }
}

internal enum class UsbFeedbackRawTimeUnit {
    FRAMES_PER_USB_FRAME,
    FRAMES_PER_USB_MICROFRAME,
}

internal data class UsbTransportFeedbackConfig(
    val endpointAddress: Int,
    val endpointCapacityBytesPerServiceInterval: Int,
    val expectedPayloadBytes: Int,
    val fractionalBits: Int,
    val rawTimeUnit: UsbFeedbackRawTimeUnit,
    val rawToDataServiceIntervalScale: UsbExactRatio,
    val pollPeriodSeconds: UsbExactRatio,
    val requiredZeroMask: Long,
) {
    init {
        require(endpointAddress in 1..0xff && endpointAddress and 0x80 != 0)
        require(endpointCapacityBytesPerServiceInterval > 0)
        require(expectedPayloadBytes in 1..8)
        require(expectedPayloadBytes <= endpointCapacityBytesPerServiceInterval)
        require(fractionalBits in 1 until expectedPayloadBytes * Byte.SIZE_BITS)
        require(requiredZeroMask >= 0)
        val payloadBits = expectedPayloadBytes * Byte.SIZE_BITS
        if (payloadBits < Long.SIZE_BITS) {
            val payloadMask = (1L shl payloadBits) - 1L
            require(requiredZeroMask and payloadMask.inv() == 0L)
        }
    }

    val pollPeriodMicros: Long? get() = pollPeriodSeconds.toWholeMicrosOrNull()
}

internal data class UsbTransportConfig(
    val nominalRuntimeFrameRateHz: Long,
    val dataEndpointAddress: Int,
    val dataMaxBytesPerServiceInterval: Int,
    val bytesPerRuntimeFrame: Int,
    val busSpeed: UsbBusSpeed,
    val dataServicePeriodSeconds: UsbExactRatio,
    val syncMode: UsbEndpointSyncMode,
    val feedback: UsbTransportFeedbackConfig?,
    val packetsPerTransfer: Int,
    val dataQueueDepth: Int,
    val aheadWindowTargetSeconds: UsbExactRatio,
    val aheadWindowCoverageSeconds: UsbExactRatio,
) {
    init {
        require(nominalRuntimeFrameRateHz > 0)
        require(dataEndpointAddress in 1..0xff)
        require(dataEndpointAddress and 0x80 == 0)
        require(dataMaxBytesPerServiceInterval > 0)
        require(bytesPerRuntimeFrame > 0)
        require(busSpeed != UsbBusSpeed.UNKNOWN)
        require(packetsPerTransfer > 0)
        require(dataQueueDepth > 0)
        when (syncMode) {
            UsbEndpointSyncMode.ASYNCHRONOUS -> require(feedback != null) {
                "asynchronous USB transport requires explicit feedback"
            }
            UsbEndpointSyncMode.ADAPTIVE,
            UsbEndpointSyncMode.SYNCHRONOUS,
            -> require(feedback == null) {
                "$syncMode USB transport must not consume explicit feedback"
            }
        }
    }

    val dataServicePeriodMicros: Long? get() = dataServicePeriodSeconds.toWholeMicrosOrNull()
    val aheadWindowTargetMicros: Long? get() = aheadWindowTargetSeconds.toWholeMicrosOrNull()
    val aheadWindowCoverageMicros: Long? get() = aheadWindowCoverageSeconds.toWholeMicrosOrNull()
}

internal sealed interface UsbTransportConfigResult {
    data class Ready(val config: UsbTransportConfig) : UsbTransportConfigResult
    data class Rejected(val rejection: UsbAudioRejection) : UsbTransportConfigResult
}

/** Converts a proven exact PCM decision into a payload-agnostic runtime transport configuration. */
internal object UsbTransportConfigBuilder {
    private const val DEFAULT_PACKETS_PER_TRANSFER = 8
    private const val TARGET_AHEAD_WINDOW_MICROS = 16_000L

    fun build(
        decision: UsbFormatDecision.Accepted,
        busSpeed: UsbBusSpeed,
    ): UsbTransportConfigResult {
        if (!decision.signalExact || decision.requestedFormat != decision.deviceFormat) {
            return rejected(
                UsbAudioRejectionCode.UNSUPPORTED_SIGNAL_POLICY,
                "transport config requires an exact source/device format decision",
            )
        }
        if (busSpeed == UsbBusSpeed.UNKNOWN) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "transport cadence cannot be derived from unknown bus speed",
            )
        }
        if (decision.deviceFormat.sampleRateHz <= 0) {
            return rejected(
                UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE,
                "runtime frame rate must be positive",
            )
        }

        val profile = decision.streamingProfile
        val evidence = profile.capacityEvidence
            ?: return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "transport config requires endpoint capacity evidence",
            )
        val expectedFrameBytes = profile.subslotBytes * profile.channelCount
        if (expectedFrameBytes <= 0 || expectedFrameBytes != evidence.bytesPerAudioFrame) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "bytes/runtime-frame evidence does not match negotiated profile",
            )
        }
        if (profile.maxPacketBytes != evidence.maxPacketBytes || evidence.maxPacketBytes <= 0) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "max bytes/data-service-interval evidence does not match negotiated profile",
            )
        }
        if (profile.endpointAddress !in 1..0xff || profile.endpointAddress and 0x80 != 0) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_SHAPE_MISMATCH,
                "data endpoint address is invalid",
            )
        }

        val dataServicePeriod = servicePeriodSeconds(busSpeed, profile.interval)
            ?: return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "invalid data endpoint service interval=${profile.interval} for $busSpeed",
            )

        val feedback = when (profile.syncMode) {
            UsbEndpointSyncMode.ASYNCHRONOUS -> buildExplicitFeedback(
                plan = profile.feedbackPlan,
                busSpeed = busSpeed,
                dataServicePeriod = dataServicePeriod,
            ) ?: return rejected(
                feedbackRejectionCode(profile.feedbackPlan),
                "asynchronous transport lacks a proven factual feedback decode/normalization profile",
            )

            UsbEndpointSyncMode.ADAPTIVE,
            UsbEndpointSyncMode.SYNCHRONOUS,
            -> {
                if (profile.feedbackPlan.mode != UsbFeedbackMode.NONE) {
                    return rejected(
                        UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                        "${profile.syncMode} transport unexpectedly carries a feedback plan",
                    )
                }
                null
            }
        }

        val targetAheadWindow = UsbExactRatio.of(TARGET_AHEAD_WINDOW_MICROS, MICROS_PER_SECOND)
            ?: return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "invalid ahead-window target",
            )
        val queueDepth = minimumQueueDepthExact(
            aheadWindowTarget = targetAheadWindow,
            packetsPerTransfer = DEFAULT_PACKETS_PER_TRANSFER,
            servicePeriod = dataServicePeriod,
        )
        if (queueDepth <= 0) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "unable to derive a bounded native ahead window",
            )
        }
        val coverage = multiplyExact(
            dataServicePeriod,
            queueDepth.toLong() * DEFAULT_PACKETS_PER_TRANSFER.toLong(),
        ) ?: return rejected(
            UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
            "ahead-window coverage overflow",
        )

        return UsbTransportConfigResult.Ready(
            UsbTransportConfig(
                nominalRuntimeFrameRateHz = decision.deviceFormat.sampleRateHz.toLong(),
                dataEndpointAddress = profile.endpointAddress,
                dataMaxBytesPerServiceInterval = evidence.maxPacketBytes,
                bytesPerRuntimeFrame = evidence.bytesPerAudioFrame,
                busSpeed = busSpeed,
                dataServicePeriodSeconds = dataServicePeriod,
                syncMode = profile.syncMode,
                feedback = feedback,
                packetsPerTransfer = DEFAULT_PACKETS_PER_TRANSFER,
                dataQueueDepth = queueDepth,
                aheadWindowTargetSeconds = targetAheadWindow,
                aheadWindowCoverageSeconds = coverage,
            ),
        )
    }

    internal fun servicePeriodSeconds(busSpeed: UsbBusSpeed, interval: Int): UsbExactRatio? {
        if (interval !in 1..16) return null
        val intervalMultiplier = 1L shl (interval - 1)
        return when (busSpeed) {
            UsbBusSpeed.FULL -> UsbExactRatio.of(intervalMultiplier, 1_000L)
            UsbBusSpeed.HIGH, UsbBusSpeed.SUPER -> UsbExactRatio.of(intervalMultiplier, 8_000L)
            UsbBusSpeed.UNKNOWN -> null
        }
    }

    internal fun minimumQueueDepthExact(
        aheadWindowTarget: UsbExactRatio,
        packetsPerTransfer: Int,
        servicePeriod: UsbExactRatio,
    ): Int {
        if (packetsPerTransfer <= 0) return 0
        val numerator = checkedMultiply(aheadWindowTarget.numerator, servicePeriod.denominator) ?: return 0
        val packetPeriodNumerator = checkedMultiply(
            servicePeriod.numerator,
            packetsPerTransfer.toLong(),
        ) ?: return 0
        val denominator = checkedMultiply(aheadWindowTarget.denominator, packetPeriodNumerator) ?: return 0
        if (denominator <= 0) return 0
        val quotient = numerator / denominator
        val depth = quotient + if (numerator % denominator == 0L) 0L else 1L
        return depth.coerceAtLeast(1L).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0
    }

    private fun buildExplicitFeedback(
        plan: UsbFeedbackPlan,
        busSpeed: UsbBusSpeed,
        dataServicePeriod: UsbExactRatio,
    ): UsbTransportFeedbackConfig? {
        if (plan.mode != UsbFeedbackMode.EXPLICIT) return null
        val endpoint = plan.endpointAddress ?: return null
        val endpointCapacity = plan.maxPacketBytes ?: return null
        val interval = plan.interval ?: return null
        val encoding = plan.encoding ?: return null
        if (endpoint !in 1..0xff || endpoint and 0x80 == 0 || endpointCapacity <= 0) return null

        val decodeShape = when (encoding) {
            UsbFeedbackEncoding.UAC1_10_14 -> FeedbackDecodeShape(expectedPayloadBytes = 3, fractionalBits = 14)
            UsbFeedbackEncoding.UAC2_16_16 -> FeedbackDecodeShape(expectedPayloadBytes = 4, fractionalBits = 16)
        }
        if (endpointCapacity < decodeShape.expectedPayloadBytes) return null

        val rawUnit = when (busSpeed) {
            UsbBusSpeed.FULL -> UsbFeedbackRawTimeUnit.FRAMES_PER_USB_FRAME
            UsbBusSpeed.HIGH, UsbBusSpeed.SUPER -> UsbFeedbackRawTimeUnit.FRAMES_PER_USB_MICROFRAME
            UsbBusSpeed.UNKNOWN -> return null
        }
        val rawUnitPeriod = servicePeriodSeconds(busSpeed, interval = 1) ?: return null
        val rawToDataScale = divideExact(dataServicePeriod, rawUnitPeriod) ?: return null
        val pollPeriod = servicePeriodSeconds(busSpeed, interval) ?: return null

        return UsbTransportFeedbackConfig(
            endpointAddress = endpoint,
            endpointCapacityBytesPerServiceInterval = endpointCapacity,
            expectedPayloadBytes = decodeShape.expectedPayloadBytes,
            fractionalBits = decodeShape.fractionalBits,
            rawTimeUnit = rawUnit,
            rawToDataServiceIntervalScale = rawToDataScale,
            pollPeriodSeconds = pollPeriod,
            requiredZeroMask = 0L,
        )
    }

    private fun feedbackRejectionCode(plan: UsbFeedbackPlan): UsbAudioRejectionCode = when (plan.mode) {
        UsbFeedbackMode.IMPLICIT -> UsbAudioRejectionCode.IMPLICIT_FEEDBACK_UNSUPPORTED
        UsbFeedbackMode.NONE,
        UsbFeedbackMode.EXPLICIT,
        -> UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY
    }

    private data class FeedbackDecodeShape(
        val expectedPayloadBytes: Int,
        val fractionalBits: Int,
    )

    private const val MICROS_PER_SECOND = 1_000_000L

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        UsbTransportConfigResult.Rejected(UsbAudioRejection(code, detail))
}

private fun multiplyExact(ratio: UsbExactRatio, multiplier: Long): UsbExactRatio? {
    if (multiplier <= 0) return null
    val numerator = checkedMultiply(ratio.numerator, multiplier) ?: return null
    return UsbExactRatio.of(numerator, ratio.denominator)
}

private fun divideExact(left: UsbExactRatio, right: UsbExactRatio): UsbExactRatio? {
    val numerator = checkedMultiply(left.numerator, right.denominator) ?: return null
    val denominator = checkedMultiply(left.denominator, right.numerator) ?: return null
    return UsbExactRatio.of(numerator, denominator)
}

private fun checkedMultiply(left: Long, right: Long): Long? {
    if (left <= 0 || right <= 0) return null
    if (left > Long.MAX_VALUE / right) return null
    return left * right
}

private fun gcd(left: Long, right: Long): Long {
    var a = left
    var b = right
    while (b != 0L) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}
