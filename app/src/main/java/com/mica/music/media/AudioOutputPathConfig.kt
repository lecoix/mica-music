package com.mica.music.media

import com.mica.music.util.DiagnosticLog
import com.mica.music.media.usb.UsbOutputRequest

/**
 * Output-path configuration fixed at ExoPlayer build time (§7.1 / §7.4 full-mode rebuild).
 *
 * Bundles [PlaybackOutputMode] (SharedPcm vs USB paths) with [DsdDecimationOutputMode]
 * (DSD int vs float delivery). Renderer-split sink selection in [MicaRenderersFactory] reads
 * this object; changing it requires rebuilding Exo.
 */
internal data class AudioOutputPathConfig(
    val outputMode: PlaybackOutputMode = PlaybackOutputMode.SharedPcm,
    val dsdDecimationMode: DsdDecimationOutputMode = DsdDecimationOutputMode.IntPcm,
    /** Typed Host request; never uses framework AudioDeviceInfo.id as USB identity. */
    val usbOutputRequest: UsbOutputRequest? = null,
) {
    fun logForDiagnostics() {
        DiagnosticLog.event(
            "AudioOutputPath",
            "mode=$outputMode dsdDecimation=$dsdDecimationMode " +
                "usbDevice=${usbOutputRequest?.device}",
        )
    }

    /**
     * Fail fast if a reserved mode is selected before its P4/P6 implementation lands.
     * Call at Exo stack build so misconfiguration surfaces at startup, not mid-playback.
     */
    fun requireSupportedForPlayback() {
        require(
            outputMode == PlaybackOutputMode.SharedPcm ||
                (outputMode == PlaybackOutputMode.UsbDirectPcm && usbOutputRequest != null),
        ) {
            "Output mode $outputMode is reserved (P6 USB); only SharedPcm is active today."
        }
        require(outputMode != PlaybackOutputMode.SharedPcm || usbOutputRequest == null) {
            "SharedPcm cannot carry a USB Host request"
        }
        require(dsdDecimationMode == DsdDecimationOutputMode.IntPcm) {
            "DSD FloatPcm delivery is reserved (P4); only IntPcm is active today."
        }
    }

    companion object {
        /** Current production defaults: built-in SharedPcm + DSD 24-bit int decimation. */
        val PRODUCTION = AudioOutputPathConfig()
    }
}
