package com.mica.music.media.usb

import java.nio.ByteBuffer

/** Stable identity survives reconnect; [runtimeDeviceId] never participates in equality. */
internal data class UsbAudioDeviceIdentity(
    val vendorId: Int,
    val productId: Int,
    val descriptorFingerprint: String,
    val serialNumber: String? = null,
    val topologyHint: String? = null,
)

/** Handle valid only for one Android USB enumeration. */
internal data class UsbAudioRuntimeHandle(
    val runtimeDeviceId: Int,
)

internal enum class UsbPcmEncoding {
    PCM_16,
    PCM_24_PACKED,
    PCM_32,
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
    val sampleRateRangeHz: IntRange,
    val maxPacketBytes: Int,
    val interval: Int,
)

internal data class UsbAudioEndpointShape(
    val address: Int,
    val transferType: Int,
    val maxPacketBytes: Int,
    val interval: Int,
)

internal sealed interface UsbStreamingProfileValidation {
    data object Valid : UsbStreamingProfileValidation

    data class Rejected(val reason: String) : UsbStreamingProfileValidation
}

internal data class UsbAudioCapability(
    val identity: UsbAudioDeviceIdentity,
    val uacVersion: Int,
    val audioControlInterface: Int,
    val clockSourceId: Int?,
    val streamingProfiles: List<UsbAudioStreamingProfile>,
    val rejectReason: String? = null,
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

    data class Rejected(val reason: String) : UsbFormatDecision
}

internal interface UsbAudioDeviceRepository {
    fun snapshot(): List<Pair<UsbAudioDeviceIdentity, UsbAudioRuntimeHandle>>
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
