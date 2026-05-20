package com.mica.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mica.music.data.PlayerController
import kotlin.math.min
import kotlin.math.roundToInt

internal class PlaybackSeekState(
    val sliderValue: Float,
    val displaySec: Int,
    val totalSec: Int,
    val valueRange: ClosedFloatingPointRange<Float>,
    val onValueChange: (Float) -> Unit,
    val onValueChangeFinished: () -> Unit,
)

/**
 * 拖动进度条时与播放器进度解耦，避免 ALAC/时长变化导致条在手指下乱跳。
 */
@Composable
internal fun rememberPlaybackSeekState(playerController: PlayerController): PlaybackSeekState {
    var isDragging by remember { mutableStateOf(false) }
    var dragValueSec by remember { mutableFloatStateOf(0f) }
    /** 按下时锁定的总时长，拖动过程中不随 metadata 变化而缩放比例 */
    var dragAnchorTotalMs by remember { mutableIntStateOf(-1) }
    /** 拖动期间锁定的 valueRange 上界，避免时长变化导致 pointerInput 重建、条乱跳 */
    var dragRangeEndSec by remember { mutableFloatStateOf(-1f) }

    val liveTotalMs = playerController.uiDurationMs().coerceAtLeast(1)
    val totalMs = when {
        isDragging && dragAnchorTotalMs > 0 -> dragAnchorTotalMs
        else -> liveTotalMs
    }.coerceAtLeast(1)
    val totalSec = (totalMs + 999) / 1000
    val totalFloat = totalMs / 1000f
    val rangeEndSec = if (isDragging && dragRangeEndSec > 0f) dragRangeEndSec else totalFloat

    val liveSec = playerController.uiPositionMs().coerceIn(0, liveTotalMs) / 1000f

    val sliderValueSec = if (isDragging) {
        dragValueSec.coerceIn(0f, rangeEndSec)
    } else {
        liveSec
    }

    val displaySec = min(sliderValueSec.roundToInt(), totalSec)

    return PlaybackSeekState(
        sliderValue = sliderValueSec,
        displaySec = displaySec,
        totalSec = totalSec,
        valueRange = 0f..rangeEndSec.coerceAtLeast(1f),
        onValueChange = { value ->
            if (!isDragging) {
                playerController.setAlacSeekUiActive(true)
                dragAnchorTotalMs = liveTotalMs
                dragRangeEndSec = liveTotalMs / 1000f
                dragValueSec = liveSec.coerceIn(0f, dragRangeEndSec)
            }
            isDragging = true
            val cap = if (dragRangeEndSec > 0f) dragRangeEndSec else dragAnchorTotalMs.coerceAtLeast(1) / 1000f
            dragValueSec = value.coerceIn(0f, cap)
        },
        onValueChangeFinished = {
            val capMs = if (dragAnchorTotalMs > 0) dragAnchorTotalMs else liveTotalMs
            val targetMs = (dragValueSec * 1000f).roundToInt().coerceIn(0, capMs)
            playerController.setAlacSeekUiActive(false)
            playerController.seekToMs(targetMs)
            isDragging = false
            dragAnchorTotalMs = -1
            dragRangeEndSec = -1f
        },
    )
}
