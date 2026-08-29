package com.mica.music.playback

import androidx.media3.common.Player
import com.mica.music.media.PlaybackOutputAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatusTest {
    @Test
    fun endedRemainsEndedEvenWhenPlayIntentIsStillSet() {
        val status = resolvePlaybackStatus(
            hasCurrentSong = true,
            controllerConnected = true,
            playbackState = Player.STATE_ENDED,
            isPlaying = false,
            playWhenReady = true,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            playbackError = null,
            outputAvailability = PlaybackOutputAvailability.STABLE,
        )

        assertEquals(PlaybackExecutionState.ENDED, status.execution)
        assertEquals(PlaybackIntent.PLAY, status.intent)
        assertFalse(status.showsPauseAction)
    }

    @Test
    fun bufferingAndSuppressionKeepPlayIntentSeparateFromExecution() {
        val buffering = resolvePlaybackStatus(
            hasCurrentSong = true,
            controllerConnected = true,
            playbackState = Player.STATE_BUFFERING,
            isPlaying = false,
            playWhenReady = true,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            playbackError = null,
            outputAvailability = PlaybackOutputAvailability.STABLE,
        )
        val suppressed = resolvePlaybackStatus(
            hasCurrentSong = true,
            controllerConnected = true,
            playbackState = Player.STATE_READY,
            isPlaying = false,
            playWhenReady = true,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            playbackError = null,
            outputAvailability = PlaybackOutputAvailability.STABLE,
        )

        assertEquals(PlaybackExecutionState.BUFFERING, buffering.execution)
        assertEquals(PlaybackExecutionState.SUPPRESSED, suppressed.execution)
        assertTrue(buffering.showsPauseAction)
        assertTrue(suppressed.showsPauseAction)
    }

    @Test
    fun outputTransitionIsIndependentFromMediaExecution() {
        val status = resolvePlaybackStatus(
            hasCurrentSong = true,
            controllerConnected = false,
            playbackState = null,
            isPlaying = false,
            playWhenReady = false,
            pendingOutputPlayIntent = true,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            playbackError = null,
            outputAvailability = PlaybackOutputAvailability.WAITING_FOR_PERMISSION,
        )

        assertEquals(PlaybackExecutionState.UNAVAILABLE, status.execution)
        assertEquals(PlaybackIntent.PLAY, status.intent)
        assertEquals(PlaybackOutputAvailability.WAITING_FOR_PERMISSION, status.outputAvailability)
    }

    @Test
    fun playbackErrorWinsOverRetainedPlayIntent() {
        val status = resolvePlaybackStatus(
            hasCurrentSong = true,
            controllerConnected = true,
            playbackState = Player.STATE_IDLE,
            isPlaying = false,
            playWhenReady = true,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            playbackError = "decode failed",
            outputAvailability = PlaybackOutputAvailability.STABLE,
        )

        assertEquals(PlaybackExecutionState.ERROR, status.execution)
        assertFalse(status.showsPauseAction)
    }
}
