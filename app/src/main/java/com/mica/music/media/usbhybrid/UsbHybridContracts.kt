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

sealed interface UsbTopologyEvent {
    data class Attached(
        val runtimeHandle: UsbRuntimeHandle,
        val hasAudioOutput: Boolean,
    ) : UsbTopologyEvent
    data class Detached(val runtimeHandle: UsbRuntimeHandle) : UsbTopologyEvent
}

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

/** The transport that is physically active for the current stream, independent of user policy. */
enum class UsbActiveTransport {
    PCM,
    DOP,
    NATIVE_DSD,
}

enum class PermissionState { NOT_REQUIRED, REQUESTED, GRANTED, DENIED }

data class UsbFailure(val code: String, val message: String)

data class UsbPlaybackFacts(
    val requestEpoch: Long = 0L,
    val discoveryRevision: Long = 0L,
    val requestedMode: UsbExclusiveMode = UsbExclusiveMode.SHARED_PCM,
    val activeMode: UsbExclusiveMode? = null,
    val activeTransport: UsbActiveTransport? = null,
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
    val streamFormat: String? = null,
    val telemetry: UsbRealtimeTelemetry? = null,
    val sessionDiagnostics: Map<String, Any?>? = null,
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
    val format: UsbStreamFormat,
)

sealed interface UsbStreamFormat {
    data class Pcm(val sampleRate: Int, val channels: Int, val bitDepth: Int) : UsbStreamFormat

    data class Dsd(val sampleRate: Int, val channels: Int, val native: Boolean) : UsbStreamFormat
}

data class UsbOpenResult(
    val sessionId: UsbTransportSessionId? = null,
    val claimed: Boolean = false,
    val failure: UsbFailure? = null,
    val transportExact: Boolean = false,
    val signalExact: Boolean = false,
    val sourceEncoding: Int? = null,
    val usbBitResolution: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val streamFormat: String? = null,
)

data class UsbRealtimeTelemetry(
    val pendingIsoPackets: Long,
    val totalIsoPackets: Long,
    val pendingOutputUrbs: Long,
    val isoErrorCount: Long,
)

interface UsbHybridRealtimePort {
    fun writePcm(sessionId: UsbTransportSessionId, data: ByteArray): UsbRealtimeResult

    fun beginPcmTimeline(sessionId: UsbTransportSessionId): UsbRealtimeResult = UsbRealtimeResult.Success

    /** Completed source frames only; excludes USB pre-roll, pause filler and tail padding. */
    fun consumedPcmSourceFrames(sessionId: UsbTransportSessionId): Long = 0L

    fun finishPcm(sessionId: UsbTransportSessionId): UsbRealtimeResult

    fun setVolume(sessionId: UsbTransportSessionId, gainQ16: Int): UsbRealtimeResult = UsbRealtimeResult.Success

    fun pausePcm(sessionId: UsbTransportSessionId): UsbRealtimeResult = UsbRealtimeResult.Success

    fun resumePcm(sessionId: UsbTransportSessionId): UsbRealtimeResult = UsbRealtimeResult.Success

    fun preparePcmSeek(sessionId: UsbTransportSessionId): UsbRealtimeResult {
        resetPcmForSeek(sessionId)
        return UsbRealtimeResult.Success
    }

    fun resetPcmForSeek(sessionId: UsbTransportSessionId)

    fun telemetry(sessionId: UsbTransportSessionId): UsbRealtimeTelemetry

    fun sessionDiagnostics(sessionId: UsbTransportSessionId): Map<String, Any?> = emptyMap()

    fun writeDsd(sessionId: UsbTransportSessionId, data: ByteArray): UsbRealtimeResult

    fun prepareDsdSeek(sessionId: UsbTransportSessionId): UsbRealtimeResult

    fun pauseDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult

    fun resumeDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult

    fun finishDsd(sessionId: UsbTransportSessionId): UsbRealtimeResult
}

sealed interface UsbRealtimeResult {
    data object Success : UsbRealtimeResult

    /** Native rejected an epoch/session that the control owner has already superseded. */
    data object Retired : UsbRealtimeResult

    data class Failed(val message: String) : UsbRealtimeResult
}

internal fun classifyUsbRealtimeResult(
    error: String?,
    sessionEpoch: Long,
    publishedEpoch: Long,
): UsbRealtimeResult = when {
    error == null -> UsbRealtimeResult.Success
    sessionEpoch != publishedEpoch -> UsbRealtimeResult.Retired
    error == com.afalphy.sylvakru.UsbExclusiveAudioTransport.STALE_SESSION_ERROR ->
        UsbRealtimeResult.Retired
    else -> UsbRealtimeResult.Failed(error)
}

internal fun isUsbRealtimeTransportUnavailableError(message: String): Boolean =
    message.contains("No such device", ignoreCase = true) ||
        message.contains("ENODEV", ignoreCase = true)

interface UsbHybridControlEffects {
    /** Must not call back into [UsbHybridSessionOwner]. */
    fun publishActiveEpoch(epoch: UsbRequestEpoch)

    fun requestPermission(request: UsbPermissionRequest)

    fun open(request: UsbOpenRequest): UsbOpenResult

    fun close(sessionId: UsbTransportSessionId)
}
