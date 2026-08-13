package com.mica.music.media.usb

import com.mica.music.media.dsd.DsdCarrierPlanner
import com.mica.music.media.dsd.DsdCarrierSourceFacts
import com.mica.music.media.dsd.DoPCarrierPlanningResult
import com.mica.music.media.dsd.ProvenDoPPackingEvidence
import com.mica.music.media.dsd.ProvenPcmStreamingFacts

internal enum class UsbDoPCarrierBridgeRejectionCode {
    NON_EXACT_PCM_DECISION,
    PROFILE_FORMAT_MISMATCH,
    RUNTIME_RATE_MISMATCH,
    ENDPOINT_MISMATCH,
    FRAME_GEOMETRY_MISMATCH,
    CAPACITY_EVIDENCE_MISSING,
    CAPACITY_EVIDENCE_MISMATCH,
    SERVICE_PERIOD_MISMATCH,
}

internal data class UsbDoPCarrierBridgeRejection(
    val code: UsbDoPCarrierBridgeRejectionCode,
    val detail: String,
)

internal sealed interface UsbDoPCarrierBridgeResult {
    data class PlannerResult(
        val pcmFacts: ProvenPcmStreamingFacts,
        val result: DoPCarrierPlanningResult,
    ) : UsbDoPCarrierBridgeResult

    data class Rejected(
        val rejection: UsbDoPCarrierBridgeRejection,
    ) : UsbDoPCarrierBridgeResult
}

/**
 * Pure P3 -> P5 factual adapter for an already-proven exact PCM streaming decision.
 *
 * This bridge validates only P3-owned factual consistency, copies those accepted facts into the
 * P5-owned [ProvenPcmStreamingFacts], and delegates all DoP-specific policy/math to
 * [DsdCarrierPlanner]. In particular it never infers 4-byte DoP slot placement from PCM encoding,
 * device identity, endpoint shape, or subslot size.
 */
internal object UsbDoPCarrierBridge {
    fun planDoP(
        decision: UsbFormatDecision.Accepted,
        transport: UsbTransportConfig,
        source: DsdCarrierSourceFacts,
        packingEvidence: ProvenDoPPackingEvidence? = null,
    ): UsbDoPCarrierBridgeResult {
        return when (val mapped = mapProvenPcmFacts(decision, transport)) {
            is MappingResult.Rejected -> UsbDoPCarrierBridgeResult.Rejected(mapped.rejection)
            is MappingResult.Ready -> UsbDoPCarrierBridgeResult.PlannerResult(
                pcmFacts = mapped.pcmFacts,
                result = DsdCarrierPlanner.planDoP(
                    source = source,
                    pcm = mapped.pcmFacts,
                    packingEvidence = packingEvidence,
                ),
            )
        }
    }

    private fun mapProvenPcmFacts(
        decision: UsbFormatDecision.Accepted,
        transport: UsbTransportConfig,
    ): MappingResult {
        if (!decision.signalExact || decision.requestedFormat != decision.deviceFormat) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.NON_EXACT_PCM_DECISION,
                "DoP bridge requires an exact source/device PCM decision",
            )
        }

        val deviceFormat = decision.deviceFormat
        val profile = decision.streamingProfile
        val expectedEncodingShape = expectedEncodingShape(deviceFormat.encoding)
        if (deviceFormat.sampleRateHz <= 0 ||
            profile.channelCount != deviceFormat.channelCount ||
            profile.encoding != deviceFormat.encoding ||
            profile.subslotBytes != expectedEncodingShape.subslotBytes ||
            profile.bitResolution != expectedEncodingShape.bitResolution ||
            !profile.sampleRates.supports(deviceFormat.sampleRateHz)
        ) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.PROFILE_FORMAT_MISMATCH,
                "accepted PCM/profile facts disagree: device=$deviceFormat " +
                    "profileChannels=${profile.channelCount} profileEncoding=${profile.encoding} " +
                    "subslot=${profile.subslotBytes} bitResolution=${profile.bitResolution}",
            )
        }

        if (transport.nominalRuntimeFrameRateHz != deviceFormat.sampleRateHz.toLong()) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.RUNTIME_RATE_MISMATCH,
                "transport runtime rate=${transport.nominalRuntimeFrameRateHz}, " +
                    "accepted PCM rate=${deviceFormat.sampleRateHz}",
            )
        }

        if (transport.dataEndpointAddress != profile.endpointAddress) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.ENDPOINT_MISMATCH,
                "transport endpoint=0x${transport.dataEndpointAddress.toString(16)}, " +
                    "profile endpoint=0x${profile.endpointAddress.toString(16)}",
            )
        }

        val evidence = profile.capacityEvidence
            ?: return rejected(
                UsbDoPCarrierBridgeRejectionCode.CAPACITY_EVIDENCE_MISSING,
                "accepted streaming profile lacks endpoint capacity evidence",
            )

        val physicalFrameBytes = checkedFrameBytes(profile.subslotBytes, profile.channelCount)
            ?: return rejected(
                UsbDoPCarrierBridgeRejectionCode.FRAME_GEOMETRY_MISMATCH,
                "PCM physical runtime-frame geometry overflows",
            )
        if (physicalFrameBytes != evidence.bytesPerAudioFrame ||
            physicalFrameBytes != transport.bytesPerRuntimeFrame
        ) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.FRAME_GEOMETRY_MISMATCH,
                "physical bytes/runtime-frame=$physicalFrameBytes, " +
                    "profile evidence=${evidence.bytesPerAudioFrame}, " +
                    "transport=${transport.bytesPerRuntimeFrame}",
            )
        }

        if (evidence.bytesPerAudioFrame <= 0 || evidence.maxPacketBytes <= 0 ||
            evidence.maxFramesPerServiceInterval <= 0
        ) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.CAPACITY_EVIDENCE_MISMATCH,
                "endpoint capacity evidence contains non-positive geometry",
            )
        }
        val evidenceMaxFrames = evidence.maxPacketBytes / evidence.bytesPerAudioFrame
        if (profile.maxPacketBytes != evidence.maxPacketBytes ||
            transport.dataMaxBytesPerServiceInterval != evidence.maxPacketBytes ||
            evidence.maxFramesPerServiceInterval != evidenceMaxFrames
        ) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.CAPACITY_EVIDENCE_MISMATCH,
                "profile max=${profile.maxPacketBytes}, evidence max=${evidence.maxPacketBytes}, " +
                    "transport max=${transport.dataMaxBytesPerServiceInterval}, " +
                    "evidence frames=${evidence.maxFramesPerServiceInterval}, calculated=$evidenceMaxFrames",
            )
        }

        val expectedServicePeriod = UsbTransportConfigBuilder.servicePeriodSeconds(
            transport.busSpeed,
            profile.interval,
        )
        if (expectedServicePeriod == null || expectedServicePeriod != transport.dataServicePeriodSeconds) {
            return rejected(
                UsbDoPCarrierBridgeRejectionCode.SERVICE_PERIOD_MISMATCH,
                "transport service period=${transport.dataServicePeriodSeconds}, " +
                    "profile/bus expected=$expectedServicePeriod",
            )
        }

        val pcmFacts = ProvenPcmStreamingFacts(
            runtimeFrameRateHz = transport.nominalRuntimeFrameRateHz,
            channelCount = profile.channelCount,
            subslotBytesPerChannel = profile.subslotBytes,
            bitResolution = profile.bitResolution,
            bytesPerRuntimeFrame = transport.bytesPerRuntimeFrame,
            maxBytesPerServiceInterval = transport.dataMaxBytesPerServiceInterval,
            servicePeriodNumeratorSeconds = transport.dataServicePeriodSeconds.numerator,
            servicePeriodDenominatorSeconds = transport.dataServicePeriodSeconds.denominator,
        )
        return MappingResult.Ready(pcmFacts)
    }

    private sealed interface MappingResult {
        data class Ready(val pcmFacts: ProvenPcmStreamingFacts) : MappingResult
        data class Rejected(val rejection: UsbDoPCarrierBridgeRejection) : MappingResult
    }

    private data class EncodingShape(
        val subslotBytes: Int,
        val bitResolution: Int,
    )

    private fun expectedEncodingShape(encoding: UsbPcmEncoding): EncodingShape = when (encoding) {
        UsbPcmEncoding.PCM_16 -> EncodingShape(subslotBytes = 2, bitResolution = 16)
        UsbPcmEncoding.PCM_24_PACKED -> EncodingShape(subslotBytes = 3, bitResolution = 24)
        UsbPcmEncoding.PCM_24_IN_32 -> EncodingShape(subslotBytes = 4, bitResolution = 24)
        UsbPcmEncoding.PCM_32 -> EncodingShape(subslotBytes = 4, bitResolution = 32)
    }

    private fun checkedFrameBytes(subslotBytes: Int, channelCount: Int): Int? {
        if (subslotBytes <= 0 || channelCount <= 0) return null
        val value = subslotBytes.toLong() * channelCount.toLong()
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun rejected(
        code: UsbDoPCarrierBridgeRejectionCode,
        detail: String,
    ) = MappingResult.Rejected(UsbDoPCarrierBridgeRejection(code, detail))
}
