package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.mica.music.media.EqualizerBand
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.ln
import kotlin.math.max

/** 以折线展示当前 EQ 频响曲线（非实时频谱）。 */
@Composable
fun EqualizerCurveChart(
    bands: List<EqualizerBand>,
    minMillibels: Short,
    maxMillibels: Short,
    modifier: Modifier = Modifier,
) {
    if (bands.isEmpty()) return
    val accent = MicaTheme.colors.accent
    val grid = MicaTheme.colors.divider
    val zeroMb = (minMillibels.toInt() + maxMillibels.toInt()) / 2
    val minDb = minMillibels / 100f
    val maxDb = maxMillibels / 100f
    val dbMarks = listOf(maxDb, maxDb / 2f, 0f, minDb / 2f, minDb).distinct().sortedDescending()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp),
    ) {
        val padL = 28f
        val padR = 12f
        val padT = 8f
        val padB = 18f
        val chartW = size.width - padL - padR
        val chartH = size.height - padT - padB

        dbMarks.forEach { db ->
            val y = padT + chartH * (1f - (db - minDb) / (maxDb - minDb).coerceAtLeast(0.01f))
            drawLine(grid, Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 1f)
        }

        val minHz = bands.minOf { max(it.centerHz, 1) }.toFloat()
        val maxHz = bands.maxOf { it.centerHz }.toFloat().coerceAtLeast(minHz + 1f)
        val logMin = ln(minHz)
        val logMax = ln(maxHz)

        fun hzToX(hz: Int): Float {
            val logF = ln(max(hz, 1).toFloat())
            val t = if (logMax == logMin) 0.5f else (logF - logMin) / (logMax - logMin)
            return padL + t * chartW
        }

        fun mbToY(mb: Short): Float {
            val db = mb / 100f
            return padT + chartH * (1f - (db - minDb) / (maxDb - minDb).coerceAtLeast(0.01f))
        }

        val zeroY = mbToY(zeroMb.toShort())
        drawLine(grid.copy(alpha = 0.6f), Offset(padL, zeroY), Offset(size.width - padR, zeroY), strokeWidth = 1f)

        val points = bands.map { band -> Offset(hzToX(band.centerHz), mbToY(band.levelMillibels)) }
        for (i in 0 until points.lastIndex) {
            drawLine(accent, points[i], points[i + 1], strokeWidth = 2f)
        }
        points.forEach { pt ->
            drawRect(
                color = accent,
                topLeft = Offset(pt.x - 2f, pt.y - 6f),
                size = androidx.compose.ui.geometry.Size(4f, 12f),
            )
        }
    }
}
