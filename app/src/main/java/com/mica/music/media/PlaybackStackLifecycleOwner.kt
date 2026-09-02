package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Owns the single active Exo playback stack and its publication boundary.
 *
 * Stack-scoped service owners are attached before the player is published to MediaSession.
 * Retirement clears the active authority before the underlying ExoPlayer is released so a
 * released stack cannot be reused by later service callbacks.
 */
internal data class PlaybackStackHandoff(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val playbackParameters: PlaybackParameters,
    val volume: Float,
)

@UnstableApi
internal class PlaybackStackLifecycleOwner(
    private val publishPlayer: (MicaCompositePlayer) -> Unit,
) {
    var activeStack: ExoPlaybackStack? = null
        private set

    val exoPlayer get() = activeStack?.exoPlayer
    val player get() = activeStack?.compositePlayer
    val hasActiveStack get() = activeStack != null

    fun isActive(stack: ExoPlaybackStack): Boolean = activeStack === stack

    fun captureHandoff(): PlaybackStackHandoff? {
        val stack = activeStack ?: return null
        val player = stack.compositePlayer
        val queue = player.playbackQueueSnapshot()
        if (queue.items.isEmpty()) return null
        return PlaybackStackHandoff(
            items = queue.items,
            currentIndex = queue.currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            repeatMode = player.repeatMode,
            playbackParameters = player.playbackParameters,
            volume = player.volume,
        )
    }
    fun install(
        stack: ExoPlaybackStack,
        attachOwners: (ExoPlaybackStack) -> Unit,
        detachOwners: (ExoPlaybackStack) -> Unit,
    ) {
        check(activeStack == null) { "Playback stack already active" }
        activeStack = stack
        try {
            attachOwners(stack)
            publishPlayer(stack.compositePlayer)
        } catch (failure: Throwable) {
            activeStack = null
            runCatching { detachOwners(stack) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            runCatching { stack.exoPlayer.release() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun retire(
        detachOwners: (ExoPlaybackStack) -> Unit,
        verifyPlayerRelease: Boolean = false,
        beforePlayerRelease: () -> Unit = {},
    ) {
        val stack = activeStack
        if (stack == null) {
            beforePlayerRelease()
            return
        }
        var observedReleaseError: PlaybackException? = null
        if (verifyPlayerRelease) {
            stack.exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    observedReleaseError = error
                }
            })
        }

        activeStack = null
        var detachFailure: Throwable? = null
        try {
            detachOwners(stack)
        } catch (failure: Throwable) {
            detachFailure = failure
        }

        var preReleaseFailure: Throwable? = null
        try {
            beforePlayerRelease()
        } catch (failure: Throwable) {
            preReleaseFailure = failure
        }

        var releaseFailure: Throwable? = null
        try {
            stack.exoPlayer.release()
        } catch (failure: Throwable) {
            releaseFailure = failure
        }

        detachFailure?.let { failure ->
            preReleaseFailure?.let(failure::addSuppressed)
            releaseFailure?.let(failure::addSuppressed)
            throw failure
        }
        preReleaseFailure?.let { failure ->
            releaseFailure?.let(failure::addSuppressed)
            throw failure
        }
        releaseFailure?.let { throw it }
        observedReleaseError?.let { error ->
            throw IllegalStateException(
                "Old ExoPlayer release failed: ${error.cause?.message ?: error.message}",
                error,
            )
        }
    }
}