package com.mica.music.media.usbhybrid

data class UsbDeviceCandidate(
    val identity: UsbStableIdentity,
    val runtimeHandle: UsbRuntimeHandle,
    val manufacturerName: String?,
    val productName: String?,
    val hasAudioOutput: Boolean,
)

sealed interface UsbAudioSelection {
    data object NotFound : UsbAudioSelection
    data class Ambiguous(val candidateCount: Int) : UsbAudioSelection
    data class Selected(val candidate: UsbDeviceCandidate) : UsbAudioSelection
}

/** Selects a single USB Audio streaming output device; never guesses when multiple DACs are attached. */
object UsbAudioTargetSelector {
    fun select(candidates: List<UsbDeviceCandidate>): UsbAudioSelection {
        val matching = candidates.filter(UsbDeviceCandidate::hasAudioOutput)
        return when (matching.size) {
            0 -> UsbAudioSelection.NotFound
            1 -> UsbAudioSelection.Selected(matching.single())
            else -> UsbAudioSelection.Ambiguous(matching.size)
        }
    }
}
