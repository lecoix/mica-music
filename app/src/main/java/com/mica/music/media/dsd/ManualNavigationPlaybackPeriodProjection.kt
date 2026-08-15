package com.mica.music.media.dsd

import androidx.media3.exoplayer.source.MediaSource

/** Per-renderer projection of the playback thread's authoritative MediaPeriodId. */
class ManualNavigationPlaybackPeriodProjection(
    private val bridge: ManualNavigationTransitionBridge,
) {
    private var current: ManualNavigationPlaybackIdentity? = null

    @Synchronized
    fun onStreamChanged(mediaPeriodId: MediaSource.MediaPeriodId): ManualNavigationPlaybackIdentity {
        val identity = bridge.observePlaybackStream(mediaPeriodId)
        current = identity
        return identity
    }

    @Synchronized
    fun snapshot(): ManualNavigationPlaybackIdentity? = current

    @Synchronized
    fun clear() {
        current = null
    }
}
