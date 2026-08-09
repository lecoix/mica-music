package com.mica.music.media.usb

internal enum class UsbOutputPhase {
    IDLE,
    REQUESTED,
    OPENING,
    ACTIVE,
    RELEASING,
    FAILED,
}

internal data class UsbOutputFailure(
    val stage: String,
    val message: String,
    val fallbackToSharedPcm: Boolean = false,
)
/** Runtime facts; consumers must not infer these from preferences. */
internal data class PlaybackOutputFacts(
    val generation: Long = 0L,
    val phase: UsbOutputPhase = UsbOutputPhase.IDLE,
    val request: UsbOutputRequest? = null,
    val runtimeHandle: UsbAudioRuntimeHandle? = null,
    val negotiatedFormat: UsbPcmFormat? = null,
    val permissionGranted: Boolean = false,
    val claimed: Boolean = false,
    val exclusive: Boolean = false,
    val signalExact: Boolean = false,
    val activeDsp: Set<String> = emptySet(),
    val failure: UsbOutputFailure? = null,
)
