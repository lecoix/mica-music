package com.mica.music.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme

@Composable
fun PlayingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MicaTheme.colors.accent,
) {
    val transition = rememberInfiniteTransition(label = "playing")
    val h1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "h1"
    )
    val h2 by transition.animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "h2"
    )
    val h3 by transition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "h3"
    )

    Canvas(modifier = modifier.size(16.dp)) {
        val barWidth = size.width / 5f
        val gap = barWidth / 2f
        val heights = listOf(h1, h2, h3)
        heights.forEachIndexed { i, h ->
            val left = i * (barWidth + gap)
            val barHeight = size.height * h
            val top = size.height - barHeight
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
            )
        }
    }
}
