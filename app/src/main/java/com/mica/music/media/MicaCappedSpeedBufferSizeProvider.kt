package com.mica.music.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider

/**
 * Clamps the max-playback-speed factor used when sizing the AudioTrack buffer.
 *
 * When [DefaultAudioSink.Builder.setEnableAudioTrackPlaybackParams] is on, Media3 sizes the buffer
 * for `MAX_PLAYBACK_SPEED = 8x` to stay safe at any speed, inflating the float PcmSink buffer ~8x
 * (~3.8s). Mica never plays faster than [com.mica.music.data.PlaybackTuning.MAX_SPEED] (2x), so we
 * cap the factor to that. Result: buffer sized for 2x (~0.96s) — same drain-safety as normal 1x
 * playback — which keeps the spectrum tap cadence tight and EQ latency low. Buffer size never
 * alters samples, so this has no audio-quality impact.
 */
@UnstableApi
internal class MicaCappedSpeedBufferSizeProvider(
    private val maxSpeed: Double,
    private val delegate: AudioTrackBufferSizeProvider = AudioTrackBufferSizeProvider.DEFAULT,
) : AudioTrackBufferSizeProvider {

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int = delegate.getBufferSizeInBytes(
        minBufferSizeInBytes,
        encoding,
        outputMode,
        pcmFrameSize,
        sampleRate,
        bitrate,
        maxAudioTrackPlaybackSpeed.coerceAtMost(maxSpeed),
    )
}
