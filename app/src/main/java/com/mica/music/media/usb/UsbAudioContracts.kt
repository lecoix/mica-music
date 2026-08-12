package com.mica.music.media.usb

import java.nio.ByteBuffer

/** Stable identity survives reconnect; [runtimeDeviceId] never participates in equality. */
internal data class UsbAudioDeviceIdentity(
    val vendorId: Int,
    val productId: Int,
    val descriptorFingerprint: String,
    val serialNumber: String? = null,
    val topologyHint: String? = null,
    val bcdDevice: Int? = null,
) {
    init {
        require(bcdDevice == null || bcdDevice in 0..0xffff)
    }
}

/** Handle valid only for one Android USB enumeration. */
internal data class UsbAudioRuntimeHandle(
    val runtimeDeviceId: Int,
)

internal enum class UsbPermissionState {
    UNKNOWN,
    REQUESTED,
    GRANTED,
    DENIED,
}

internal data class UsbAudioDeviceSnapshot(
    val identity: UsbAudioDeviceIdentity,
    val runtimeHandle: UsbAudioRuntimeHandle,
    val permission: UsbPermissionState,
)

internal enum class UsbPcmEncoding {
    PCM_16,
    PCM_24_PACKED,
    PCM_24_IN_32,
    PCM_32,
}

internal data class UsbSampleRateRange(
    val minHz: Int,
    val maxHz: Int,
    val resolutionHz: Int,
) {
    init {
        require(minHz > 0)
        require(maxHz >= minHz)
        require(resolutionHz > 0)
    }

    fun supports(sampleRateHz: Int): Boolean =
        sampleRateHz in minHz..maxHz && (sampleRateHz - minHz) % resolutionHz == 0
}

internal sealed interface UsbSampleRateSupport {
    fun supports(sampleRateHz: Int): Boolean

    data object Unverified : UsbSampleRateSupport {
        override fun supports(sampleRateHz: Int): Boolean = false
    }

    data class Fixed(val sampleRateHz: Int) : UsbSampleRateSupport {
        init {
            require(sampleRateHz > 0)
        }

        override fun supports(sampleRateHz: Int): Boolean = sampleRateHz == this.sampleRateHz
    }

    data class Discrete(val sampleRatesHz: Set<Int>) : UsbSampleRateSupport {
        init {
            require(sampleRatesHz.isNotEmpty())
            require(sampleRatesHz.all { it > 0 })
        }

        override fun supports(sampleRateHz: Int): Boolean = sampleRateHz in sampleRatesHz
    }

    data class Ranges(val ranges: List<UsbSampleRateRange>) : UsbSampleRateSupport {
        init {
            require(ranges.isNotEmpty())
        }

        override fun supports(sampleRateHz: Int): Boolean = ranges.any { it.supports(sampleRateHz) }
    }
}

internal enum class UsbAudioProtocol {
    UAC1,
    UAC2,
}

internal enum class UsbBusSpeed {
    FULL,
    HIGH,
    SUPER,
    UNKNOWN,
}

internal enum class UsbEndpointSyncMode {
    ASYNCHRONOUS,
    ADAPTIVE,
    SYNCHRONOUS,
}

internal enum class UsbFeedbackMode {
    NONE,
    EXPLICIT,
    IMPLICIT,
}

internal enum class UsbFeedbackEncoding {
    UAC1_10_14,
    UAC2_16_16,
}

internal data class UsbFeedbackPlan(
    val mode: UsbFeedbackMode,
    val endpointAddress: Int? = null,
    val maxPacketBytes: Int? = null,
    val interval: Int? = null,
    val encoding: UsbFeedbackEncoding? = null,
)

internal sealed interface UsbClockPlan {
    data class Uac1Endpoint(
        val endpointAddress: Int,
        val samplingFrequencyControl: Boolean,
    ) : UsbClockPlan

    data class Uac2Entity(
        val sourceEntityId: Int,
        val entityPath: List<Int> = listOf(sourceEntityId),
        val rateMultiplierNumerator: Long = 1,
        val rateMultiplierDenominator: Long = 1,
    ) : UsbClockPlan
}

internal data class UsbEndpointCapacityEvidence(
    val maxPacketBytes: Int,
    val bytesPerAudioFrame: Int,
    val maxFramesPerServiceInterval: Int,
)

internal data class UsbInterfaceClaimPlan(
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumber: Int,
    val alternateSetting: Int,
)

internal data class UsbAudioFunction(
    val protocol: UsbAudioProtocol,
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumbers: Set<Int>,
)

internal enum class UsbAudioRejectionCode {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_SAMPLE_RATE,
    UNSUPPORTED_CHANNEL_COUNT,
    UNSUPPORTED_SIGNAL_POLICY,
    UNPROVEN_CLOCK_PATH,
    CLOCK_INVALID,
    RATE_CONTROL_FAILED,
    RATE_READBACK_MISMATCH,
    ENDPOINT_CAPACITY_INSUFFICIENT,
    UNSUPPORTED_FEEDBACK_TOPOLOGY,
    IMPLICIT_FEEDBACK_UNSUPPORTED,
    INTERFACE_CLAIM_UNPROVEN,
    AMBIGUOUS_TOPOLOGY,
    ENDPOINT_SHAPE_MISMATCH,
    DEVICE_IDENTITY_MISMATCH,
    MALFORMED_DESCRIPTOR,
}

internal data class UsbAudioRejection(
    val code: UsbAudioRejectionCode,
    val detail: String,
) {
    override fun toString(): String = "${code.name}: $detail"
}

internal data class UsbPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: UsbPcmEncoding,
)

internal data class UsbAudioStreamingProfile(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val feedbackEndpointAddress: Int?,
    val feedbackMaxPacketBytes: Int?,
    val feedbackInterval: Int?,
    val channelCount: Int,
    val encoding: UsbPcmEncoding,
    val subslotBytes: Int,
    val bitResolution: Int,
    val sampleRates: UsbSampleRateSupport,
    val maxPacketBytes: Int,
    val interval: Int,
    val syncMode: UsbEndpointSyncMode = UsbEndpointSyncMode.ASYNCHRONOUS,
    val feedbackPlan: UsbFeedbackPlan = UsbFeedbackPlan(UsbFeedbackMode.NONE),
    val clockPlan: UsbClockPlan? = null,
    val capacityEvidence: UsbEndpointCapacityEvidence? = null,
    val claimPlan: UsbInterfaceClaimPlan? = null,
)

internal data class UsbAudioEndpointShape(
    val address: Int,
    val transferType: Int,
    val maxPacketBytes: Int,
    val interval: Int,
)

internal sealed interface UsbStreamingProfileValidation {
    data object Valid : UsbStreamingProfileValidation

    data class Rejected(val rejection: UsbAudioRejection) : UsbStreamingProfileValidation {
        val reason: String get() = rejection.toString()
    }
}

internal data class UsbAudioCapability(
    val identity: UsbAudioDeviceIdentity,
    val uacVersion: Int,
    val audioControlInterface: Int,
    val clockSourceId: Int?,
    val streamingProfiles: List<UsbAudioStreamingProfile>,
    val audioFunction: UsbAudioFunction? = null,
    val busSpeed: UsbBusSpeed = UsbBusSpeed.UNKNOWN,
    val rejectReason: UsbAudioRejection? = null,
)

internal enum class UsbSignalPolicy {
    /** No resampling, bit-depth reduction, digital gain, EQ, ReplayGain, or Sonic. */
    EXACT_ONLY,
}

internal data class UsbOutputRequest(
    val device: UsbAudioDeviceIdentity,
    val sourceFormat: UsbPcmFormat? = null,
    val signalPolicy: UsbSignalPolicy = UsbSignalPolicy.EXACT_ONLY,
)

internal sealed interface UsbFormatDecision {
    data class Accepted(
        val requestedFormat: UsbPcmFormat,
        val deviceFormat: UsbPcmFormat,
        val streamingProfile: UsbAudioStreamingProfile,
        val signalExact: Boolean,
    ) : UsbFormatDecision

    data class Rejected(val rejection: UsbAudioRejection) : UsbFormatDecision {
        val reason: String get() = rejection.toString()
    }
}

internal interface UsbAudioDeviceRepository {
    fun snapshot(): List<UsbAudioDeviceSnapshot>
}

internal interface UsbAudioCapabilityParser<in Descriptor> {
    fun parse(identity: UsbAudioDeviceIdentity, descriptor: Descriptor): UsbAudioCapability
}

internal interface UsbFormatNegotiator {
    fun negotiate(
        source: UsbPcmFormat,
        capability: UsbAudioCapability,
        signalPolicy: UsbSignalPolicy,
    ): UsbFormatDecision
}

/** Native/USBFS boundary. Implementations own one bounded URB queue. */
internal interface UsbIsochronousTransport {
    fun start(lease: UsbOutputRequestLease)
    fun write(source: ByteBuffer, lease: UsbOutputRequestLease): Int
    fun stop(lease: UsbOutputCleanupLease)
}

/** Session resources that must only be mutated by [UsbOutputSessionOwner]. */
internal interface UsbOutputSession {
    val activeFacts: PlaybackOutputFacts

    fun restart(lease: UsbOutputRequestLease)

    fun release(lease: UsbOutputCleanupLease, reason: String)
}
