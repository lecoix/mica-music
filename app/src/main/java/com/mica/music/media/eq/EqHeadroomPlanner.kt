package com.mica.music.media.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Estimates the peak gain of the complete graphic-EQ cascade.
 *
 * Looking only at the highest slider is insufficient because adjacent peaking filters overlap.
 * This planner is control-rate work: it runs only when EQ settings or sample rate change.
 */
internal object EqHeadroomPlanner {
    const val SAFETY_MARGIN_DB = 0.25
    private const val PROBE_COUNT = 192
    private const val MIN_PROBE_HZ = 20.0
    private const val MAX_PROBE_HZ = 20_000.0
    private const val MIN_MEANINGFUL_PEAK_DB = 0.01

    data class Plan(
        val responsePeakDb: Double,
        val preampDb: Double,
    )

    fun plan(
        levelsMillibels: ShortArray,
        sampleRateHz: Int,
    ): Plan {
        if (levelsMillibels.isEmpty() || levelsMillibels.all { it == 0.toShort() }) {
            return Plan(responsePeakDb = 0.0, preampDb = 0.0)
        }

        val deviceLevels = AndroidFiveBandEqModel.collapseUiLevels(levelsMillibels)
        val sampleRate = sampleRateHz.coerceAtLeast(1).toDouble()
        val nyquist = sampleRate * 0.5
        val maxFrequency = min(MAX_PROBE_HZ, nyquist * 0.99)
        if (maxFrequency <= 1.0) {
            return Plan(responsePeakDb = 0.0, preampDb = 0.0)
        }
        val minFrequency = min(MIN_PROBE_HZ, maxFrequency)
        val filters = buildList {
            deviceLevels.indices.forEach { index ->
                val gainDb = deviceLevels[index] / 100.0
                if (gainDb != 0.0) {
                    add(
                        Coefficients.peaking(
                            sampleRate = sampleRate,
                            centerHz = AndroidFiveBandEqModel.CENTER_HZ[index]
                                .toDouble()
                                .coerceIn(1.0, maxFrequency),
                            gainDb = gainDb,
                            q = AndroidFiveBandEqModel.Q,
                        ),
                    )
                }
            }
        }

        if (filters.isEmpty()) {
            return Plan(responsePeakDb = 0.0, preampDb = 0.0)
        }

        var peakDb = 0.0
        val logMin = ln(minFrequency)
        val logMax = ln(maxFrequency)
        repeat(PROBE_COUNT) { probe ->
            val t = if (PROBE_COUNT <= 1) 0.0 else probe.toDouble() / (PROBE_COUNT - 1)
            val frequency = exp(logMin + (logMax - logMin) * t)
            peakDb = maxOf(peakDb, responseDb(filters, sampleRate, frequency))
        }
        AndroidFiveBandEqModel.CENTER_HZ.forEach { center ->
            val frequency = center.toDouble().coerceIn(1.0, maxFrequency)
            peakDb = maxOf(peakDb, responseDb(filters, sampleRate, frequency))
        }

        if (!peakDb.isFinite() || peakDb <= MIN_MEANINGFUL_PEAK_DB) {
            return Plan(responsePeakDb = 0.0, preampDb = 0.0)
        }
        return Plan(
            responsePeakDb = peakDb,
            preampDb = -(peakDb + SAFETY_MARGIN_DB),
        )
    }

    private fun responseDb(
        filters: List<Coefficients>,
        sampleRate: Double,
        frequency: Double,
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val cos1 = cos(omega)
        val sin1 = sin(omega)
        val cos2 = cos(2.0 * omega)
        val sin2 = sin(2.0 * omega)

        var db = 0.0
        filters.forEach { filter ->
            val numeratorReal = filter.b0 + filter.b1 * cos1 + filter.b2 * cos2
            val numeratorImag = -(filter.b1 * sin1 + filter.b2 * sin2)
            val denominatorReal = 1.0 + filter.a1 * cos1 + filter.a2 * cos2
            val denominatorImag = -(filter.a1 * sin1 + filter.a2 * sin2)
            val numeratorPower = numeratorReal * numeratorReal + numeratorImag * numeratorImag
            val denominatorPower = denominatorReal * denominatorReal + denominatorImag * denominatorImag
            if (numeratorPower > 0.0 && denominatorPower > 0.0) {
                db += 10.0 * log10(numeratorPower / denominatorPower)
            }
        }
        return db
    }

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        companion object {
            fun peaking(
                sampleRate: Double,
                centerHz: Double,
                gainDb: Double,
                q: Double,
            ): Coefficients {
                val safeQ = q.coerceIn(0.2, 20.0)
                val safeCenter = centerHz.coerceIn(1.0, sampleRate * 0.5 * 0.99)
                val a = 10.0.pow(gainDb / 40.0)
                val omega = 2.0 * PI * safeCenter / sampleRate
                val sinW = sin(omega)
                val cosW = cos(omega)
                val alpha = sinW / (2.0 * safeQ)
                val b0n = 1.0 + alpha * a
                val b1n = -2.0 * cosW
                val b2n = 1.0 - alpha * a
                val a0n = 1.0 + alpha / a
                val a1n = -2.0 * cosW
                val a2n = 1.0 - alpha / a
                return Coefficients(
                    b0 = b0n / a0n,
                    b1 = b1n / a0n,
                    b2 = b2n / a0n,
                    a1 = a1n / a0n,
                    a2 = a2n / a0n,
                )
            }
        }
    }
}
