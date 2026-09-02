package com.mica.music.audio.eq

import com.mica.music.audio.eq.EqBandConstants

/**
 * Software model of the legacy Android five-band graphic equalizer used by common AudioEffect
 * backends. PixelPlayer exposes ten UI sliders but, on a five-band device, pairs adjacent UI bands
 * before sending them to the platform equalizer. Keeping that mapping here avoids stacking two
 * independent low-frequency peaking filters for what is effectively one platform band.
 */
internal object AndroidFiveBandEqModel {
    const val BAND_COUNT = 5
    const val Q = 0.96

    val CENTER_HZ = intArrayOf(60, 230, 910, 3_600, 14_000)

    fun collapseUiLevels(levelsMillibels: ShortArray): ShortArray {
        if (levelsMillibels.size != EqBandConstants.BAND_COUNT) {
            return ShortArray(BAND_COUNT)
        }
        return ShortArray(BAND_COUNT) { band ->
            val first = levelsMillibels[band * 2].toInt()
            val second = levelsMillibels[band * 2 + 1].toInt()
            ((first + second) / 2).toShort()
        }
    }

    fun expandDeviceLevels(levelsMillibels: ShortArray): ShortArray {
        if (levelsMillibels.size != BAND_COUNT) {
            return EqBandConstants.defaultLevels()
        }
        return ShortArray(EqBandConstants.BAND_COUNT) { uiBand ->
            levelsMillibels[uiBand / 2]
        }
    }

    fun isLegacyFiveBandCenters(centersHz: List<Int>): Boolean =
        centersHz.size == BAND_COUNT && centersHz.indices.all { index ->
            centersHz[index] == CENTER_HZ[index]
        }
}
