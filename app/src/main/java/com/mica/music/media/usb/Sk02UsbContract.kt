package com.mica.music.media.usb

/** P1 is deliberately one-device: facts below come from the checked-in SK02 probe evidence. */
internal object Sk02UsbContract : UsbFormatNegotiator {
    val identity = UsbAudioDeviceIdentity(
        vendorId = 0x262a,
        productId = 0x0001,
        descriptorFingerprint = "262a:0001:rev0004:uac2:ac1-as2",
    )

    val capability = UsbAudioCapability(
        identity = identity,
        uacVersion = 2,
        audioControlInterface = 1,
        clockSourceId = 1,
        streamingProfiles = listOf(
            profile(alt = 1, encoding = UsbPcmEncoding.PCM_16, maxPacketBytes = 200),
            profile(alt = 2, encoding = UsbPcmEncoding.PCM_24_PACKED, maxPacketBytes = 300),
            profile(alt = 3, encoding = UsbPcmEncoding.PCM_32, maxPacketBytes = 400),
        ),
        audioFunction = UsbAudioFunction(
            protocol = UsbAudioProtocol.UAC2,
            controlInterfaceNumber = 1,
            streamingInterfaceNumbers = setOf(2),
        ),
        busSpeed = UsbBusSpeed.HIGH,
    )

    override fun negotiate(
        source: UsbPcmFormat,
        capability: UsbAudioCapability,
        signalPolicy: UsbSignalPolicy,
    ): UsbFormatDecision {
        if (capability.identity != identity) {
            return UsbFormatDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.DEVICE_IDENTITY_MISMATCH,
                    "SK02 golden contract received a different device identity",
                ),
            )
        }
        if (signalPolicy != UsbSignalPolicy.EXACT_ONLY) {
            return UsbFormatDecision.Rejected(
                UsbAudioRejection(
                    UsbAudioRejectionCode.UNSUPPORTED_SIGNAL_POLICY,
                    "SK02 golden contract exposes exact-only output",
                ),
            )
        }
        val profile = capability.streamingProfiles.firstOrNull {
            it.channelCount == source.channelCount &&
                it.encoding == source.encoding &&
                it.sampleRates.supports(source.sampleRateHz)
        } ?: return UsbFormatDecision.Rejected(
            UsbAudioRejection(
                UsbAudioRejectionCode.UNSUPPORTED_FORMAT,
                "SK02 has no exact profile for ${source.sampleRateHz}Hz/" +
                    "${source.channelCount}ch/${source.encoding}",
            ),
        )
        return UsbFormatDecision.Accepted(
            requestedFormat = source,
            deviceFormat = UsbPcmFormat(
                sampleRateHz = source.sampleRateHz,
                channelCount = profile.channelCount,
                encoding = profile.encoding,
            ),
            streamingProfile = profile,
            signalExact = true,
        )
    }

    fun profileFor(format: UsbPcmFormat): UsbAudioStreamingProfile? =
        capability.streamingProfiles.firstOrNull {
            it.channelCount == format.channelCount &&
                it.encoding == format.encoding &&
                it.sampleRates.supports(format.sampleRateHz)
        }

    fun validateRuntimeEndpoints(
        profile: UsbAudioStreamingProfile,
        endpoints: List<UsbAudioEndpointShape>,
    ): UsbStreamingProfileValidation {
        val data = endpoints.singleOrNull { it.address == profile.endpointAddress }
            ?: return rejected("data endpoint 0x${profile.endpointAddress.toString(16)} is missing")
        if (data.transferType != ISOCHRONOUS_TRANSFER_TYPE) {
            return rejected("data endpoint is not isochronous")
        }
        if (data.maxPacketBytes != profile.maxPacketBytes || data.interval != profile.interval) {
            return rejected(
                "data endpoint shape ${data.maxPacketBytes}/${data.interval} != " +
                    "${profile.maxPacketBytes}/${profile.interval}",
            )
        }
        val feedbackAddress = profile.feedbackEndpointAddress
            ?: return UsbStreamingProfileValidation.Valid
        val feedback = endpoints.singleOrNull { it.address == feedbackAddress }
            ?: return rejected("feedback endpoint 0x${feedbackAddress.toString(16)} is missing")
        if (feedback.transferType != ISOCHRONOUS_TRANSFER_TYPE) {
            return rejected("feedback endpoint is not isochronous")
        }
        if (feedback.maxPacketBytes != profile.feedbackMaxPacketBytes ||
            feedback.interval != profile.feedbackInterval
        ) {
            return rejected(
                "feedback endpoint shape ${feedback.maxPacketBytes}/${feedback.interval} != " +
                    "${profile.feedbackMaxPacketBytes}/${profile.feedbackInterval}",
            )
        }
        return UsbStreamingProfileValidation.Valid
    }

    private fun profile(
        alt: Int,
        encoding: UsbPcmEncoding,
        maxPacketBytes: Int,
    ): UsbAudioStreamingProfile {
        val subslotBytes = when (encoding) {
            UsbPcmEncoding.PCM_16 -> 2
            UsbPcmEncoding.PCM_24_PACKED -> 3
            UsbPcmEncoding.PCM_24_IN_32 -> 4
            UsbPcmEncoding.PCM_32 -> 4
        }
        return UsbAudioStreamingProfile(
            interfaceNumber = 2,
            alternateSetting = alt,
            endpointAddress = 0x03,
            feedbackEndpointAddress = 0x84,
            feedbackMaxPacketBytes = 4,
            feedbackInterval = 4,
            channelCount = 2,
            encoding = encoding,
            subslotBytes = subslotBytes,
            bitResolution = if (encoding == UsbPcmEncoding.PCM_24_IN_32) 24 else subslotBytes * 8,
            sampleRates = UsbSampleRateSupport.Ranges(
                listOf(UsbSampleRateRange(minHz = 8_000, maxHz = 384_000, resolutionHz = 1)),
            ),
            maxPacketBytes = maxPacketBytes,
            interval = 1,
            syncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
            feedbackPlan = UsbFeedbackPlan(
                mode = UsbFeedbackMode.EXPLICIT,
                endpointAddress = 0x84,
                maxPacketBytes = 4,
                interval = 4,
                encoding = UsbFeedbackEncoding.UAC2_16_16,
            ),
            clockPlan = UsbClockPlan.Uac2Entity(sourceEntityId = 1),
            capacityEvidence = UsbEndpointCapacityEvidence(
                maxPacketBytes = maxPacketBytes,
                bytesPerAudioFrame = subslotBytes * 2,
                maxFramesPerServiceInterval = maxPacketBytes / (subslotBytes * 2),
            ),
            claimPlan = UsbInterfaceClaimPlan(
                controlInterfaceNumber = 1,
                streamingInterfaceNumber = 2,
                alternateSetting = alt,
            ),
        )
    }

    private fun rejected(reason: String) = UsbStreamingProfileValidation.Rejected(
        UsbAudioRejection(UsbAudioRejectionCode.ENDPOINT_SHAPE_MISMATCH, reason),
    )

    private const val ISOCHRONOUS_TRANSFER_TYPE = 1
}
