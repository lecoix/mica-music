package com.mica.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.motion.rememberMicaMotionEnabled

/**
 * 播放页整页切歌：双层裁剪分界扫过（不画单独分割线）。
 * 主层结构保持不变，避免擦除结束后重组导致闪屏。
 */
@Composable
fun NowPlayingTrackWipe(
    targetSong: Song,
    consumeSkipDirection: () -> TrackSkipDirection?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (Song) -> Unit,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    var holdSong by remember { mutableStateOf(targetSong) }
    var outgoingSong by remember { mutableStateOf<Song?>(null) }
    var wipeDirection by remember { mutableStateOf(TrackSkipDirection.TO_NEXT) }
    var wiping by remember { mutableStateOf(false) }
    val wipeProgress = remember { Animatable(0f) }

    LaunchedEffect(enabled, targetSong.id) {
        if (!enabled) {
            consumeSkipDirection()
            holdSong = targetSong
            outgoingSong = null
            wiping = false
            wipeProgress.snapTo(0f)
            return@LaunchedEffect
        }
        if (targetSong.id == holdSong.id) return@LaunchedEffect
        val direction = consumeSkipDirection()
        if (direction == null) {
            holdSong = targetSong
            outgoingSong = null
            wiping = false
            wipeProgress.snapTo(0f)
            return@LaunchedEffect
        }
        wipeDirection = direction
        outgoingSong = holdSong
        wiping = true
        wipeProgress.snapTo(0f)
        wipeProgress.animateTo(
            targetValue = 1f,
            animationSpec = MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationMediumMs),
        )
        holdSong = targetSong
        outgoingSong = null
        wiping = false
    }

    val seamFraction = when {
        wiping -> when (wipeDirection) {
            TrackSkipDirection.TO_NEXT -> 1f - wipeProgress.value
            TrackSkipDirection.TO_PREVIOUS -> wipeProgress.value
        }
        wipeDirection == TrackSkipDirection.TO_NEXT -> 0f
        else -> 1f
    }

    val incomingSong = if (wiping) targetSong else holdSong

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .trackWipeClip(seamFraction, wipeDirection, isIncomingLayer = true),
        ) {
            key(incomingSong.id) {
                content(incomingSong)
            }
        }
        val outgoing = outgoingSong
        if (wiping && outgoing != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .trackWipeClip(seamFraction, wipeDirection, isIncomingLayer = false),
            ) {
                key(outgoing.id) {
                    content(outgoing)
                }
            }
        }
    }
}

private fun Modifier.trackWipeClip(
    seamFraction: Float,
    direction: TrackSkipDirection,
    isIncomingLayer: Boolean,
): Modifier = drawWithContent {
    val seamX = (size.width * seamFraction).coerceIn(0f, size.width)
    val (left, right) = when (direction) {
        TrackSkipDirection.TO_NEXT ->
            if (isIncomingLayer) seamX to size.width else 0f to seamX
        TrackSkipDirection.TO_PREVIOUS ->
            if (isIncomingLayer) 0f to seamX else seamX to size.width
    }
    clipRect(left, 0f, right, size.height) {
        this@drawWithContent.drawContent()
    }
}
