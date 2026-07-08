package com.mica.music.media

/**
 * DSD decimation delivery encoding after downsample (§2.4 / P4).
 *
 * - [IntPcm]: current production — quantize to 24-bit (or 16-bit) int for AudioTrack.
 * - [FloatPcm]: future — keep float PCM so Sonic/EQ can run in the float domain on DSD
 *   (requires consent + device validation; not enabled in [AudioOutputPathConfig.PRODUCTION]).
 */
enum class DsdDecimationOutputMode {
    IntPcm,
    FloatPcm,
}
