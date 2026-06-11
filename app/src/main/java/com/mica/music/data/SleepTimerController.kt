package com.mica.music.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SleepTimerState(
    val endTimeMillis: Long,
    val fadeStartTimeMillis: Long,
    val isFading: Boolean = false,
)

/**
 * 睡眠播放定时：墙钟倒计时，最后 [FADE_DURATION_MS] 线性渐弱后暂停。
 * 不因切歌、换歌单或手动暂停而取消。
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val playerController: PlayerController,
) {
    var state by mutableStateOf<SleepTimerState?>(null)
        private set

    /** 每秒递增，驱动 Compose 刷新剩余时间。 */
    var displayTick by mutableIntStateOf(0)
        private set

    var onExpired: (() -> Unit)? = null

    private var tickJob: Job? = null
    private var fadeJob: Job? = null

    val isActive: Boolean
        get() = state != null

    val remainingMs: Long
        get() {
            val end = state?.endTimeMillis ?: return 0L
            return (end - System.currentTimeMillis()).coerceAtLeast(0L)
        }

    fun start(durationMinutes: Int) {
        cancelInternal(restoreVolume = false)
        val now = System.currentTimeMillis()
        val durationMs = durationMinutes.coerceAtLeast(1) * 60_000L
        val end = now + durationMs
        state = SleepTimerState(
            endTimeMillis = end,
            fadeStartTimeMillis = (end - FADE_DURATION_MS).coerceAtLeast(now),
        )
        playerController.setPlaybackVolume(1f)
        startTickLoop()
    }

    fun cancel() {
        cancelInternal(restoreVolume = true)
    }

    fun formatRemaining(): String {
        val totalSec = (remainingMs + 999) / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    fun menuLabel(): String =
        if (isActive) "睡眠定时 · 剩余 ${formatRemaining()}" else "睡眠定时"

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val current = state ?: break
                val now = System.currentTimeMillis()
                displayTick++

                if (now >= current.fadeStartTimeMillis && fadeJob == null) {
                    state = current.copy(isFading = true)
                    startFadeLoop(current.fadeStartTimeMillis, current.endTimeMillis)
                }
                if (now >= current.endTimeMillis) {
                    onExpire()
                    break
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun startFadeLoop(fadeStartMillis: Long, endTimeMillis: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            while (isActive) {
                if (state == null) break
                val now = System.currentTimeMillis()
                if (now >= endTimeMillis) break
                val progress = ((now - fadeStartMillis).toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
                playerController.setPlaybackVolume(1f - progress)
                delay(FADE_STEP_MS)
            }
        }
    }

    private fun onExpire() {
        fadeJob?.cancel()
        fadeJob = null
        tickJob?.cancel()
        tickJob = null
        if (playerController.isPlaying) {
            playerController.pauseIfPlaying()
        }
        playerController.setPlaybackVolume(1f)
        state = null
        onExpired?.invoke()
    }

    private fun cancelInternal(restoreVolume: Boolean) {
        tickJob?.cancel()
        fadeJob?.cancel()
        tickJob = null
        fadeJob = null
        if (restoreVolume) {
            playerController.setPlaybackVolume(1f)
        }
        state = null
    }

    companion object {
        const val FADE_DURATION_MS = 30_000L
        private const val FADE_STEP_MS = 200L
        private const val TICK_INTERVAL_MS = 1_000L

        val PRESET_MINUTES = listOf(1, 15, 30, 45, 60, 90)

        val stepCount: Int get() = PRESET_MINUTES.size

        fun presetLabel(minutes: Int): String =
            if (minutes == 1) "1 分钟" else "$minutes 分钟"

        fun minutesAtStep(stepIndex: Int): Int =
            PRESET_MINUTES[stepIndex.coerceIn(0, PRESET_MINUTES.lastIndex)]

        fun stepFraction(stepIndex: Int): Float {
            val last = PRESET_MINUTES.lastIndex
            if (last <= 0) return 0f
            return stepIndex.coerceIn(0, last).toFloat() / last
        }

        fun snapStepIndexFromFraction(fraction: Float): Int {
            val last = PRESET_MINUTES.lastIndex
            if (last <= 0) return 0
            return (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        }
    }
}
