package com.mica.music.media.dsd

private const val UAC_FORMAT_TYPE_I = 0x01
private const val UAC2_FORMAT_RAW_DATA_BIT = 0x80000000L

/** Semantic DSD source facts. [dsdBitRateHz] is the one-bit rate per channel. */
data class DsdCarrierSourceFacts(
    val dsdBitRateHz: Long,
    val channelCount: Int,
) {
    init {
        require(dsdBitRateHz > 0L)
        require(channelCount > 0)
    }
}

/**
 * Facts from one already-proven PCM streaming choice.
 *
 * This intentionally does not mirror P3's capability/transport classes. An integration adapter can
 * copy the accepted factual values into this P5-owned planning input without making P5 depend on the
 * evolving USB implementation types.
 */
data class ProvenPcmStreamingFacts(
    val runtimeFrameRateHz: Long,
    val channelCount: Int,
    val subslotBytesPerChannel: Int,
    val bitResolution: Int,
    val bytesPerRuntimeFrame: Int,
    val maxBytesPerServiceInterval: Int,
    val servicePeriodNumeratorSeconds: Long,
    val servicePeriodDenominatorSeconds: Long,
) {
    init {
        require(runtimeFrameRateHz > 0L)
        require(channelCount > 0)
        require(subslotBytesPerChannel > 0)
        require(bitResolution > 0)
        require(bytesPerRuntimeFrame > 0)
        require(maxBytesPerServiceInterval > 0)
        require(servicePeriodNumeratorSeconds > 0L)
        require(servicePeriodDenominatorSeconds > 0L)
    }
}

/** Explicit proof for a 32-bit DoP carrier slot. Packed 24-bit needs no extra placement proof. */
data class ProvenDoPPackingEvidence(
    val packing: DoPCarrierPacking,
)

enum class DsdCarrierRejectionCode {
    SOURCE_RATE_NOT_EXACT,
    CHANNEL_MISMATCH,
    RUNTIME_RATE_MISMATCH,
    PCM_CARRIER_INCOMPATIBLE,
    DOP_PACKING_UNPROVEN,
    RUNTIME_FRAME_GEOMETRY_MISMATCH,
    ENDPOINT_EVIDENCE_INVALID,
    ENDPOINT_CAPACITY_INSUFFICIENT,
    RAW_DATA_ABSENT,
    NATIVE_GROUP_SIZE_UNSUPPORTED,
    CLOCK_RATE_UNPROVEN,
    CLOCK_RATE_MISMATCH,
}

data class DsdCarrierRejection(
    val code: DsdCarrierRejectionCode,
    val detail: String,
)

data class DoPCarrierPlan(
    val dsdBitRateHz: Long,
    val channelCount: Int,
    val runtimeFrameRateHz: Long,
    val bytesPerRuntimeFrame: Int,
    val packing: DoPCarrierPacking,
    val maxRuntimeFramesPerServiceInterval: Long,
    val requiredMaxBytesPerServiceInterval: Long,
)

sealed interface DoPCarrierPlanningResult {
    data class Ready(val plan: DoPCarrierPlan) : DoPCarrierPlanningResult
    data class Rejected(val rejection: DsdCarrierRejection) : DoPCarrierPlanningResult
}

/**
 * Factual RAW_DATA streaming evidence for one Native-DSD candidate probe.
 *
 * [provenRuntimeFrameRateHz] is the exact USB runtime-frame rate proven by the generic clock/rate
 * layer for this candidate, not the semantic DSD bit rate. RAW_DATA plus the physical subslot only
 * permits evaluation of a candidate byte-group geometry; it never selects endian/framing.
 */
data class NativeRawStreamingFacts(
    val formatType: Int,
    val formatsBitmap: Long,
    val channelCount: Int,
    val subslotBytesPerChannel: Int,
    val bitResolution: Int,
    val bytesPerRuntimeFrame: Int,
    val dataEndpointAddress: Int,
    val maxBytesPerServiceInterval: Int,
    val servicePeriodNumeratorSeconds: Long,
    val servicePeriodDenominatorSeconds: Long,
    val provenRuntimeFrameRateHz: Long?,
) {
    init {
        require(channelCount > 0)
        require(subslotBytesPerChannel > 0)
        require(bitResolution > 0)
        require(bytesPerRuntimeFrame > 0)
        require(maxBytesPerServiceInterval > 0)
        require(servicePeriodNumeratorSeconds > 0L)
        require(servicePeriodDenominatorSeconds > 0L)
        require(provenRuntimeFrameRateHz == null || provenRuntimeFrameRateHz > 0L)
    }
}

data class NativeDsdFramingUnprovenCandidate(
    val dsdBitRateHz: Long,
    val channelCount: Int,
    val runtimeFrameRateHz: Long,
    val bytesPerRuntimeFrame: Int,
    val bytesPerChannelGroup: Int,
    val maxRuntimeFramesPerServiceInterval: Long,
    val requiredMaxBytesPerServiceInterval: Long,
) {
    /** Deliberately no NativeDsdFraming field: endian/framing remains unqualified. */
    val framingProven: Boolean = false
}

sealed interface NativeDsdCarrierPlanningResult {
    data class FramingUnproven(
        val candidate: NativeDsdFramingUnprovenCandidate,
    ) : NativeDsdCarrierPlanningResult

    data class Rejected(
        val rejection: DsdCarrierRejection,
    ) : NativeDsdCarrierPlanningResult
}

/** Pure P5 carrier planner. No Android, USB parser, scheduler, JNI, or fallback behavior lives here. */
object DsdCarrierPlanner {
    fun planDoP(
        source: DsdCarrierSourceFacts,
        pcm: ProvenPcmStreamingFacts,
        packingEvidence: ProvenDoPPackingEvidence? = null,
    ): DoPCarrierPlanningResult {
        if (source.channelCount != pcm.channelCount) {
            return rejectedDoP(
                DsdCarrierRejectionCode.CHANNEL_MISMATCH,
                "source channels=${source.channelCount}, PCM carrier channels=${pcm.channelCount}",
            )
        }

        val runtimeRate = exactDiv(source.dsdBitRateHz, 16L)
            ?: return rejectedDoP(
                DsdCarrierRejectionCode.SOURCE_RATE_NOT_EXACT,
                "DSD bit rate ${source.dsdBitRateHz} is not exactly divisible by 16 for DoP",
            )
        if (pcm.runtimeFrameRateHz != runtimeRate) {
            return rejectedDoP(
                DsdCarrierRejectionCode.RUNTIME_RATE_MISMATCH,
                "DoP requires runtime rate=$runtimeRate Hz, proven PCM rate=${pcm.runtimeFrameRateHz} Hz",
            )
        }

        val physicalFrameBytes = checkedFrameBytes(pcm.subslotBytesPerChannel, pcm.channelCount)
            ?: return rejectedDoP(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "PCM physical frame geometry overflows",
            )
        if (physicalFrameBytes != pcm.bytesPerRuntimeFrame) {
            return rejectedDoP(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "PCM bytes/runtime-frame=${pcm.bytesPerRuntimeFrame}, physical=$physicalFrameBytes",
            )
        }

        val packing = when {
            pcm.subslotBytesPerChannel == 3 && pcm.bitResolution == 24 -> {
                if (packingEvidence != null && packingEvidence.packing != DoPCarrierPacking.PACKED_24_LE) {
                    return rejectedDoP(
                        DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                        "3-byte PCM carrier cannot use ${packingEvidence.packing}",
                    )
                }
                DoPCarrierPacking.PACKED_24_LE
            }

            pcm.subslotBytesPerChannel == 4 && pcm.bitResolution in setOf(24, 32) -> {
                val proven = packingEvidence?.packing
                    ?: return rejectedDoP(
                        DsdCarrierRejectionCode.DOP_PACKING_UNPROVEN,
                        "4-byte PCM carrier requires explicit DoP slot-placement evidence",
                    )
                if (proven.bytesPerChannel != 4) {
                    return rejectedDoP(
                        DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                        "4-byte PCM carrier cannot use $proven",
                    )
                }
                proven
            }

            else -> return rejectedDoP(
                DsdCarrierRejectionCode.PCM_CARRIER_INCOMPATIBLE,
                "DoP requires proven 24-bit packed or explicitly placed 24-in-32 carrier; " +
                    "got subslot=${pcm.subslotBytesPerChannel}, resolution=${pcm.bitResolution}",
            )
        }

        val requiredFrameBytes = checkedFrameBytes(packing.bytesPerChannel, source.channelCount)
            ?: return rejectedDoP(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "DoP runtime-frame geometry overflows",
            )
        if (requiredFrameBytes != pcm.bytesPerRuntimeFrame) {
            return rejectedDoP(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "DoP requires $requiredFrameBytes bytes/runtime-frame, PCM provides ${pcm.bytesPerRuntimeFrame}",
            )
        }

        val capacity = requiredCapacity(
            runtimeFrameRateHz = runtimeRate,
            bytesPerRuntimeFrame = requiredFrameBytes,
            servicePeriodNumeratorSeconds = pcm.servicePeriodNumeratorSeconds,
            servicePeriodDenominatorSeconds = pcm.servicePeriodDenominatorSeconds,
        ) ?: return rejectedDoP(
            DsdCarrierRejectionCode.ENDPOINT_EVIDENCE_INVALID,
            "DoP service-period/capacity arithmetic is not representable",
        )
        if (capacity.requiredBytes > pcm.maxBytesPerServiceInterval.toLong()) {
            return rejectedDoP(
                DsdCarrierRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "DoP requires ${capacity.requiredBytes} bytes/service-interval, " +
                    "endpoint proves ${pcm.maxBytesPerServiceInterval}",
            )
        }

        return DoPCarrierPlanningResult.Ready(
            DoPCarrierPlan(
                dsdBitRateHz = source.dsdBitRateHz,
                channelCount = source.channelCount,
                runtimeFrameRateHz = runtimeRate,
                bytesPerRuntimeFrame = requiredFrameBytes,
                packing = packing,
                maxRuntimeFramesPerServiceInterval = capacity.maxFrames,
                requiredMaxBytesPerServiceInterval = capacity.requiredBytes,
            ),
        )
    }

    fun planNativeCandidate(
        source: DsdCarrierSourceFacts,
        raw: NativeRawStreamingFacts,
    ): NativeDsdCarrierPlanningResult {
        if (source.channelCount != raw.channelCount) {
            return rejectedNative(
                DsdCarrierRejectionCode.CHANNEL_MISMATCH,
                "source channels=${source.channelCount}, RAW_DATA channels=${raw.channelCount}",
            )
        }
        if (raw.formatType != UAC_FORMAT_TYPE_I || raw.formatsBitmap and UAC2_FORMAT_RAW_DATA_BIT == 0L) {
            return rejectedNative(
                DsdCarrierRejectionCode.RAW_DATA_ABSENT,
                "UAC2 Type-I RAW_DATA evidence is absent",
            )
        }
        if (raw.dataEndpointAddress !in 1..0x7f) {
            return rejectedNative(
                DsdCarrierRejectionCode.ENDPOINT_EVIDENCE_INVALID,
                "Native candidate requires a proven OUT data endpoint",
            )
        }
        if (raw.subslotBytesPerChannel !in setOf(1, 2, 4)) {
            return rejectedNative(
                DsdCarrierRejectionCode.NATIVE_GROUP_SIZE_UNSUPPORTED,
                "P5 Native candidate encoders support 1/2/4-byte channel groups, got ${raw.subslotBytesPerChannel}",
            )
        }

        val physicalFrameBytes = checkedFrameBytes(raw.subslotBytesPerChannel, raw.channelCount)
            ?: return rejectedNative(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "RAW_DATA physical frame geometry overflows",
            )
        if (physicalFrameBytes != raw.bytesPerRuntimeFrame) {
            return rejectedNative(
                DsdCarrierRejectionCode.RUNTIME_FRAME_GEOMETRY_MISMATCH,
                "RAW_DATA bytes/runtime-frame=${raw.bytesPerRuntimeFrame}, physical=$physicalFrameBytes",
            )
        }

        val divisor = 8L * raw.subslotBytesPerChannel.toLong()
        val requiredRuntimeRate = exactDiv(source.dsdBitRateHz, divisor)
            ?: return rejectedNative(
                DsdCarrierRejectionCode.SOURCE_RATE_NOT_EXACT,
                "DSD bit rate ${source.dsdBitRateHz} is not exactly divisible by $divisor",
            )
        val provenRate = raw.provenRuntimeFrameRateHz
            ?: return rejectedNative(
                DsdCarrierRejectionCode.CLOCK_RATE_UNPROVEN,
                "Native candidate runtime clock rate has not been proven",
            )
        if (provenRate != requiredRuntimeRate) {
            return rejectedNative(
                DsdCarrierRejectionCode.CLOCK_RATE_MISMATCH,
                "candidate requires runtime rate=$requiredRuntimeRate Hz, proven clock rate=$provenRate Hz",
            )
        }

        val capacity = requiredCapacity(
            runtimeFrameRateHz = requiredRuntimeRate,
            bytesPerRuntimeFrame = physicalFrameBytes,
            servicePeriodNumeratorSeconds = raw.servicePeriodNumeratorSeconds,
            servicePeriodDenominatorSeconds = raw.servicePeriodDenominatorSeconds,
        ) ?: return rejectedNative(
            DsdCarrierRejectionCode.ENDPOINT_EVIDENCE_INVALID,
            "Native service-period/capacity arithmetic is not representable",
        )
        if (capacity.requiredBytes > raw.maxBytesPerServiceInterval.toLong()) {
            return rejectedNative(
                DsdCarrierRejectionCode.ENDPOINT_CAPACITY_INSUFFICIENT,
                "Native candidate requires ${capacity.requiredBytes} bytes/service-interval, " +
                    "endpoint proves ${raw.maxBytesPerServiceInterval}",
            )
        }

        return NativeDsdCarrierPlanningResult.FramingUnproven(
            NativeDsdFramingUnprovenCandidate(
                dsdBitRateHz = source.dsdBitRateHz,
                channelCount = source.channelCount,
                runtimeFrameRateHz = requiredRuntimeRate,
                bytesPerRuntimeFrame = physicalFrameBytes,
                bytesPerChannelGroup = raw.subslotBytesPerChannel,
                maxRuntimeFramesPerServiceInterval = capacity.maxFrames,
                requiredMaxBytesPerServiceInterval = capacity.requiredBytes,
            ),
        )
    }

    private fun rejectedDoP(code: DsdCarrierRejectionCode, detail: String) =
        DoPCarrierPlanningResult.Rejected(DsdCarrierRejection(code, detail))

    private fun rejectedNative(code: DsdCarrierRejectionCode, detail: String) =
        NativeDsdCarrierPlanningResult.Rejected(DsdCarrierRejection(code, detail))

    private data class CapacityRequirement(
        val maxFrames: Long,
        val requiredBytes: Long,
    )

    private fun requiredCapacity(
        runtimeFrameRateHz: Long,
        bytesPerRuntimeFrame: Int,
        servicePeriodNumeratorSeconds: Long,
        servicePeriodDenominatorSeconds: Long,
    ): CapacityRequirement? {
        if (
            runtimeFrameRateHz <= 0L || bytesPerRuntimeFrame <= 0 ||
            servicePeriodNumeratorSeconds <= 0L || servicePeriodDenominatorSeconds <= 0L
        ) {
            return null
        }
        val scaledFrames = checkedMultiplyPositive(runtimeFrameRateHz, servicePeriodNumeratorSeconds)
            ?: return null
        val maxFrames = ceilDivPositive(scaledFrames, servicePeriodDenominatorSeconds)
            ?: return null
        val requiredBytes = checkedMultiplyPositive(maxFrames, bytesPerRuntimeFrame.toLong())
            ?: return null
        return CapacityRequirement(maxFrames = maxFrames, requiredBytes = requiredBytes)
    }

    private fun checkedFrameBytes(bytesPerChannel: Int, channelCount: Int): Int? {
        if (bytesPerChannel <= 0 || channelCount <= 0 || bytesPerChannel > Int.MAX_VALUE / channelCount) return null
        return bytesPerChannel * channelCount
    }

    private fun exactDiv(value: Long, divisor: Long): Long? =
        if (value > 0L && divisor > 0L && value % divisor == 0L) value / divisor else null

    private fun checkedMultiplyPositive(left: Long, right: Long): Long? {
        if (left <= 0L || right <= 0L || left > Long.MAX_VALUE / right) return null
        return left * right
    }

    private fun ceilDivPositive(value: Long, divisor: Long): Long? {
        if (value <= 0L || divisor <= 0L) return null
        val whole = value / divisor
        return whole + if (value % divisor == 0L) 0L else 1L
    }
}
