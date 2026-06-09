package com.mica.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.mica.music.data.Song
import com.mica.music.data.TrackSkipDirection
import com.mica.music.ui.motion.rememberMicaMotionEnabled

@Composable
fun NowPlayingTrackWipe(
    targetSong: Song,
    consumeSkipDirection: () -> TrackSkipDirection?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (Song) -> Unit,
) {
    val motionEnabled = rememberMicaMotionEnabled()
    val currentSongState = remember { mutableStateOf(targetSong) }
    val slideOffset = remember { Animatable(0f) }

    LaunchedEffect(enabled, targetSong.id) {
        if (!enabled) {
            consumeSkipDirection()
            currentSongState.value = targetSong
            slideOffset.snapTo(0f)
            return@LaunchedEffect
        }
        if (targetSong.id == currentSongState.value.id) return@LaunchedEffect
        val direction = consumeSkipDirection()
        val startOffset = when (direction) {
            TrackSkipDirection.TO_NEXT -> 0.08f
            TrackSkipDirection.TO_PREVIOUS -> -0.08f
            null -> 0f
        }
        currentSongState.value = targetSong
        slideOffset.snapTo(startOffset)
        slideOffset.animateTo(
            targetValue = 0f,
            animationSpec = if (motionEnabled) tween(160, easing = LinearEasing) else tween(0),
        )
    }

    if (!enabled) {
        content(targetSong)
    } else {
        val currentSong = currentSongState.value
        Box(
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * slideOffset.value
                },
        ) {
            content(currentSong)
        }
    }
}
