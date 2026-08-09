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
    )

    override fun negotiate(
        source: UsbPcmFormat,
        capability: UsbAudioCapability,
        signalPolicy: UsbSignalPolicy,
    ): UsbFormatDecision {
        if (capability.identity != identity) {
            return UsbFormatDecision.Rejected("P1 supports only the proven Fosi Audio SK02")
        }
        if (signalPolicy != UsbSignalPolicy.EXACT_ONLY) {
            return UsbFormatDecision.Rejected("P1 exposes no processed USB signal policy")
        }
        val profile = capability.streamingProfiles.firstOrNull {
            it.channelCount == source.channelCount &&
                it.encoding == source.encoding &&
                source.sampleRateHz in it.sampleRateRangeHz
        } ?: return UsbFormatDecision.Rejected(
            "SK02 has no exact profile for ${source.sampleRateHz}Hz/" +
                "${source.channelCount}ch/${source.encoding}",
        )
        return UsbFormatDecision.Accepted(
            requestedFormat = source,
            deviceFormat = UsbPcmFormat(
                sampleRateHz = source.sampleRateHz,
                channelCount = profile.channelCount,
                encoding = profile.encoding,
            ),
            signalExact = true,
        )
    }

    fun profileFor(format: UsbPcmFormat): UsbAudioStreamingProfile? =
        capability.streamingProfiles.firstOrNull {
            it.channelCount == format.channelCount &&
                it.encoding == format.encoding &&
                format.sampleRateHz in it.sampleRateRangeHz
        }

    private fun profile(
        alt: Int,
        encoding: UsbPcmEncoding,
        maxPacketBytes: Int,
    ) = UsbAudioStreamingProfile(
        interfaceNumber = 2,
        alternateSetting = alt,
        endpointAddress = 0x03,
        feedbackEndpointAddress = 0x84,
        channelCount = 2,
        encoding = encoding,
        sampleRateRangeHz = 8_000..384_000,
        maxPacketBytes = maxPacketBytes,
        interval = 1,
    )
}
