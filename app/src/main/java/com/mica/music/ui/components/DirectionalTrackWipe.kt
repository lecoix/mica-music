package com.mica.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.motion.MicaMotion

internal data class TrackWipeHorizontalBounds(
    val left: Float,
    val right: Float,
)

internal fun trackWipeHorizontalBounds(
    width: Float,
    progress: Float,
    direction: TrackSkipDirection,
    incoming: Boolean,
): TrackWipeHorizontalBounds {
    val safeWidth = width.coerceAtLeast(0f)
    val safeProgress = progress.coerceIn(0f, 1f)
    val seamX = when (direction) {
        TrackSkipDirection.TO_NEXT -> safeWidth * (1f - safeProgress)
        TrackSkipDirection.TO_PREVIOUS -> safeWidth * safeProgress
    }
    return when (direction) {
        TrackSkipDirection.TO_NEXT -> if (incoming) {
            TrackWipeHorizontalBounds(seamX, safeWidth)
        } else {
            TrackWipeHorizontalBounds(0f, seamX)
        }
        TrackSkipDirection.TO_PREVIOUS -> if (incoming) {
            TrackWipeHorizontalBounds(0f, seamX)
        } else {
            TrackWipeHorizontalBounds(seamX, safeWidth)
        }
    }
}

/**
 * Directional track change for one bounded visual region.
 *
 * Incoming and outgoing content are always measured at their complete size. Animation only moves
 * a draw-time clip boundary, so artwork and text never recenter, resize, or reflow during the wipe.
 * Playback controls and the rest of the page stay single-instance.
 */
@Composable
internal fun <T : Any> DirectionalTrackWipe(
    targetState: T,
    contentKey: (T) -> Any?,
    direction: TrackSkipDirection?,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    var visibleState by remember { mutableStateOf(targetState) }
    var outgoingState by remember { mutableStateOf<T?>(null) }
    var activeDirection by remember { mutableStateOf<TrackSkipDirection?>(null) }
    val progress = remember { Animatable(1f) }
    val targetKey = contentKey(targetState)

    SideEffect {
        if (contentKey(visibleState) == targetKey && visibleState != targetState) {
            visibleState = targetState
        }
    }

    LaunchedEffect(targetKey) {
        if (contentKey(visibleState) == targetKey) {
            visibleState = targetState
            return@LaunchedEffect
        }

        outgoingState = visibleState
        visibleState = targetState
        activeDirection = direction
        progress.snapTo(0f)
        if (motionEnabled) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MicaMotion.DurationMediumMs,
                    easing = MicaMotion.Easing,
                ),
            )
        } else {
            progress.snapTo(1f)
        }
        outgoingState = null
        activeDirection = null
    }

    val outgoing = outgoingState
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (outgoing == null) {
            content(visibleState)
        } else {
            Box(
                Modifier.trackWipeLayer(
                    progress = { progress.value },
                    direction = activeDirection,
                    incoming = true,
                ),
            ) {
                content(visibleState)
            }
            Box(
                Modifier.trackWipeLayer(
                    progress = { progress.value },
                    direction = activeDirection,
                    incoming = false,
                ),
            ) {
                content(outgoing)
            }
        }
    }
}

internal fun Modifier.trackWipeLayer(
    progress: () -> Float,
    direction: TrackSkipDirection?,
    incoming: Boolean,
): Modifier = if (direction == null) {
    graphicsLayer {
        val incomingAlpha = progress().coerceIn(0f, 1f)
        alpha = if (incoming) incomingAlpha else 1f - incomingAlpha
    }
} else {
    drawWithContent {
        val bounds = trackWipeHorizontalBounds(
            width = size.width,
            progress = progress(),
            direction = direction,
            incoming = incoming,
        )
        clipRect(left = bounds.left, top = 0f, right = bounds.right, bottom = size.height) {
            this@drawWithContent.drawContent()
        }
    }
}
