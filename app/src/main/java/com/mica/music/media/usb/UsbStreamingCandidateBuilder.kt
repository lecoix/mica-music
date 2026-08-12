package com.mica.music.media.usb

internal data class Uac2RuntimeClockEvidence(
    val sampleRatesByClockSourceId: Map<Int, UsbSampleRateSupport> = emptyMap(),
    val selectorSelections: Map<Int, Int> = emptyMap(),
    val multiplierRatios: Map<Int, UsbClockMultiplierRatio> = emptyMap(),
    val validClockSourceIds: Set<Int> = emptySet(),
)

internal data class UsbCandidateRejection(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val rejection: UsbAudioRejection,
)

internal data class UsbCandidateBuildReport(
    val capability: UsbAudioCapability,
    val rejections: List<UsbCandidateRejection>,
)

internal object UsbStreamingCandidateBuilder {
    fun build(
        identity: UsbAudioDeviceIdentity,
        facts: UsbParsedAudioDescriptorFacts,
        uac2ClockEvidence: Uac2RuntimeClockEvidence = Uac2RuntimeClockEvidence(),
    ): UsbCandidateBuildReport {
        val accepted = mutableListOf<UsbAudioStreamingProfile>()
        val rejections = mutableListOf<UsbCandidateRejection>()

        facts.streamingAlternates
            .filter { it.alternateSetting != 0 }
            .forEach { alternate ->
                when (val decision = buildAlternate(facts, alternate, uac2ClockEvidence)) {
                    is AlternateDecision.Accepted -> accepted += decision.profile
                    is AlternateDecision.Rejected -> rejections += UsbCandidateRejection(
                        interfaceNumber = alternate.interfaceNumber,
                        alternateSetting = alternate.alternateSetting,
                        rejection = decision.rejection,
                    )
                }
            }

        val clockSourceIds = accepted.mapNotNull {
            (it.clockPlan as? UsbClockPlan.Uac2Entity)?.sourceEntityId
        }.distinct()
        val topLevelRejection = if (accepted.isEmpty()) {
            rejections.firstOrNull()?.rejection ?: UsbAudioRejection(
                UsbAudioRejectionCode.UNSUPPORTED_FORMAT,
                "no non-zero AudioStreaming alternate produced a candidate",
            )
        } else {
            null
        }
        return UsbCandidateBuildReport(
            capability = UsbAudioCapability(
                identity = identity,
                uacVersion = when (facts.audioFunction.protocol) {
                    UsbAudioProtocol.UAC1 -> 1
                    UsbAudioProtocol.UAC2 -> 2
                },
                audioControlInterface = facts.audioFunction.controlInterfaceNumber,
                clockSourceId = clockSourceIds.singleOrNull(),
                streamingProfiles = accepted,
                audioFunction = facts.audioFunction,
                busSpeed = facts.busSpeed,
                rejectReason = topLevelRejection,
            ),
            rejections = rejections,
        )
    }

    private fun buildAlternate(
        facts: UsbParsedAudioDescriptorFacts,
        alternate: UsbParsedStreamingAlternate,
        uac2ClockEvidence: Uac2RuntimeClockEvidence,
    ): AlternateDecision {
        if (!alternate.formatIsPcm) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_FORMAT, "alternate is not Type-I PCM")
        }
        val format = alternate.format
            ?: return rejected(UsbAudioRejectionCode.UNSUPPORTED_FORMAT, "Type-I format descriptor missing")
        if (format.channelCount <= 0) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_CHANNEL_COUNT, "channel count is not proven")
        }
        val encoding = pcmEncoding(format)
            ?: return rejected(
                UsbAudioRejectionCode.UNSUPPORTED_FORMAT,
                "unsupported PCM subslot=${format.subslotBytes} validBits=${format.bitResolution}",
            )
        if (facts.busSpeed == UsbBusSpeed.UNKNOWN) {
            return rejected(UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT, "USB bus speed is unknown")
        }

        val dataEndpoints = alternate.endpoints.filter {
            it.transferType == ISOCHRONOUS && !it.directionIn && it.usageTypeCode != USAGE_FEEDBACK
        }
        if (dataEndpoints.size != 1) {
            return rejected(
                UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
                "expected exactly one isochronous OUT data endpoint, found=${dataEndpoints.size}",
            )
        }
        val data = dataEndpoints.single()
        if (data.highBandwidthMultiplierCode == RESERVED_HIGH_BANDWIDTH_MULTIPLIER) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "endpoint uses reserved high-bandwidth multiplier code=3",
            )
        }
        val syncMode = when (data.syncTypeCode) {
            SYNC_ASYNCHRONOUS -> UsbEndpointSyncMode.ASYNCHRONOUS
            SYNC_ADAPTIVE -> UsbEndpointSyncMode.ADAPTIVE
            SYNC_SYNCHRONOUS -> UsbEndpointSyncMode.SYNCHRONOUS
            else -> return rejected(
                UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                "unsupported endpoint sync type=${data.syncTypeCode}",
            )
        }
        if (data.usageTypeCode == USAGE_IMPLICIT_FEEDBACK) {
            return rejected(
                UsbAudioRejectionCode.IMPLICIT_FEEDBACK_UNSUPPORTED,
                "implicit-feedback data endpoint is recognized but unsupported",
            )
        }

        val feedback = buildFeedbackPlan(alternate, data, syncMode)
        if (feedback is FeedbackDecision.Rejected) return AlternateDecision.Rejected(feedback.rejection)
        val feedbackPlan = (feedback as FeedbackDecision.Accepted).plan

        val clockDecision = when (alternate.protocol) {
            UsbAudioProtocol.UAC1 -> buildUac1Clock(data, format)
            UsbAudioProtocol.UAC2 -> buildUac2Clock(facts, alternate, uac2ClockEvidence)
        }
        if (clockDecision is ClockDecision.Rejected) return AlternateDecision.Rejected(clockDecision.rejection)
        val clockPlan = (clockDecision as ClockDecision.Accepted).plan
        val sampleRates = clockDecision.sampleRates

        val bytesPerFrame = format.subslotBytes * format.channelCount
        if (bytesPerFrame <= 0) {
            return rejected(UsbAudioRejectionCode.UNSUPPORTED_FORMAT, "bytes per audio frame is invalid")
        }
        val maxFrames = data.maxServiceIntervalBytes / bytesPerFrame
        if (maxFrames <= 0) {
            return rejected(
                UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "endpoint cannot carry one complete audio frame",
            )
        }

        return AlternateDecision.Accepted(
            UsbAudioStreamingProfile(
                interfaceNumber = alternate.interfaceNumber,
                alternateSetting = alternate.alternateSetting,
                endpointAddress = data.address,
                feedbackEndpointAddress = feedbackPlan.endpointAddress,
                feedbackMaxPacketBytes = feedbackPlan.maxPacketBytes,
                feedbackInterval = feedbackPlan.interval,
                channelCount = format.channelCount,
                encoding = encoding,
                subslotBytes = format.subslotBytes,
                bitResolution = format.bitResolution,
                sampleRates = sampleRates,
                maxPacketBytes = data.maxServiceIntervalBytes,
                interval = data.interval,
                syncMode = syncMode,
                feedbackPlan = feedbackPlan,
                clockPlan = clockPlan,
                capacityEvidence = UsbEndpointCapacityEvidence(
                    maxPacketBytes = data.maxServiceIntervalBytes,
                    bytesPerAudioFrame = bytesPerFrame,
                    maxFramesPerServiceInterval = maxFrames,
                ),
                claimPlan = UsbInterfaceClaimPlan(
                    controlInterfaceNumber = facts.audioFunction.controlInterfaceNumber,
                    streamingInterfaceNumber = alternate.interfaceNumber,
                    alternateSetting = alternate.alternateSetting,
                ),
            ),
        )
    }

    private fun buildFeedbackPlan(
        alternate: UsbParsedStreamingAlternate,
        data: UsbParsedEndpoint,
        syncMode: UsbEndpointSyncMode,
    ): FeedbackDecision {
        val feedbackEndpoints = alternate.endpoints.filter {
            it.transferType == ISOCHRONOUS && it.directionIn && it.usageTypeCode == USAGE_FEEDBACK
        }
        if (syncMode != UsbEndpointSyncMode.ASYNCHRONOUS) {
            return if (feedbackEndpoints.isEmpty() && data.synchAddress == null) {
                FeedbackDecision.Accepted(UsbFeedbackPlan(UsbFeedbackMode.NONE))
            } else {
                FeedbackDecision.Rejected(
                    UsbAudioRejection(
                        UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                        "$syncMode endpoint unexpectedly declares feedback association",
                    ),
                )
            }
        }
        if (feedbackEndpoints.isEmpty()) {
            return FeedbackDecision.Rejected(
                UsbAudioRejection(
                    if (data.synchAddress != null) {
                        UsbAudioRejectionCode.IMPLICIT_FEEDBACK_UNSUPPORTED
                    } else {
                        UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY
                    },
                    if (data.synchAddress != null) {
                        "asynchronous endpoint references non-local/implicit feedback 0x${data.synchAddress.toString(16)}"
                    } else {
                        "asynchronous endpoint has no explicit feedback endpoint"
                    },
                ),
            )
        }
        if (feedbackEndpoints.size != 1) {
            return FeedbackDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                    "multiple explicit feedback endpoints found=${feedbackEndpoints.size}",
                ),
            )
        }
        val endpoint = feedbackEndpoints.single()
        if (data.synchAddress != null && data.synchAddress != endpoint.address) {
            return FeedbackDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                    "bSynchAddress does not match the explicit feedback endpoint",
                ),
            )
        }
        val encoding = when (endpoint.packetPayloadBytes) {
            3 -> UsbFeedbackEncoding.UAC1_10_14
            4 -> UsbFeedbackEncoding.UAC2_16_16
            else -> return FeedbackDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_FEEDBACK_TOPOLOGY,
                    "unsupported feedback payload bytes=${endpoint.packetPayloadBytes}",
                ),
            )
        }
        return FeedbackDecision.Accepted(
            UsbFeedbackPlan(
                mode = UsbFeedbackMode.EXPLICIT,
                endpointAddress = endpoint.address,
                maxPacketBytes = endpoint.packetPayloadBytes,
                interval = endpoint.interval,
                encoding = encoding,
            ),
        )
    }

    private fun buildUac1Clock(
        data: UsbParsedEndpoint,
        format: UsbParsedTypeIPcmFormat,
    ): ClockDecision {
        val selectableRates = when (format.sampleRates) {
            is UsbSampleRateSupport.Fixed -> false
            UsbSampleRateSupport.Unverified -> true
            else -> true
        }
        if (selectableRates && !data.samplingFrequencyControl) {
            return ClockDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                    "UAC1 alternate has multiple/ranged rates but no endpoint sampling-frequency control",
                ),
            )
        }
        return ClockDecision.Accepted(
            plan = UsbClockPlan.Uac1Endpoint(
                endpointAddress = data.address,
                samplingFrequencyControl = data.samplingFrequencyControl,
            ),
            sampleRates = format.sampleRates,
        )
    }

    private fun buildUac2Clock(
        facts: UsbParsedAudioDescriptorFacts,
        alternate: UsbParsedStreamingAlternate,
        evidence: Uac2RuntimeClockEvidence,
    ): ClockDecision {
        val terminalLink = alternate.terminalLink
            ?: return ClockDecision.Rejected(
                UsbAudioRejection(UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH, "UAC2 terminalLink missing"),
            )
        val resolution = Uac2ClockGraphResolver.resolve(
            facts = facts,
            terminalLink = terminalLink,
            selectorSelections = evidence.selectorSelections,
            multiplierRatios = evidence.multiplierRatios,
            validClockSourceIds = evidence.validClockSourceIds,
        )
        if (resolution is Uac2ClockGraphResolution.Rejected) {
            return ClockDecision.Rejected(resolution.rejection)
        }
        val plan = (resolution as Uac2ClockGraphResolution.Resolved).plan
        if (plan.rateMultiplierNumerator != plan.rateMultiplierDenominator) {
            return ClockDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                    "non-unity UAC2 ClockMultiplier rate mapping is not yet proven end-to-end",
                ),
            )
        }
        val rates = evidence.sampleRatesByClockSourceId[plan.sourceEntityId]
            ?: return ClockDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE,
                    "UAC2 clock source=${plan.sourceEntityId} sample rates are unverified",
                ),
            )
        if (rates == UsbSampleRateSupport.Unverified) {
            return ClockDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE,
                    "UAC2 sample-rate evidence is explicitly unverified",
                ),
            )
        }
        return ClockDecision.Accepted(plan, rates)
    }

    private fun pcmEncoding(format: UsbParsedTypeIPcmFormat): UsbPcmEncoding? = when {
        format.subslotBytes == 2 && format.bitResolution == 16 -> UsbPcmEncoding.PCM_16
        format.subslotBytes == 3 && format.bitResolution == 24 -> UsbPcmEncoding.PCM_24_PACKED
        format.subslotBytes == 4 && format.bitResolution == 24 -> UsbPcmEncoding.PCM_24_IN_32
        format.subslotBytes == 4 && format.bitResolution == 32 -> UsbPcmEncoding.PCM_32
        else -> null
    }

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        AlternateDecision.Rejected(UsbAudioRejection(code, detail))

    private sealed interface AlternateDecision {
        data class Accepted(val profile: UsbAudioStreamingProfile) : AlternateDecision
        data class Rejected(val rejection: UsbAudioRejection) : AlternateDecision
    }

    private sealed interface FeedbackDecision {
        data class Accepted(val plan: UsbFeedbackPlan) : FeedbackDecision
        data class Rejected(val rejection: UsbAudioRejection) : FeedbackDecision
    }

    private sealed interface ClockDecision {
        val sampleRates: UsbSampleRateSupport

        data class Accepted(
            val plan: UsbClockPlan,
            override val sampleRates: UsbSampleRateSupport,
        ) : ClockDecision

        data class Rejected(val rejection: UsbAudioRejection) : ClockDecision {
            override val sampleRates: UsbSampleRateSupport = UsbSampleRateSupport.Unverified
        }
    }

    private const val ISOCHRONOUS = 1
    private const val SYNC_ASYNCHRONOUS = 1
    private const val SYNC_ADAPTIVE = 2
    private const val SYNC_SYNCHRONOUS = 3
    private const val USAGE_FEEDBACK = 1
    private const val USAGE_IMPLICIT_FEEDBACK = 2
    private const val RESERVED_HIGH_BANDWIDTH_MULTIPLIER = 3
}

internal object GenericExactUsbFormatNegotiator : UsbFormatNegotiator {
    override fun negotiate(
        source: UsbPcmFormat,
        capability: UsbAudioCapability,
        signalPolicy: UsbSignalPolicy,
    ): UsbFormatDecision {
        capability.rejectReason?.let { return UsbFormatDecision.Rejected(it) }
        if (signalPolicy != UsbSignalPolicy.EXACT_ONLY) {
            return UsbFormatDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_SIGNAL_POLICY,
                    "generic USB P3 negotiator is exact-only",
                ),
            )
        }
        val exactShapeAndRate = capability.streamingProfiles.filter {
            it.channelCount == source.channelCount &&
                it.encoding == source.encoding &&
                it.sampleRates.supports(source.sampleRateHz)
        }
        val exact = exactShapeAndRate.filter { hasCapacityFor(it, source.sampleRateHz, capability.busSpeed) }
        if (exact.size > 1) {
            return UsbFormatDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.AMBIGUOUS_TOPOLOGY,
                    "multiple equally exact USB streaming candidates=${exact.size}",
                ),
            )
        }
        exact.singleOrNull()?.let { profile ->
            return UsbFormatDecision.Accepted(
                requestedFormat = source,
                deviceFormat = source,
                streamingProfile = profile,
                signalExact = true,
            )
        }

        if (exactShapeAndRate.isNotEmpty()) {
            return UsbFormatDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                    "exact PCM/rate candidate exists but endpoint capacity is insufficient or unproven",
                ),
            )
        }

        val sameEncodingAndChannels = capability.streamingProfiles.filter {
            it.channelCount == source.channelCount && it.encoding == source.encoding
        }
        val rejection = when {
            sameEncodingAndChannels.isNotEmpty() -> UsbAudioRejection(
                UsbAudioRejectionCode.UNSUPPORTED_SAMPLE_RATE,
                "no proven exact candidate for sampleRate=${source.sampleRateHz}",
            )
            capability.streamingProfiles.any { it.channelCount == source.channelCount } -> UsbAudioRejection(
                UsbAudioRejectionCode.UNSUPPORTED_FORMAT,
                "no proven exact PCM container/valid-bit match for ${source.encoding}",
            )
            else -> UsbAudioRejection(
                UsbAudioRejectionCode.UNSUPPORTED_CHANNEL_COUNT,
                "no proven exact candidate for channels=${source.channelCount}",
            )
        }
        return UsbFormatDecision.Rejected(rejection)
    }

    internal fun hasCapacityFor(
        profile: UsbAudioStreamingProfile,
        sampleRateHz: Int,
        busSpeed: UsbBusSpeed,
    ): Boolean {
        val evidence = profile.capacityEvidence ?: return false
        val intervalMicros = serviceIntervalMicros(busSpeed, profile.interval) ?: return false
        val requiredFrames = ceilDiv(sampleRateHz.toLong() * intervalMicros, 1_000_000L)
        return requiredFrames <= evidence.maxFramesPerServiceInterval.toLong()
    }

    private fun serviceIntervalMicros(busSpeed: UsbBusSpeed, interval: Int): Long? {
        if (interval !in 1..16) return null
        val multiplier = 1L shl (interval - 1)
        return when (busSpeed) {
            UsbBusSpeed.FULL -> 1_000L * multiplier
            UsbBusSpeed.HIGH, UsbBusSpeed.SUPER -> 125L * multiplier
            UsbBusSpeed.UNKNOWN -> null
        }
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long =
        (numerator + denominator - 1) / denominator
}
