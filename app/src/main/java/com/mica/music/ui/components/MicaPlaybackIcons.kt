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

    val queueMusic11: ImageVector by lazy {
        queueMusicIcon(
            name = "MicaQueueMusic11",
            noteHead = QueueNoteGeometry(
                left = 12f,
                top = 13f,
                width = 6.3f,
                height = 6f,
            ),
            list = QueueListGeometry(),
            stemLeft = 15.95f,
            stemWidth = 2.33f,
            stemHeight = 7.2f,
            beamWidth = 5.6f,
        )
    }

    val queueMusic18: ImageVector by lazy {
        queueMusicIcon(
            name = "MicaQueueMusic18",
            noteHead = QueueNoteGeometry(
                left = 13.19f,
                top = 14.29f,
                width = 6.27f,
                height = 5.7f,
            ),
            list = QueueListGeometry(
                firstTop = 5.76f,
                firstHeight = 2.06f,
                secondTop = 10.22f,
                secondHeight = 1.97f,
                thirdTop = 14.49f,
                thirdHeight = 2.06f,
            ),
            stemLeft = 17.53f,
            stemWidth = 1.93f,
            stemHeight = 8.3f,
            beamLeft = 17.53f,
            beamTop = 5.79f,
            beamWidth = 4.67f,
            beamHeight = 1.97f,
        )
    }

    val queueMusic19: ImageVector by lazy {
        queueMusicIcon(
            name = "MicaQueueMusic19",
            noteHead = QueueNoteGeometry(
                left = 13.19f,
                top = 14.46f,
                width = 6.27f,
                height = 5.53f,
            ),
            list = QueueListGeometry(
                firstLeft = 3.03f,
                firstTop = 5.76f,
                firstWidth = 11.97f,
                firstHeight = 2.06f,
                secondTop = 10.22f,
                secondHeight = 1.97f,
                thirdLeft = 3.03f,
                thirdTop = 14.49f,
                thirdWidth = 8f,
                thirdHeight = 2.06f,
            ),
            stemLeft = 17.53f,
            stemWidth = 1.93f,
            stemHeight = 8.57f,
            beamLeft = 17.53f,
            beamTop = 5.79f,
            beamWidth = 4.67f,
            beamHeight = 1.97f,
        )
    }
}

private data class QueueNoteGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private data class QueueListGeometry(
    val firstLeft: Float = 2.96f,
    val firstTop: Float = 5.96f,
    val firstWidth: Float = 12.07f,
    val firstHeight: Float = 2.03f,
    val secondLeft: Float = 3.02f,
    val secondTop: Float = 9.99f,
    val secondWidth: Float = 11.97f,
    val secondHeight: Float = 2f,
    val thirdLeft: Float = 2.96f,
    val thirdTop: Float = 13.99f,
    val thirdWidth: Float = 8.07f,
    val thirdHeight: Float = 2.03f,
)

private fun queueMusicIcon(
    name: String,
    noteHead: QueueNoteGeometry,
    list: QueueListGeometry,
    stemLeft: Float,
    stemWidth: Float,
    stemHeight: Float,
    beamLeft: Float = stemLeft,
    beamTop: Float = 6.02f,
    beamWidth: Float,
    beamHeight: Float = 2f,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        addRect(
            list.firstLeft,
            list.firstTop,
            list.firstLeft + list.firstWidth,
            list.firstTop + list.firstHeight,
        )
        addRect(
            list.secondLeft,
            list.secondTop,
            list.secondLeft + list.secondWidth,
            list.secondTop + list.secondHeight,
        )
        addRect(
            list.thirdLeft,
            list.thirdTop,
            list.thirdLeft + list.thirdWidth,
            list.thirdTop + list.thirdHeight,
        )
        addRect(noteHead.left, noteHead.top, noteHead.left + noteHead.width, noteHead.top + noteHead.height)
        addRect(stemLeft, 6.02f, stemLeft + stemWidth, 6.02f + stemHeight)
        addRect(beamLeft, beamTop, beamLeft + beamWidth, beamTop + beamHeight)
    }
}.build()

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
