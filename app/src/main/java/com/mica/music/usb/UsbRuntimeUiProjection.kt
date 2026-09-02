package com.mica.music.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsbPlaybackMode {
    SHARED_PCM,
    USB_EXACT_PCM,
    USB_DOP,
    USB_NATIVE_DSD_EXPERIMENTAL,
}

enum class UsbPermissionStatus { NOT_REQUIRED, REQUESTED, GRANTED, DENIED }

data class UsbTelemetrySnapshot(
    val pendingIsoPackets: Long,
    val totalIsoPackets: Long,
    val pendingOutputUrbs: Long,
    val isoErrorCount: Long,
)

data class UsbFailureSnapshot(val code: String, val message: String)

data class UsbPlaybackSnapshot(
    val requestEpoch: Long = 0L,
    val discoveryRevision: Long = 0L,
    val requestedMode: UsbPlaybackMode = UsbPlaybackMode.SHARED_PCM,
    val activeMode: UsbPlaybackMode? = null,
    val activeTransport: String? = null,
    val identity: UsbStableIdentity? = null,
    val runtimeDeviceId: Int? = null,
    val runtimeDeviceName: String? = null,
    val sessionId: Long? = null,
    val permission: UsbPermissionStatus = UsbPermissionStatus.NOT_REQUIRED,
    val claimed: Boolean = false,
    val exclusive: Boolean = false,
    val transportExact: Boolean = false,
    val signalExact: Boolean = false,
    val sourceEncoding: Int? = null,
    val usbBitResolution: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val streamFormat: String? = null,
    val telemetry: UsbTelemetrySnapshot? = null,
    val failure: UsbFailureSnapshot? = null,
)

/** Read-only USB runtime projection for settings/presentation. Transport ownership remains in media. */
object UsbRuntimeUiProjection {
    private val mutableFacts = MutableStateFlow(UsbPlaybackSnapshot())
    val facts: StateFlow<UsbPlaybackSnapshot> = mutableFacts.asStateFlow()

    internal fun publish(facts: UsbPlaybackSnapshot) {
        mutableFacts.value = facts
    }
}

interface UsbHybridDiagnosticsPort {
    fun buildReport(): String
}