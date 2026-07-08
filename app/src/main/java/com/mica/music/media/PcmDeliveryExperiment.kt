package com.mica.music.media

import com.mica.music.util.DiagnosticLog

/**
 * Audio pipeline delivery flags.
 *
 * R1b+R4 (production): renderer-split sinks — DsdOnly int sink + PcmOnly float-dsp sink +
 *      platform unified chain; no per-song Exo rebuild. Enabled on all build types.
 *
 * Audio quality (consent 2026-07-08): PcmOnly float sink avoids 24→16 toInt16 on hi-res PCM;
 * EQ/spectrum/speed are purely additive (off by default → bit-exact passthrough).
 */
internal object PcmDeliveryExperiment {

    /** R1b renderer split — production architecture for hi-res delivery. */
    private const val R1B_RENDERER_SPLIT_ENABLED = true

    /** R1b renderer-split sinks (all build types). */
    val rendererSplit: Boolean
        get() = R1B_RENDERER_SPLIT_ENABLED

    fun logActiveExperiments() {
        DiagnosticLog.event(
            "PcmDeliveryExperiment",
            "active=R1b-renderer-split+R4-float-dsp scope=all-builds; " +
                "DsdOnly=int-sink(EQ+spectrum) PcmOnly=float-dsp-sink(EQ+spectrum+hw-speed) platform=unified-chain; " +
                "no per-song rebuild; DSD speed/pitch off (Sonic no 24-bit int)",
        )
    }
}
