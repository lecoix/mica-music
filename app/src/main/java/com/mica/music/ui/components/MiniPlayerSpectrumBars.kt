package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.media.MicaSpectrumAnalyzer
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.sqrt

private const val BarCount = 11
private const val TickMs = 48L
private val BarWidth = 1.5.dp
private val BarGap = 1.5.dp
private val SpectrumHeight = 38.dp

private fun bimodalEnvelope(index: Int, count: Int): Float {
    if (count <= 1) return 1f
    val t = index / (count - 1).toFloat()
    val sigma2 = 0.0032f
    val left = exp(-((t - 0.25f) * (t - 0.25f)) / sigma2)
    val right = exp(-((t - 0.75f) * (t - 0.75f)) / sigma2)
    return ((left + right) / 2f).coerceIn(0f, 1f)
}

private fun downsampleBands(bands: List<Float>, targetCount: Int): FloatArray {
    val result = FloatArray(targetCount)
    if (bands.isEmpty()) return result
    val n = bands.size
    val step = n.toFloat() / targetCount
    for (i in 0 until targetCount) {
        val from = (i * step).toInt()
        val to = minOf(((i + 1) * step).toInt(), n)
        if (to <= from) {
            result[i] = if (from < n) bands[from] else 0f
            continue
        }
        var peak = 0f
        for (j in from until to) {
            if (bands[j] > peak) peak = bands[j]
        }
        result[i] = sqrt(peak.coerceIn(0f, 1f))
    }
    return result
}

@Composable
fun MiniPlayerSpectrumBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = SpectrumHeight,
) {
    val liveLevels by MicaSpectrumAnalyzer.levels.collectAsState()
    val currentLevels by rememberUpdatedState(liveLevels)

    val envelope = remember {
        FloatArray(BarCount) { bimodalEnvelope(it, BarCount) }
    }
    val barHeights = remember {
        mutableStateListOf(*FloatArray(BarCount) { idleHeight(envelope[it]) }.toTypedArray())
    }
    val accent = MicaTheme.colors.accent
    val spectrumWidth = BarWidth * BarCount + BarGap * (BarCount - 1)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            for (step in 0..15) {
                for (i in 0 until BarCount) {
                    val idle = idleHeight(envelope[i])
                    barHeights[i] = barHeights[i] + (idle - barHeights[i]) * 0.25f
                }
                delay(TickMs)
            }
            for (i in 0 until BarCount) barHeights[i] = idleHeight(envelope[i])
            return@LaunchedEffect
        }
        while (true) {
            val target = downsampleBands(currentLevels, BarCount)
            for (i in 0 until BarCount) {
                val current = barHeights[i]
                val dest = target[i].coerceIn(0.08f, 1f)
                val smoothed = if (dest > current) {
                    current + (dest - current) * 0.5f
                } else {
                    current + (dest - current) * 0.4f
                }
                barHeights[i] = smoothed.coerceIn(0.08f, 1f)
            }
            delay(TickMs)
        }
    }

    Box(
        modifier = modifier
            .width(spectrumWidth)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(BarGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            barHeights.forEach { fraction ->
                val barH = height * fraction
                Box(
                    modifier = Modifier
                        .width(BarWidth)
                        .height(barH)
                        .background(accent.copy(alpha = 0.48f + fraction * 0.52f)),
                )
            }
        }
    }
}

private fun idleHeight(envelope: Float): Float =
    0.1f + envelope * 0.22f
