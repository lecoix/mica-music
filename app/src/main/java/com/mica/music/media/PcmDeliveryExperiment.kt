package com.mica.music.media

import com.mica.music.BuildConfig
import com.mica.music.util.DiagnosticLog

/**
 * Gate 3-1 delivery experiments (debug + perf). Release builds use production sink settings.
 *
 * G3-1a (superseded): global [enableFloatOutput=true] — proved insufficient on device (log 26).
 * G3-1b (deprecated): per-song sink rebuild via [PcmSinkDeliveryDecider] + Exo
 *      [MediaSession.setPlayer]. Superseded by R1b (renderer split, the terminal choice) which
 *      avoids the AUTO-switch rebuild race; kept only as inert code behind a false flag.
 * R1b: renderer-split sinks — one ExoPlayer with DsdOnly/PcmOnly FFmpeg renderers, each bound to
 *      its own sink (no per-song Exo rebuild). The chosen architecture (debug/perf).
 *
 * Audio quality consent (R1b, debug/perf only): the PcmOnly sink uses [enableFloatOutput=true]
 * for hi-res PCM (avoids 24->16 toInt16); FLAC/DSD lose EQ in this first spike (deferred to
 * P3/P4). Release builds are unaffected (renderer split disabled -> unified fixed chain X).
 */
internal object PcmDeliveryExperiment {

    /** Deprecated G3-1a global float sink; kept false. */
    private const val G31_NO_DSP_FLOAT_SINK_ENABLED = false

    /** Deprecated G3-1b per-song rebuild; disabled — R1b renderer split is the terminal choice. */
    private const val G31B_PER_SONG_SINK_ENABLED = false

    /** R1b renderer split — the chosen architecture for hi-res delivery (debug/perf). */
    private const val R1B_RENDERER_SPLIT_ENABLED = true

    private val deliveryExperimentsEnabled: Boolean
        get() = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"

    /** G3-1a global flag — always false; use [g31bPerSongSink]. */
    val g31NoDspFloatSink: Boolean = false

    /** R1b renderer-split sinks (debug/perf). Takes precedence over G3-1b when enabled. */
    val rendererSplit: Boolean
        get() = deliveryExperimentsEnabled && R1B_RENDERER_SPLIT_ENABLED

    /** Deprecated G3-1b per-song rebuild — always false (superseded by [rendererSplit]). */
    val g31bPerSongSink: Boolean
        get() = deliveryExperimentsEnabled && G31B_PER_SONG_SINK_ENABLED && !rendererSplit

    fun logActiveExperiments() {
        if (!deliveryExperimentsEnabled) return
        when {
            rendererSplit -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=R1b-renderer-split+R4-float-dsp scope=debug-or-perf; " +
                    "DsdOnly=int-sink(EQ+spectrum) PcmOnly=float-dsp-sink(EQ+spectrum+hw-speed) platform=unified-chain; " +
                    "no per-song rebuild; DSD speed/pitch off (Sonic no 24-bit int)",
            )
            g31bPerSongSink -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=G3-1b-per-song-sink scope=debug-or-perf; " +
                    "rebuild-on-sink-change via setPlayer; re-test DSD+FLAC+EQ",
            )
            g31NoDspFloatSink -> DiagnosticLog.event(
                "PcmDeliveryExperiment",
                "active=G3-1a-no-dsp-float-sink enableFloatOutput=true " +
                    "scope=debug-only; re-test DSD+EQ before shipping",
            )
            else -> DiagnosticLog.event("PcmDeliveryExperiment", "active=none")
        }
    }
}
