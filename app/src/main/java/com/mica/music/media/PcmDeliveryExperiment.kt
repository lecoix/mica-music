package com.mica.music.media

import com.mica.music.BuildConfig
import com.mica.music.util.DiagnosticLog

/**
 * Audio pipeline delivery flags.
 *
 * G3-1a (superseded): global [enableFloatOutput=true] — proved insufficient on device (log 26).
 * G3-1b (deprecated): per-song sink rebuild — superseded by R1b renderer-split; inert.
 * R1b+R4 (production): renderer-split sinks — DsdOnly int sink + PcmOnly float-dsp sink +
 *      platform unified chain; no per-song Exo rebuild. Enabled on all build types.
 *
 * Audio quality (consent 2026-07-08): PcmOnly float sink avoids 24→16 toInt16 on hi-res PCM;
 * EQ/spectrum/speed are purely additive (off by default → bit-exact passthrough).
 */
internal object PcmDeliveryExperiment {

    /** Deprecated G3-1a global float sink; kept false. */
    private const val G31_NO_DSP_FLOAT_SINK_ENABLED = false

    /** Deprecated G3-1b per-song rebuild; disabled — R1b renderer split is the terminal choice. */
    private const val G31B_PER_SONG_SINK_ENABLED = false

    /** R1b renderer split — production architecture for hi-res delivery. */
    private const val R1B_RENDERER_SPLIT_ENABLED = true

    /** Debug/perf-only deprecated experiment flags (G3-1a/1b). */
    private val deprecatedExperimentsEnabled: Boolean
        get() = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"

    /** G3-1a global flag — always false. */
    val g31NoDspFloatSink: Boolean = false

    /** R1b renderer-split sinks (all build types). */
    val rendererSplit: Boolean
        get() = R1B_RENDERER_SPLIT_ENABLED

    /** Deprecated G3-1b per-song rebuild — always false (superseded by [rendererSplit]). */
    val g31bPerSongSink: Boolean
        get() = deprecatedExperimentsEnabled && G31B_PER_SONG_SINK_ENABLED && !rendererSplit

    fun logActiveExperiments() {
        when {
            rendererSplit -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=R1b-renderer-split+R4-float-dsp scope=all-builds; " +
                    "DsdOnly=int-sink(EQ+spectrum) PcmOnly=float-dsp-sink(EQ+spectrum+hw-speed) platform=unified-chain; " +
                    "no per-song rebuild; DSD speed/pitch off (Sonic no 24-bit int)",
            )
            deprecatedExperimentsEnabled && g31bPerSongSink -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=G3-1b-per-song-sink scope=debug-or-perf; " +
                    "rebuild-on-sink-change via setPlayer; re-test DSD+FLAC+EQ",
            )
            deprecatedExperimentsEnabled && g31NoDspFloatSink -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=G3-1a-no-dsp-float-sink enableFloatOutput=true " +
                    "scope=debug-only; re-test DSD+EQ before shipping",
            )
            deprecatedExperimentsEnabled -> DiagnosticLog.event("PcmDeliveryExperiment", "active=none")
        }
    }
}
