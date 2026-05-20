package com.mica.music.media.eq

/** 10 段对数分布软件均衡器中心频率（Hz）。 */
object EqBandConstants {
    const val BAND_COUNT = 10
    const val MIN_MILLIBELS: Short = -1200
    const val MAX_MILLIBELS: Short = 1200

    val CENTER_HZ: IntArray = intArrayOf(
        32, 64, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000,
    )

    fun flatLevelMillibels(): Short =
        ((MIN_MILLIBELS.toInt() + MAX_MILLIBELS.toInt()) / 2).toShort()

    fun defaultLevels(): ShortArray =
        ShortArray(BAND_COUNT) { flatLevelMillibels() }
}
