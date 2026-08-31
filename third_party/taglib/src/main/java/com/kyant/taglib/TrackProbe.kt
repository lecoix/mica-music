package com.kyant.taglib

/**
 * Metadata and audio properties read from a single [TagLib.probeTrack] native session.
 */
public data class TrackProbe(
    val metadata: Metadata,
    val audioProperties: AudioProperties,
    /** True when the tag advertises at least one PICTURE complex property; picture bytes are not read. */
    val hasPictures: Boolean,
)
