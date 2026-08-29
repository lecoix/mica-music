package com.mica.music.ui.screens.player.view

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mica.music.playback.PlaybackVideoState
import com.mica.music.playback.PlaybackVideoStatus

/** The standard-cover output for the service-owned playback video renderer. */
@Composable
internal fun MusicVideoHost(
    state: PlaybackVideoState,
    attach: (TextureView) -> Long?,
    detach: (TextureView, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val hostEligible = state.effective && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (!hostEligible) return

    val targetAlpha = if (state.status == PlaybackVideoStatus.READY) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 180),
        label = "music-video-first-frame",
    )
    var output by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(output, state.mediaId, state.effective, state.surfaceGeneration) {
        val textureView = output
        val lease = textureView?.let(attach)
        onDispose {
            if (textureView != null && lease != null) detach(textureView, lease)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        val squareSide = minOf(maxWidth, maxHeight)
        Box(modifier = Modifier.size(squareSide)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            // TextureView may receive its first video-size callback before the
                            // AndroidView has been measured; reapply Fit after each layout pass.
                            output?.let { textureView ->
                                applyMusicVideoFitTransform(textureView = textureView, state = state)
                            }
                        }
                    },
                factory = { context ->
                    TextureView(context).also {
                        output = it
                    }
                },
                update = { textureView ->
                    applyMusicVideoFitTransform(textureView, state)
                },
                onRelease = { released ->
                    if (output === released) output = null
                },
            )
        }
    }
}

internal fun applyMusicVideoFitTransform(textureView: TextureView, state: PlaybackVideoState) {
    val sourceWidth = state.width * state.pixelWidthHeightRatio
    val sourceHeight = state.height.toFloat()
    val viewWidth = textureView.width
    val viewHeight = textureView.height
    if (sourceWidth <= 0f || sourceHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) return
    val sourceAspect = sourceWidth / sourceHeight
    val viewAspect = viewWidth.toFloat() / viewHeight
    val scaleX = if (sourceAspect < viewAspect) sourceAspect / viewAspect else 1f
    val scaleY = if (sourceAspect > viewAspect) viewAspect / sourceAspect else 1f
    textureView.setTransform(
        Matrix().apply { setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f) },
    )
}
