package com.mica.music.ui.theme

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-cost seasonal sky model.
 *
 * This intentionally models only the geocentric celestial sphere. It does not claim to represent
 * the user's local horizon: no location, altitude, or sidereal-time correction is involved.
 */
internal object SeasonalSkyModel {
    fun solarDirection(epochMillis: Long): SeasonalSkyDirection {
        val daysSinceJ2000 = (epochMillis - J2000EpochMillis).toDouble() / MillisPerDay
        val meanAnomaly = normalizeDegrees(357.529 + 0.98560028 * daysSinceJ2000).toRadians()
        val meanLongitude = normalizeDegrees(280.459 + 0.98564736 * daysSinceJ2000)
        val eclipticLongitude = normalizeDegrees(
            meanLongitude +
                1.915 * sin(meanAnomaly) +
                0.020 * sin(2.0 * meanAnomaly),
        ).toRadians()
        val obliquity = (23.439 - 0.00000036 * daysSinceJ2000).toRadians()

        val rightAscension = atan2(
            cos(obliquity) * sin(eclipticLongitude),
            cos(eclipticLongitude),
        )
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val cosDeclination = cos(declination)

        return SeasonalSkyDirection(
            x = (cosDeclination * cos(rightAscension)).toFloat(),
            y = sin(declination).toFloat(),
            z = (cosDeclination * sin(rightAscension)).toFloat(),
        )
    }

    fun nightDirection(epochMillis: Long): SeasonalSkyDirection {
        val sun = solarDirection(epochMillis)
        return SeasonalSkyDirection(-sun.x, -sun.y, -sun.z)
    }

    private fun normalizeDegrees(value: Double): Double {
        val wrapped = value % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private const val J2000EpochMillis = 946_728_000_000L
    private const val MillisPerDay = 86_400_000.0
}

internal data class SeasonalSkyDirection(
    val x: Float,
    val y: Float,
    val z: Float,
)
