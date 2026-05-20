package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.sin

private const val BarCount = 11
private const val TickMs = 48L
private val BarWidth = 1.5.dp
private val BarGap = 1.5.dp
private val SpectrumHeight = 38.dp

/**
 * 对称双峰：左/右峰中心 25% 与 75%，等高；中间谷底接近 0。
 */
private fun bimodalEnvelope(index: Int, count: Int): Float {
    if (count <= 1) return 1f
    val t = index / (count - 1).toFloat()
    val sigma2 = 0.0032f
    val left = exp(-((t - 0.25f) * (t - 0.25f)) / sigma2)
    val right = exp(-((t - 0.75f) * (t - 0.75f)) / sigma2)
    return ((left + right) / 2f).coerceIn(0f, 1f)
}

@Composable
fun MiniPlayerSpectrumBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = SpectrumHeight,
) {
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
            for (i in 0 until BarCount) {
                barHeights[i] = idleHeight(envelope[i])
            }
            return@LaunchedEffect
        }
        var frame = 0
        while (true) {
            val t = frame * 0.11f
            val global = 0.78f + 0.22f * sin(t * 3f)
            for (i in 0 until BarCount) {
                val ripple = 0.92f + 0.08f * sin(t * 4.8f + i * 0.38f)
                val shaped = envelope[i] * global * ripple
                barHeights[i] = shaped.coerceIn(0.08f, 1f)
            }
            frame++
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
