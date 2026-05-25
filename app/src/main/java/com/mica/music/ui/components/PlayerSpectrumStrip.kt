package com.mica.music.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.media.MicaSpectrumAnalyzer
import com.mica.music.ui.theme.PlayerContentColors
import kotlin.math.abs
import kotlin.math.sqrt

private const val SpectrumProbeTag = "MicaSpectrumProbe"
private const val SpectrumProbeEnabled = true

@Composable
fun LivePlayerSpectrumStrip(
    enabled: Boolean,
    isPlaying: Boolean,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    alpha: Float = 1f,
) {
    if (!enabled || alpha <= 0.01f) return
    val liveLevels by MicaSpectrumAnalyzer.levels.collectAsState()
    val silentLevels = remember { silentSpectrumLevels() }
    PlayerSpectrumStrip(
        levels = if (isPlaying) liveLevels else silentLevels,
        colors = colors,
        modifier = modifier,
        height = height,
        alpha = alpha,
    )
}

@Composable
fun PlayerSpectrumStrip(
    levels: List<Float>,
    colors: PlayerContentColors,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    alpha: Float = 1f,
) {
    val baseAlpha = alpha.coerceIn(0f, 1f)
    val targetLevels by rememberUpdatedState(levels)
    val displayLevels = remember(levels.size) { FloatArray(levels.size) }
    var frameTick by remember(levels.size) { mutableIntStateOf(0) }

    LaunchedEffect(levels.size) {
        if (levels.isEmpty()) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        var probeStartNanos = lastFrameNanos
        var probeFrames = 0
        var probeTargetFrames = 0
        var lastTargetLevels: List<Float>? = null
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameNanos
            val target = targetLevels
            if (SpectrumProbeEnabled) {
                probeFrames++
                if (target !== lastTargetLevels) {
                    probeTargetFrames++
                    lastTargetLevels = target
                }
                val elapsedNanos = frameNanos - probeStartNanos
                if (elapsedNanos >= 1_000_000_000L) {
                    val seconds = elapsedNanos / 1_000_000_000f
                    Log.d(
                        SpectrumProbeTag,
                        "ui fps=${(probeFrames / seconds).format1()} " +
                            "targetFps=${(probeTargetFrames / seconds).format1()} " +
                            "bars=${target.size} height=${height.value.format1()}",
                    )
                    probeStartNanos = frameNanos
                    probeFrames = 0
                    probeTargetFrames = 0
                }
            }
            val count = target.size
            if (count == 0) {
                continue
            }
            for (index in 0 until minOf(count, displayLevels.size)) {
                val from = displayLevels[index]
                val to = target[index].coerceIn(0f, 1f)
                val next = if (to >= from) {
                    from + (to - from) * 0.55f
                } else {
                    maxOf(to, from - 3.2f * dt)
                }
                displayLevels[index] = if (abs(next - to) < 0.001f) to else next
            }
            frameTick++
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        val count = displayLevels.size.coerceAtLeast(1)
        val barWidth = 2.dp.toPx()
        val gap = (size.width - barWidth * count) / (count - 1).coerceAtLeast(1).toFloat()
        displayLevels.forEachIndexed { index, raw ->
            val level = sqrt(raw.coerceIn(0f, 1f))
            if (level < 0.001f) return@forEachIndexed
            val t = index / count.toFloat()
            val barAlpha = (0.38f + 0.54f * (1f - t * 0.55f)) * baseAlpha
            val barHeight = (size.height * level).coerceAtLeast(1f)
            val x = index * (barWidth + gap)
            val y = size.height - barHeight
            drawRect(
                color = colors.primary.copy(alpha = barAlpha),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

fun silentSpectrumLevels(count: Int = 96): List<Float> = List(count) { 0f }

private fun Float.format1(): String = String.format("%.1f", this)
