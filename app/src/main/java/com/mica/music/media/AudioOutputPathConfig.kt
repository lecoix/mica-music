package com.mica.music.media

import com.mica.music.util.DiagnosticLog
import com.mica.music.data.preferences.UsbHybridOutputMode

/**
 * Output-path configuration fixed at ExoPlayer build time (§7.1 / §7.4 full-mode rebuild).
 *
 * Bundles [PlaybackOutputMode] (SharedPcm vs USB paths) with [DsdDecimationOutputMode]
 * (DSD int vs float delivery). Renderer-split sink selection in [MicaRenderersFactory] reads
 * this object; changing it requires rebuilding Exo.
 */
data class AudioOutputPathConfig(
    val outputMode: PlaybackOutputMode = PlaybackOutputMode.SharedPcm,
    val dsdDecimationMode: DsdDecimationOutputMode = DsdDecimationOutputMode.IntPcm,
    /** Reserved for P6 USB device binding; unused while [outputMode] is [PlaybackOutputMode.SharedPcm]. */
    val usbAudioDeviceId: Int? = null,
) {
    fun logForDiagnostics() {
        DiagnosticLog.event(
            "AudioOutputPath",
            "mode=$outputMode dsdDecimation=$dsdDecimationMode usbDeviceId=$usbAudioDeviceId",
        )
    }

    /**
     * Fail fast if a reserved mode is selected before its P4/P6 implementation lands.
     * Call at Exo stack build so misconfiguration surfaces at startup, not mid-playback.
     */
    fun requireSupportedForPlayback() {
        require(dsdDecimationMode == DsdDecimationOutputMode.IntPcm) {
            "DSD FloatPcm delivery is reserved (P4); only IntPcm is active today."
        }
    }

    companion object {
        /** Current production defaults: built-in SharedPcm + DSD 24-bit int decimation. */
        val PRODUCTION = AudioOutputPathConfig()
    }
}

internal fun UsbHybridOutputMode.toAudioOutputPathConfig(): AudioOutputPathConfig = when (this) {
    UsbHybridOutputMode.SharedPcm -> AudioOutputPathConfig.PRODUCTION
    UsbHybridOutputMode.ExactPcm -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDirectPcm)
    UsbHybridOutputMode.Dop -> AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbDop)
    UsbHybridOutputMode.NativeDsdExperimental ->
        AudioOutputPathConfig(outputMode = PlaybackOutputMode.UsbNativeDsdExperimental)
}
