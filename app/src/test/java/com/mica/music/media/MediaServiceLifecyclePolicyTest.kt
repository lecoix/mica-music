package com.mica.music.media

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServiceLifecyclePolicyTest {

    @Test
    fun activePlaybackSurvivesTaskRemoval() {
        assertFalse(
            MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = true,
                alacPlayWhenReady = false,
                mediaItemCount = 3,
                playbackState = Player.STATE_READY,
            ),
        )
        assertFalse(
            MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(
                playWhenReady = false,
                alacPlayWhenReady = true,
                mediaItemCount = 3,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun pausedEmptyOrEndedPlaybackStopsService() {
        assertTrue(shouldStop(playWhenReady = false, itemCount = 2, state = Player.STATE_READY))
        assertTrue(shouldStop(playWhenReady = true, itemCount = 0, state = Player.STATE_READY))
        assertTrue(shouldStop(playWhenReady = true, itemCount = 2, state = Player.STATE_ENDED))
    }

    private fun shouldStop(
        playWhenReady: Boolean,
        itemCount: Int,
        state: Int,
    ): Boolean = MediaServiceLifecyclePolicy.shouldStopAfterTaskRemoved(
        playWhenReady = playWhenReady,
        alacPlayWhenReady = false,
        mediaItemCount = itemCount,
        playbackState = state,
    )
}
