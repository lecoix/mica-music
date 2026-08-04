package com.mica.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import com.mica.music.ui.motion.MicaMotion
import kotlin.math.abs

internal const val PhotoStackLyricsSwipeCommitFraction = 0.25f

private const val PhotoStackLyricsEndpointEpsilon = 0.001f

internal fun photoStackLyricsProgressAfterDrag(
    currentProgress: Float,
    deltaX: Float,
    widthPx: Float,
): Float {
    if (widthPx <= 0f) return currentProgress.coerceIn(0f, 1f)
    return (currentProgress - deltaX / widthPx).coerceIn(0f, 1f)
}

internal fun photoStackLyricsTargetProgress(
    progress: Float,
    startedOpen: Boolean,
): Float = if (startedOpen) {
    if (progress <= 1f - PhotoStackLyricsSwipeCommitFraction) 0f else 1f
} else {
    if (progress >= PhotoStackLyricsSwipeCommitFraction) 1f else 0f
}

internal data class PhotoStackLyricsTransitionFrame(
    val progress: Float,
    val playbackMounted: Boolean,
    val lyricsMounted: Boolean,
    val playbackTranslationFraction: Float,
    val lyricsTranslationFraction: Float,
    val playbackAlpha: Float,
    val lyricsAlpha: Float,
    val playbackInputEnabled: Boolean,
    val lyricsInputEnabled: Boolean,
)

internal fun photoStackLyricsTransitionFrame(
    progress: Float,
    targetOpen: Boolean,
    dragging: Boolean,
): PhotoStackLyricsTransitionFrame {
    val p = progress.coerceIn(0f, 1f)
    val playbackSettled = !targetOpen && p <= PhotoStackLyricsEndpointEpsilon
    val lyricsSettled = targetOpen && p >= 1f - PhotoStackLyricsEndpointEpsilon
    return PhotoStackLyricsTransitionFrame(
        progress = p,
        playbackMounted = !targetOpen || p < 1f - PhotoStackLyricsEndpointEpsilon,
        lyricsMounted = targetOpen || p > PhotoStackLyricsEndpointEpsilon,
        playbackTranslationFraction = -p,
        lyricsTranslationFraction = 1f - p,
        playbackAlpha = 1f - p,
        lyricsAlpha = p,
        playbackInputEnabled = !dragging && playbackSettled,
        lyricsInputEnabled = !dragging && lyricsSettled,
    )
}

@Stable
internal class PhotoStackLyricsTransitionState {
    internal var targetProgress by mutableFloatStateOf(0f)
    internal var animatedProgress by mutableFloatStateOf(0f)
    internal var dragging by mutableStateOf(false)

    val progress: Float
        get() = if (dragging) targetProgress else animatedProgress

    fun beginDrag() {
        targetProgress = animatedProgress
        dragging = true
    }

    fun dragBy(deltaX: Float, widthPx: Float) {
        targetProgress = photoStackLyricsProgressAfterDrag(
            currentProgress = targetProgress,
            deltaX = deltaX,
            widthPx = widthPx,
        )
    }

    fun settleTo(targetProgress: Float) {
        this.targetProgress = targetProgress.coerceIn(0f, 1f)
        dragging = false
    }

    fun settleFromDrag(targetProgress: Float) {
        settleTo(targetProgress)
    }
}

@Composable
internal fun rememberPhotoStackLyricsTransition(
    enabled: Boolean,
    open: Boolean,
    motionEnabled: Boolean,
    ): PhotoStackLyricsTransitionState {
    val transition = remember { PhotoStackLyricsTransitionState() }
    val animatedProgress = remember { Animatable(0f) }
    val animatedProgressValue = animatedProgress.value
    LaunchedEffect(transition.targetProgress, transition.dragging, motionEnabled) {
        if (transition.dragging) {
            animatedProgress.snapTo(transition.targetProgress)
        } else {
            animatedProgress.animateTo(
                targetValue = transition.targetProgress,
                animationSpec = MicaMotion.tweenFloat(
                    enabled = motionEnabled,
                    durationMs = MicaMotion.DurationLongMs,
                ),
            )
        }
    }
    SideEffect {
        transition.animatedProgress = animatedProgressValue
    }
    LaunchedEffect(enabled, open, motionEnabled) {
        transition.settleTo(if (enabled && open) 1f else 0f)
    }
    return transition
}

internal fun Modifier.photoStackLyricsInputEnabled(enabled: Boolean): Modifier {
    if (enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun Modifier.photoStackLyricsSwipe(
    enabled: Boolean,
    transition: PhotoStackLyricsTransitionState,
    onSettled: (Boolean) -> Unit,
): Modifier {
    if (!enabled) return this
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val onSettledState = rememberUpdatedState(onSettled)
    return pointerInput(transition, touchSlop) {
        awaitEachGesture {
            // This listener lives on the playback-page ancestor, so it can observe touches
            // whose visual target is a full-size child container. Child owners still get the
            // final pass first; once they consume a gesture, the page-level swipe cancels.
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Final,
            )
            var dragging = false
            var cancelled = false
            var startedOpen = transition.progress >= 0.5f
            var lastPosition = down.position

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!dragging && change.isConsumed) {
                    cancelled = true
                    break
                }

                val position = change.position
                val fromDown = position - down.position
                val delta = position - lastPosition
                if (!dragging &&
                    (abs(fromDown.x) > touchSlop || abs(fromDown.y) > touchSlop)
                ) {
                    if (abs(fromDown.x) <= abs(fromDown.y)) {
                        cancelled = true
                        break
                    }
                    transition.beginDrag()
                    startedOpen = transition.progress >= 0.5f
                    dragging = true
                }
                if (dragging) {
                    transition.dragBy(deltaX = delta.x, widthPx = size.width.toFloat())
                    change.consume()
                }
                lastPosition = position
            }

            if (dragging && !cancelled) {
                val target = photoStackLyricsTargetProgress(
                    progress = transition.progress,
                    startedOpen = startedOpen,
                )
                transition.settleFromDrag(target)
                onSettledState.value(target == 1f)
            }
        }
    }
}
