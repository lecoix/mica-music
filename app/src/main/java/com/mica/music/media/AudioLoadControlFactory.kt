@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.mica.music.media

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import com.mica.music.util.DiagnosticLog

internal data class AudioLoadControlPolicy(
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 30_000,
    val bufferForPlaybackMs: Int = 800,
    val bufferForPlaybackAfterRebufferMs: Int = 800,
    val backBufferMs: Int = 60_000,
)

/**
 * Keeps audio source loading governed by buffered duration rather than allocator bytes.
 *
 * High-bitrate lossless tracks can hit Media3's byte target while holding too little source
 * duration. If consumed samples are then released, loading can restart only at the edge and
 * starve the PCM producer even though the USB queue itself is healthy.
 */
internal fun buildAudioLoadControl(
    policy: AudioLoadControlPolicy = AudioLoadControlPolicy(),
): LoadControl = runCatching {
    require(policy.bufferForPlaybackMs >= 0)
    require(policy.bufferForPlaybackAfterRebufferMs >= 0)
    require(policy.minBufferMs >= policy.bufferForPlaybackMs)
    require(policy.minBufferMs >= policy.bufferForPlaybackAfterRebufferMs)
    require(policy.maxBufferMs >= policy.minBufferMs)

    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            policy.minBufferMs,
            policy.maxBufferMs,
            policy.bufferForPlaybackMs,
            policy.bufferForPlaybackAfterRebufferMs,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(policy.backBufferMs.coerceAtLeast(0), false)
        .build()
}.getOrElse { error ->
    DiagnosticLog.event(
        "AudioLoadControl",
        "invalid policy; using Media3 defaults",
        error,
    )
    DefaultLoadControl.Builder().build()
}
