package com.mica.music.ui.theme

import java.time.Instant
import kotlin.math.acos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarSystemEphemerisTest {
    @Test
    fun computeReturnsNineNormalizedGeocentricBodies() {
        val states = SolarSystemEphemeris.compute(Instant.parse("2026-09-05T10:00:00Z").toEpochMilli())

        assertEquals(9, states.size)
        assertEquals(SolarSystemBodyId.entries.toSet(), states.map { it.id }.toSet())
        states.forEach { state ->
            val lengthSquared =
                state.direction.x * state.direction.x +
                    state.direction.y * state.direction.y +
                    state.direction.z * state.direction.z
            assertTrue(
                "${state.id} direction must be normalized: $lengthSquared",
                lengthSquared in 0.999f..1.001f,
            )
        }
    }

    @Test
    fun solarPositionStaysAlignedWithSeasonalSkyModel() {
        listOf(
            "2026-03-20T12:00:00Z",
            "2026-06-21T12:00:00Z",
            "2026-09-22T12:00:00Z",
            "2026-12-21T12:00:00Z",
        ).forEach { timestamp ->
            val epochMillis = Instant.parse(timestamp).toEpochMilli()
            val ephemerisSun = SolarSystemEphemeris.compute(epochMillis)
                .first { it.id == SolarSystemBodyId.SUN }
                .direction
            val seasonalSun = SeasonalSkyModel.solarDirection(epochMillis)
            assertTrue(
                "$timestamp solar models diverged by ${angularDistanceDeg(ephemerisSun, seasonalSun)}°",
                angularDistanceDeg(ephemerisSun, seasonalSun) < 1.5,
            )
        }
    }

    @Test
    fun moonMovesRoughlyOneLunarDayArcPerDay() {
        val start = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val nextDay = start + 24L * 60L * 60L * 1000L
        val moonA = SolarSystemEphemeris.compute(start)
            .first { it.id == SolarSystemBodyId.MOON }
            .direction
        val moonB = SolarSystemEphemeris.compute(nextDay)
            .first { it.id == SolarSystemBodyId.MOON }
            .direction
        val movement = angularDistanceDeg(moonA, moonB)

        assertTrue("Moon moved only $movement°", movement > 8.0)
        assertTrue("Moon moved too far: $movement°", movement < 18.0)
    }

    private fun angularDistanceDeg(
        first: SeasonalSkyDirection,
        second: SeasonalSkyDirection,
    ): Double {
        val dot = (
            first.x * second.x +
                first.y * second.y +
                first.z * second.z
            ).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(dot.toDouble()))
    }
}
