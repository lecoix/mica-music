package com.mica.music.media.usbhybrid

@JvmInline
value class UsbRequestEpoch(val value: Long)

@JvmInline
value class UsbDiscoveryRevision(val value: Long)

data class UsbStableIdentity(
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int?,
    val descriptorDigest: String,
)

data class UsbRuntimeHandle(
    val deviceId: Int,
    val deviceName: String,
)

data class UsbTransportSessionId(
    val epoch: UsbRequestEpoch,
    val nativeId: Long,
)

enum class UsbExclusiveMode {
    SHARED_PCM,
    USB_EXACT_PCM,
    USB_DOP,
    USB_NATIVE_DSD_EXPERIMENTAL,
}

enum class PermissionState { NOT_REQUIRED, REQUESTED, GRANTED, DENIED }

data class UsbFailure(val code: String, val message: String)

data class UsbPlaybackFacts(
    val requestEpoch: Long = 0L,
    val discoveryRevision: Long = 0L,
    val requestedMode: UsbExclusiveMode = UsbExclusiveMode.SHARED_PCM,
    val activeMode: UsbExclusiveMode? = null,
    val identity: UsbStableIdentity? = null,
    val runtimeHandle: UsbRuntimeHandle? = null,
    val sessionId: Long? = null,
    val permission: PermissionState = PermissionState.NOT_REQUIRED,
    val claimed: Boolean = false,
    val exclusive: Boolean = false,
    val transportExact: Boolean = false,
    val signalExact: Boolean = false,
    val sourceEncoding: Int? = null,
    val usbBitResolution: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val failure: UsbFailure? = null,
)

data class UsbPermissionRequest(
    val epoch: UsbRequestEpoch,
    val mode: UsbExclusiveMode,
    val identity: UsbStableIdentity,
    val runtimeHandle: UsbRuntimeHandle,
)

data class UsbPermissionResult(
    val epoch: UsbRequestEpoch,
    val mode: UsbExclusiveMode,
    val identity: UsbStableIdentity,
    val runtimeHandle: UsbRuntimeHandle,
    val granted: Boolean,
)

data class UsbOpenRequest(
    val epoch: UsbRequestEpoch,
    val mode: UsbExclusiveMode,
    val identity: UsbStableIdentity,
    val runtimeHandle: UsbRuntimeHandle,
)

data class UsbOpenResult(
    val sessionId: UsbTransportSessionId,
    val claimed: Boolean,
)

interface UsbHybridControlEffects {
    /** Must not call back into [UsbHybridSessionOwner]. */
    fun publishActiveEpoch(epoch: UsbRequestEpoch)

    fun requestPermission(request: UsbPermissionRequest)

    fun open(request: UsbOpenRequest): UsbOpenResult

    fun close(sessionId: UsbTransportSessionId)
}
