package com.mica.music.playback

import androidx.media3.common.Player
import com.mica.music.media.PlaybackOutputAvailability

enum class PlaybackExecutionState {
    UNAVAILABLE,
    IDLE,
    PAUSED,
    PREPARING,
    BUFFERING,
    PLAYING,
    SUPPRESSED,
    ENDED,
    ERROR,
}

enum class PlaybackIntent { PLAY, PAUSE }

data class PlaybackStatus(
    val execution: PlaybackExecutionState = PlaybackExecutionState.UNAVAILABLE,
    val intent: PlaybackIntent = PlaybackIntent.PAUSE,
    val outputAvailability: PlaybackOutputAvailability = PlaybackOutputAvailability.INACTIVE,
) {
    /** The primary button offers Pause only while there is still cancellable playback intent. */
    val showsPauseAction: Boolean
        get() = intent == PlaybackIntent.PLAY &&
            (
                execution == PlaybackExecutionState.PREPARING ||
                    execution == PlaybackExecutionState.BUFFERING ||
                    execution == PlaybackExecutionState.PLAYING ||
                    execution == PlaybackExecutionState.SUPPRESSED
            )
}

internal fun resolvePlaybackStatus(
    hasCurrentSong: Boolean,
    controllerConnected: Boolean,
    playbackState: Int?,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    pendingOutputPlayIntent: Boolean = playWhenReady,
    playbackSuppressionReason: Int,
    playbackError: String?,
    outputAvailability: PlaybackOutputAvailability,
): PlaybackStatus {
    val outputTransitionActive = when (outputAvailability) {
        PlaybackOutputAvailability.INACTIVE,
        PlaybackOutputAvailability.STABLE,
        -> false
        else -> true
    }
    val effectivePlayIntent = if (outputTransitionActive) pendingOutputPlayIntent else playWhenReady
    val intent = if (effectivePlayIntent) PlaybackIntent.PLAY else PlaybackIntent.PAUSE
    val execution = when {
        playbackError != null || outputAvailability == PlaybackOutputAvailability.FAILED ->
            PlaybackExecutionState.ERROR
        !hasCurrentSong -> PlaybackExecutionState.IDLE
        !controllerConnected -> PlaybackExecutionState.UNAVAILABLE
        playbackState == Player.STATE_ENDED -> PlaybackExecutionState.ENDED
        isPlaying -> PlaybackExecutionState.PLAYING
        playbackState == Player.STATE_BUFFERING -> PlaybackExecutionState.BUFFERING
        playWhenReady && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
            PlaybackExecutionState.SUPPRESSED
        playbackState == Player.STATE_IDLE && playWhenReady -> PlaybackExecutionState.PREPARING
        playbackState == Player.STATE_READY && playWhenReady -> PlaybackExecutionState.PREPARING
        else -> PlaybackExecutionState.PAUSED
    }
    return PlaybackStatus(
        execution = execution,
        intent = intent,
        outputAvailability = outputAvailability,
    )
}
