package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SharpPlayPauseButton(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying) {
            Canvas(modifier = Modifier.size(size * 0.6f)) {
                val barWidth = this.size.width / 3f
                drawRect(
                    color = color,
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(barWidth, this.size.height),
                )
                drawRect(
                    color = color,
                    topLeft = Offset(this.size.width - barWidth, 0f),
                    size = androidx.compose.ui.geometry.Size(barWidth, this.size.height),
                )
            }
        } else {
            Canvas(modifier = Modifier.size(size * 0.6f)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
                    lineTo(0f, this@Canvas.size.height)
                    close()
                }
                drawPath(path = path, color = color)
            }
        }
    }
}
