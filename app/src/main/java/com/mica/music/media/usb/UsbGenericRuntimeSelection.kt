package com.mica.music.media.usb

internal sealed interface Uac2RuntimeClockEvidenceReadResult {
    data class Ready(val evidence: Uac2RuntimeClockEvidence) : Uac2RuntimeClockEvidenceReadResult
    data class Rejected(val rejection: UsbAudioRejection) : Uac2RuntimeClockEvidenceReadResult
}

/**
 * Reads only runtime facts already modeled by P3. Selector/multiplier graphs remain fail-closed
 * until an authoritative CUR/readback seam for those entity controls is added.
 */
internal object Uac2RuntimeClockEvidenceReader {
    fun read(
        facts: UsbParsedAudioDescriptorFacts,
        io: UsbAudioControlIo,
    ): Uac2RuntimeClockEvidenceReadResult {
        if (facts.audioFunction.protocol != UsbAudioProtocol.UAC2) {
            return Uac2RuntimeClockEvidenceReadResult.Ready(Uac2RuntimeClockEvidence())
        }
        val unsupportedGraphEntity = facts.uac2ClockEntities.values.firstOrNull {
            it is UsbUac2ClockEntity.Selector || it is UsbUac2ClockEntity.Multiplier
        }
        if (unsupportedGraphEntity != null) {
            return rejected(
                UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH,
                "UAC2 clock entity=${unsupportedGraphEntity.id} requires selector/multiplier runtime readback",
            )
        }
        val sources = facts.uac2ClockEntities.values.filterIsInstance<UsbUac2ClockEntity.Source>()
        if (sources.isEmpty()) {
            return rejected(UsbAudioRejectionCode.UNPROVEN_CLOCK_PATH, "UAC2 ClockSource is missing")
        }
        val controller = Uac2ClockRateController(
            io = io,
            audioControlInterface = facts.audioFunction.controlInterfaceNumber,
        )
        val rates = linkedMapOf<Int, UsbSampleRateSupport>()
        val valid = linkedSetOf<Int>()
        for (source in sources) {
            when (val validity = controller.readClockValidity(source.id)) {
                is UsbRateControlResult.Applied -> valid += source.id
                is UsbRateControlResult.Rejected -> return Uac2RuntimeClockEvidenceReadResult.Rejected(
                    validity.rejection,
                )
            }
            when (val range = controller.querySupportedRates(source.id)) {
                is UsbRateQueryResult.Supported -> rates[source.id] = range.sampleRates
                is UsbRateQueryResult.Rejected -> return Uac2RuntimeClockEvidenceReadResult.Rejected(
                    range.rejection,
                )
            }
        }
        return Uac2RuntimeClockEvidenceReadResult.Ready(
            Uac2RuntimeClockEvidence(
                sampleRatesByClockSourceId = rates,
                validClockSourceIds = valid,
            ),
        )
    }

    private fun rejected(code: UsbAudioRejectionCode, detail: String) =
        Uac2RuntimeClockEvidenceReadResult.Rejected(UsbAudioRejection(code, detail))
}

internal object UsbRuntimeStreamingProfileValidator {
    fun validate(
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

    private fun rejected(detail: String) = UsbStreamingProfileValidation.Rejected(
        UsbAudioRejection(UsbAudioRejectionCode.ENDPOINT_SHAPE_MISMATCH, detail),
    )

    private const val ISOCHRONOUS_TRANSFER_TYPE = 1
}

internal sealed interface UsbGenericPcmSelectionResult {
    data class Ready(
        val capability: UsbAudioCapability,
        val decision: UsbFormatDecision.Accepted,
        val transportConfig: UsbTransportConfig,
        val candidateRejections: List<UsbCandidateRejection>,
    ) : UsbGenericPcmSelectionResult

    data class Rejected(val rejection: UsbAudioRejection) : UsbGenericPcmSelectionResult
}

/** Parser policy stays upstream; this stage only builds candidates, exact-selects, and transports. */
internal object UsbGenericPcmSelection {
    fun select(
        source: UsbPcmFormat,
        identity: UsbAudioDeviceIdentity,
        facts: UsbParsedAudioDescriptorFacts,
        uac2ClockEvidence: Uac2RuntimeClockEvidence = Uac2RuntimeClockEvidence(),
    ): UsbGenericPcmSelectionResult {
        val report = UsbStreamingCandidateBuilder.build(
            identity = identity,
            facts = facts,
            uac2ClockEvidence = uac2ClockEvidence,
        )
        val decision = when (
            val result = GenericExactUsbFormatNegotiator.negotiate(
                source = source,
                capability = report.capability,
                signalPolicy = UsbSignalPolicy.EXACT_ONLY,
            )
        ) {
            is UsbFormatDecision.Accepted -> result
            is UsbFormatDecision.Rejected -> return UsbGenericPcmSelectionResult.Rejected(result.rejection)
        }
        val transport = when (
            val result = UsbTransportConfigBuilder.build(
                decision = decision,
                busSpeed = facts.busSpeed,
            )
        ) {
            is UsbTransportConfigResult.Ready -> result.config
            is UsbTransportConfigResult.Rejected -> return UsbGenericPcmSelectionResult.Rejected(result.rejection)
        }
        return UsbGenericPcmSelectionResult.Ready(
            capability = report.capability,
            decision = decision,
            transportConfig = transport,
            candidateRejections = report.rejections,
        )
    }
}
