package com.kyant.taglib

/**
 * Metadata and audio properties read from a single [TagLib.probeTrack] native session.
 */
public data class TrackProbe(
    val metadata: Metadata,
    val audioProperties: AudioProperties,
)
