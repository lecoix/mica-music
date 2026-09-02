package com.mica.music.media.usbhybrid

import com.mica.music.usb.UsbFailureSnapshot
import com.mica.music.usb.UsbPermissionStatus
import com.mica.music.usb.UsbPlaybackMode
import com.mica.music.usb.UsbPlaybackSnapshot
import com.mica.music.usb.UsbRuntimeUiProjection
import com.mica.music.usb.UsbTelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only process view. Only [UsbHybridSessionOwner]'s publication seam writes this state. */
object UsbHybridRuntimeMonitor {
    private val mutableFacts = MutableStateFlow(UsbPlaybackFacts())
    val facts: StateFlow<UsbPlaybackFacts> = mutableFacts.asStateFlow()

    internal fun publishFromOwner(facts: UsbPlaybackFacts) {
        mutableFacts.value = facts
        UsbRuntimeUiProjection.publish(facts.toUiSnapshot())
    }
}
private fun UsbPlaybackFacts.toUiSnapshot(): UsbPlaybackSnapshot = UsbPlaybackSnapshot(
    requestEpoch = requestEpoch,
    discoveryRevision = discoveryRevision,
    requestedMode = requestedMode.toUiMode(),
    activeMode = activeMode?.toUiMode(),
    activeTransport = activeTransport?.name,
    identity = identity,
    runtimeDeviceId = runtimeHandle?.deviceId,
    runtimeDeviceName = runtimeHandle?.deviceName,
    sessionId = sessionId,
    permission = when (permission) {
        PermissionState.NOT_REQUIRED -> UsbPermissionStatus.NOT_REQUIRED
        PermissionState.REQUESTED -> UsbPermissionStatus.REQUESTED
        PermissionState.GRANTED -> UsbPermissionStatus.GRANTED
        PermissionState.DENIED -> UsbPermissionStatus.DENIED
    },
    claimed = claimed,
    exclusive = exclusive,
    transportExact = transportExact,
    signalExact = signalExact,
    sourceEncoding = sourceEncoding,
    usbBitResolution = usbBitResolution,
    sampleRate = sampleRate,
    channels = channels,
    streamFormat = streamFormat,
    telemetry = telemetry?.let {
        UsbTelemetrySnapshot(
            pendingIsoPackets = it.pendingIsoPackets,
            totalIsoPackets = it.totalIsoPackets,
            pendingOutputUrbs = it.pendingOutputUrbs,
            isoErrorCount = it.isoErrorCount,
        )
    },
    failure = failure?.let { UsbFailureSnapshot(it.code, it.message) },
)

private fun UsbExclusiveMode.toUiMode(): UsbPlaybackMode = when (this) {
    UsbExclusiveMode.SHARED_PCM -> UsbPlaybackMode.SHARED_PCM
    UsbExclusiveMode.USB_EXACT_PCM -> UsbPlaybackMode.USB_EXACT_PCM
    UsbExclusiveMode.USB_DOP -> UsbPlaybackMode.USB_DOP
    UsbExclusiveMode.USB_NATIVE_DSD_EXPERIMENTAL -> UsbPlaybackMode.USB_NATIVE_DSD_EXPERIMENTAL
}