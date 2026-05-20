package com.mica.music.media.eq

import kotlin.math.ln
import kotlin.math.max

/** 将系统 EQ 频段（数量/频率不固定）映射到 10 段软件 EQ。 */
object EqBandMapper {

    fun mapToSoftwareBands(
        source: List<Pair<Int, Short>>,
        targetHz: IntArray = EqBandConstants.CENTER_HZ,
    ): ShortArray {
        if (source.isEmpty()) return EqBandConstants.defaultLevels()
        if (source.size == targetHz.size &&
            source.zip(targetHz.toList()).all { (band, hz) -> band.first == hz }
        ) {
            return ShortArray(targetHz.size) { source[it].second }
        }

        val sorted = source.sortedBy { it.first }
        val minHz = sorted.first().first.toDouble().coerceAtLeast(1.0)
        val maxHz = sorted.last().first.toDouble().coerceAtLeast(minHz + 1.0)
        val logMin = ln(minHz)
        val logMax = ln(maxHz)

        return ShortArray(targetHz.size) { index ->
            val targetLog = ln(max(targetHz[index], 1).toDouble())
            val t = if (logMax == logMin) 0.5 else (targetLog - logMin) / (logMax - logMin)
            val sourceIndex = t * (sorted.size - 1)
            val lower = sourceIndex.toInt().coerceIn(0, sorted.lastIndex)
            val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
            val frac = sourceIndex - lower
            val lowerMb = sorted[lower].second.toInt()
            val upperMb = sorted[upper].second.toInt()
            (lowerMb + (upperMb - lowerMb) * frac).toInt().toShort()
        }
    }

    fun normalizeLevels(levels: List<Short>): ShortArray {
        val target = EqBandConstants.defaultLevels()
        if (levels.isEmpty()) return target
        if (levels.size == EqBandConstants.BAND_COUNT) {
            return levels.toShortArray()
        }
        if (levels.size == 5) {
            val typical5Hz = intArrayOf(60, 230, 910, 3_600, 14_000)
            return mapToSoftwareBands(typical5Hz.zip(levels))
        }
        val source = levels.mapIndexed { index, level ->
            val hz = EqBandConstants.CENTER_HZ.getOrElse(index) { EqBandConstants.CENTER_HZ.last() }
            hz to level
        }
        return mapToSoftwareBands(source)
    }
}
