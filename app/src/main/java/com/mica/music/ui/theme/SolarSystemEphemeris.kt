package com.mica.music.ui.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class SolarSystemBodyId {
    SUN,
    MOON,
    MERCURY,
    VENUS,
    MARS,
    JUPITER,
    SATURN,
    URANUS,
    NEPTUNE,
}

internal data class SolarSystemBodyState(
    val id: SolarSystemBodyId,
    val direction: SeasonalSkyDirection,
    val sizePx: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val haloStrength: Float,
)

/**
 * Low-precision geocentric ephemeris intended for the decorative star-map background.
 *
 * The major planets use compact J2000 osculating-element approximations with secular rates.
 * The Moon uses a lightweight geocentric orbital model. The result is suitable for placing
 * bodies on the celestial sphere, not for navigation, observation planning, or scientific work.
 */
internal object SolarSystemEphemeris {
    fun compute(epochMillis: Long): List<SolarSystemBodyState> {
        val julianDate = epochMillis / MillisPerDay + UnixEpochJulianDate
        val centuries = (julianDate - J2000JulianDate) / DaysPerJulianCentury
        val earth = heliocentricPosition(EarthElements.at(centuries))

        val states = ArrayList<SolarSystemBodyState>(9)
        states += state(
            SolarSystemBodyId.SUN,
            eclipticToEquatorialDirection(earth * -1.0, centuries),
            sizePx = 54f,
            color = Triple(1.00f, 0.82f, 0.48f),
            haloStrength = 1.00f,
        )
        states += state(
            SolarSystemBodyId.MOON,
            moonDirection(julianDate, centuries),
            sizePx = 48f,
            color = Triple(0.88f, 0.93f, 1.00f),
            haloStrength = 0.78f,
        )

        for (planet in PlanetVisuals) {
            val heliocentric = heliocentricPosition(planet.elements.at(centuries))
            val geocentric = heliocentric - earth
            states += state(
                id = planet.id,
                direction = eclipticToEquatorialDirection(geocentric, centuries),
                sizePx = planet.sizePx,
                color = planet.color,
                haloStrength = planet.haloStrength,
            )
        }
        return states
    }

    private fun moonDirection(
        julianDate: Double,
        centuries: Double,
    ): SeasonalSkyDirection {
        val days = julianDate - MoonEpochJulianDate
        val ascendingNode = normalizeDegrees(125.1228 - 0.0529538083 * days).toRadians()
        val inclination = 5.1454.toRadians()
        val argumentOfPerigee = normalizeDegrees(318.0634 + 0.1643573223 * days).toRadians()
        val eccentricity = 0.054900
        val meanAnomaly = normalizeDegrees(115.3654 + 13.0649929509 * days).toRadians()

        val eccentricAnomaly = solveKepler(meanAnomaly, eccentricity)
        val orbitalX = cos(eccentricAnomaly) - eccentricity
        val orbitalY = sqrt(1.0 - eccentricity * eccentricity) * sin(eccentricAnomaly)
        val trueAnomaly = atan2(orbitalY, orbitalX)

        val argument = trueAnomaly + argumentOfPerigee
        val ecliptic = Vector3d(
            x = cos(ascendingNode) * cos(argument) -
                sin(ascendingNode) * sin(argument) * cos(inclination),
            y = sin(ascendingNode) * cos(argument) +
                cos(ascendingNode) * sin(argument) * cos(inclination),
            z = sin(argument) * sin(inclination),
        )
        return eclipticToEquatorialDirection(ecliptic, centuries)
    }

    private fun heliocentricPosition(elements: OrbitalElements): Vector3d {
        val meanAnomaly = normalizeDegrees(elements.meanLongitude - elements.longitudePerihelion)
            .toRadians()
        val eccentricAnomaly = solveKepler(meanAnomaly, elements.eccentricity)
        val orbitalX = elements.semiMajorAxis * (cos(eccentricAnomaly) - elements.eccentricity)
        val orbitalY = elements.semiMajorAxis *
            sqrt(1.0 - elements.eccentricity * elements.eccentricity) *
            sin(eccentricAnomaly)

        val node = elements.longitudeAscendingNode.toRadians()
        val inclination = elements.inclination.toRadians()
        val argumentPerihelion =
            normalizeDegrees(elements.longitudePerihelion - elements.longitudeAscendingNode)
                .toRadians()

        val cosNode = cos(node)
        val sinNode = sin(node)
        val cosInclination = cos(inclination)
        val sinInclination = sin(inclination)
        val cosPerihelion = cos(argumentPerihelion)
        val sinPerihelion = sin(argumentPerihelion)

        return Vector3d(
            x = orbitalX * (cosPerihelion * cosNode - sinPerihelion * sinNode * cosInclination) +
                orbitalY * (-sinPerihelion * cosNode - cosPerihelion * sinNode * cosInclination),
            y = orbitalX * (cosPerihelion * sinNode + sinPerihelion * cosNode * cosInclination) +
                orbitalY * (-sinPerihelion * sinNode + cosPerihelion * cosNode * cosInclination),
            z = orbitalX * (sinPerihelion * sinInclination) +
                orbitalY * (cosPerihelion * sinInclination),
        )
    }

    private fun eclipticToEquatorialDirection(
        ecliptic: Vector3d,
        centuries: Double,
    ): SeasonalSkyDirection {
        val obliquity = (
            23.43929111 -
                0.013004167 * centuries -
                0.000000164 * centuries * centuries
            ).toRadians()
        val equatorialX = ecliptic.x
        val equatorialY = ecliptic.y * cos(obliquity) - ecliptic.z * sin(obliquity)
        val equatorialZ = ecliptic.y * sin(obliquity) + ecliptic.z * cos(obliquity)
        val length = sqrt(
            equatorialX * equatorialX +
                equatorialY * equatorialY +
                equatorialZ * equatorialZ,
        ).coerceAtLeast(1e-12)

        // StarMap uses x=cos(dec)cos(ra), y=sin(dec), z=cos(dec)sin(ra).
        return SeasonalSkyDirection(
            x = (equatorialX / length).toFloat(),
            y = (equatorialZ / length).toFloat(),
            z = (equatorialY / length).toFloat(),
        )
    }

    private fun solveKepler(meanAnomaly: Double, eccentricity: Double): Double {
        var eccentricAnomaly = meanAnomaly
        repeat(8) {
            val delta = (
                eccentricAnomaly -
                    eccentricity * sin(eccentricAnomaly) -
                    meanAnomaly
                ) / (1.0 - eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= delta
            if (abs(delta) < 1e-10) return eccentricAnomaly
        }
        return eccentricAnomaly
    }

    private fun state(
        id: SolarSystemBodyId,
        direction: SeasonalSkyDirection,
        sizePx: Float,
        color: Triple<Float, Float, Float>,
        haloStrength: Float,
    ): SolarSystemBodyState = SolarSystemBodyState(
        id = id,
        direction = direction,
        sizePx = sizePx,
        red = color.first,
        green = color.second,
        blue = color.third,
        haloStrength = haloStrength,
    )

    private fun normalizeDegrees(value: Double): Double {
        val wrapped = value % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private data class Vector3d(
        val x: Double,
        val y: Double,
        val z: Double,
    ) {
        operator fun minus(other: Vector3d): Vector3d =
            Vector3d(x - other.x, y - other.y, z - other.z)

        operator fun times(scale: Double): Vector3d =
            Vector3d(x * scale, y * scale, z * scale)
    }

    private data class OrbitalElements(
        val semiMajorAxis: Double,
        val eccentricity: Double,
        val inclination: Double,
        val meanLongitude: Double,
        val longitudePerihelion: Double,
        val longitudeAscendingNode: Double,
    )

    private data class ElementSet(
        val semiMajorAxis: Coefficient,
        val eccentricity: Coefficient,
        val inclination: Coefficient,
        val meanLongitude: Coefficient,
        val longitudePerihelion: Coefficient,
        val longitudeAscendingNode: Coefficient,
    ) {
        fun at(centuries: Double): OrbitalElements = OrbitalElements(
            semiMajorAxis = semiMajorAxis.at(centuries),
            eccentricity = eccentricity.at(centuries),
            inclination = inclination.at(centuries),
            meanLongitude = normalizeDegrees(meanLongitude.at(centuries)),
            longitudePerihelion = normalizeDegrees(longitudePerihelion.at(centuries)),
            longitudeAscendingNode = normalizeDegrees(longitudeAscendingNode.at(centuries)),
        )
    }

    private data class Coefficient(
        val base: Double,
        val ratePerCentury: Double,
    ) {
        fun at(centuries: Double): Double = base + ratePerCentury * centuries
    }

    private data class PlanetVisual(
        val id: SolarSystemBodyId,
        val elements: ElementSet,
        val sizePx: Float,
        val color: Triple<Float, Float, Float>,
        val haloStrength: Float,
    )

    private val EarthElements = ElementSet(
        semiMajorAxis = Coefficient(1.00000261, 0.00000562),
        eccentricity = Coefficient(0.01671123, -0.00004392),
        inclination = Coefficient(-0.00001531, -0.01294668),
        meanLongitude = Coefficient(100.46457166, 35999.37244981),
        longitudePerihelion = Coefficient(102.93768193, 0.32327364),
        longitudeAscendingNode = Coefficient(0.0, 0.0),
    )

    private val PlanetVisuals = listOf(
        PlanetVisual(
            SolarSystemBodyId.MERCURY,
            ElementSet(
                Coefficient(0.38709927, 0.00000037),
                Coefficient(0.20563593, 0.00001906),
                Coefficient(7.00497902, -0.00594749),
                Coefficient(252.25032350, 149472.67411175),
                Coefficient(77.45779628, 0.16047689),
                Coefficient(48.33076593, -0.12534081),
            ),
            26f,
            Triple(0.80f, 0.78f, 0.72f),
            0.42f,
        ),
        PlanetVisual(
            SolarSystemBodyId.VENUS,
            ElementSet(
                Coefficient(0.72333566, 0.00000390),
                Coefficient(0.00677672, -0.00004107),
                Coefficient(3.39467605, -0.00078890),
                Coefficient(181.97909950, 58517.81538729),
                Coefficient(131.60246718, 0.00268329),
                Coefficient(76.67984255, -0.27769418),
            ),
            36f,
            Triple(1.00f, 0.88f, 0.67f),
            0.72f,
        ),
        PlanetVisual(
            SolarSystemBodyId.MARS,
            ElementSet(
                Coefficient(1.52371034, 0.00001847),
                Coefficient(0.09339410, 0.00007882),
                Coefficient(1.84969142, -0.00813131),
                Coefficient(-4.55343205, 19140.30268499),
                Coefficient(-23.94362959, 0.44441088),
                Coefficient(49.55953891, -0.29257343),
            ),
            30f,
            Triple(1.00f, 0.49f, 0.29f),
            0.56f,
        ),
        PlanetVisual(
            SolarSystemBodyId.JUPITER,
            ElementSet(
                Coefficient(5.20288700, -0.00011607),
                Coefficient(0.04838624, -0.00013253),
                Coefficient(1.30439695, -0.00183714),
                Coefficient(34.39644051, 3034.74612775),
                Coefficient(14.72847983, 0.21252668),
                Coefficient(100.47390909, 0.20469106),
            ),
            34f,
            Triple(0.96f, 0.81f, 0.65f),
            0.66f,
        ),
        PlanetVisual(
            SolarSystemBodyId.SATURN,
            ElementSet(
                Coefficient(9.53667594, -0.00125060),
                Coefficient(0.05386179, -0.00050991),
                Coefficient(2.48599187, 0.00193609),
                Coefficient(49.95424423, 1222.49362201),
                Coefficient(92.59887831, -0.41897216),
                Coefficient(113.66242448, -0.28867794),
            ),
            30f,
            Triple(0.94f, 0.80f, 0.51f),
            0.52f,
        ),
        PlanetVisual(
            SolarSystemBodyId.URANUS,
            ElementSet(
                Coefficient(19.18916464, -0.00196176),
                Coefficient(0.04725744, -0.00004397),
                Coefficient(0.77263783, -0.00242939),
                Coefficient(313.23810451, 428.48202785),
                Coefficient(170.95427630, 0.40805281),
                Coefficient(74.01692503, 0.04240589),
            ),
            24f,
            Triple(0.56f, 0.90f, 0.95f),
            0.38f,
        ),
        PlanetVisual(
            SolarSystemBodyId.NEPTUNE,
            ElementSet(
                Coefficient(30.06992276, 0.00026291),
                Coefficient(0.00859048, 0.00005105),
                Coefficient(1.77004347, 0.00035372),
                Coefficient(-55.12002969, 218.45945325),
                Coefficient(44.96476227, -0.32241464),
                Coefficient(131.78422574, -0.00508664),
            ),
            24f,
            Triple(0.42f, 0.64f, 1.00f),
            0.40f,
        ),
    )

    private const val UnixEpochJulianDate = 2_440_587.5
    private const val J2000JulianDate = 2_451_545.0
    private const val MoonEpochJulianDate = 2_451_543.5
    private const val DaysPerJulianCentury = 36_525.0
    private const val MillisPerDay = 86_400_000.0
}
