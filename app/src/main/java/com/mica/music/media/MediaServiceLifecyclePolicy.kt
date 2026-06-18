package com.mica.music.media

import androidx.media3.common.Player

internal object MediaServiceLifecyclePolicy {
    fun shouldStopAfterTaskRemoved(
        playWhenReady: Boolean,
        mediaItemCount: Int,
        playbackState: Int,
    ): Boolean =
        !playWhenReady ||
            mediaItemCount <= 0 ||
            playbackState == Player.STATE_ENDED
}
