package com.mica.music.media

/**
 * Top-level audio output routing (P6 / §4.1).
 *
 * Orthogonal to renderer-split (R1b): output mode selects which sink/processor ladder to build;
 * renderer role (DsdOnly / PcmOnly / platform) still picks the active renderer inside Exo.
 *
 * Only [SharedPcm] is active today. USB modes are reserved for future full-mode rebuild on
 * device attach/detach (see [AudioOutputPathConfig.requireSupportedForPlayback]).
 */
enum class PlaybackOutputMode {
    /** Built-in / default: SharedPcm ladder with spectrum, EQ, Sonic, DSD decimation. */
    SharedPcm,

    /** USB DAC PCM direct (P6): minimal processor chain, no EQ/Sonic/spectrum by default. */
    UsbDirectPcm,

    /** Explicit DoP for DSF; regular PCM still uses USB Exact PCM. */
    UsbDop,

    /** Explicit experimental Native DSD for DSF; regular PCM still uses USB Exact PCM. */
    UsbNativeDsdExperimental,
}

/** Whether this mode routes through ExoPlayer's PCM processor + AudioTrack path. */
val PlaybackOutputMode.usesExoPcmChain: Boolean
    get() = this == PlaybackOutputMode.SharedPcm || this == PlaybackOutputMode.UsbDirectPcm

/** Whether SharedPcm DSP features (EQ / spectrum / Sonic) are allowed on this path. */
val PlaybackOutputMode.allowsSharedPcmDsp: Boolean
    get() = this == PlaybackOutputMode.SharedPcm

/** Whether the sink should use a minimal processor chain (bit-preserve / USB direct). */
val PlaybackOutputMode.requiresMinimalProcessorChain: Boolean
    get() = this != PlaybackOutputMode.SharedPcm
