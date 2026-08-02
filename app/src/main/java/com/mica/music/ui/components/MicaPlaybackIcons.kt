package com.mica.music.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom playback and queue icons for the player chrome.
 */
internal object MicaPlaybackIcons {
    val shuffleDisabled: ImageVector by lazy {
        ImageVector.Builder(
            name = "MicaShuffleDisabled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 4.5f)
                lineTo(16f, 7f)
                lineTo(5f, 7f)
                lineTo(5f, 9f)
                lineTo(16f, 9f)
                lineTo(16f, 11.5f)
                lineTo(19.5f, 8f)
                close()
                moveTo(16f, 12.5f)
                lineTo(16f, 15f)
                lineTo(5f, 15f)
                lineTo(5f, 17f)
                lineTo(16f, 17f)
                lineTo(16f, 19.5f)
                lineTo(19.5f, 16f)
                close()
            }
        }.build()
    }

    val queueMusic19: ImageVector by lazy {
        ImageVector.Builder(
            name = "MicaQueueMusic19",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                addRect(3.03f, 5.76f, 15f, 7.82f)
                addRect(3.02f, 10.22f, 14.99f, 12.19f)
                addRect(3.03f, 14.49f, 11.03f, 16.55f)
                addRect(13.19f, 14.46f, 19.46f, 19.99f)
                addRect(17.53f, 6.02f, 19.46f, 14.59f)
                addRect(17.53f, 5.79f, 22.2f, 7.76f)
            }
        }.build()
    }
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.addRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
}
