package com.mica.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mica.music.data.PlayerController

internal class PlaybackSeekState(
    val sliderValue: Float,
    val displaySec: Int,
    val totalSec: Int,
    val valueRange: ClosedFloatingPointRange<Float>,
    val onValueChange: (Float) -> Unit,
    val onValueChangeFinished: () -> Unit,
)

@Composable
internal fun rememberPlaybackSeekState(playerController: PlayerController): PlaybackSeekState {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val totalSec = if (playerController.durationSec > 0) {
        playerController.durationSec
    } else {
        playerController.currentSong?.durationSec ?: 0
    }
    val totalFloat = totalSec.coerceAtLeast(1).toFloat()
    val sliderValue = if (isDragging) dragValue else playerController.positionSec.toFloat()

    return PlaybackSeekState(
        sliderValue = sliderValue,
        displaySec = sliderValue.toInt(),
        totalSec = totalSec,
        valueRange = 0f..totalFloat,
        onValueChange = { value ->
            isDragging = true
            dragValue = value
        },
        onValueChangeFinished = {
            playerController.seek(dragValue.toInt())
            isDragging = false
        },
    )
}
