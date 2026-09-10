package com.mica.music.ui.components

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType

internal data class QualityBadgeVector(
    val width: Float,
    val height: Float,
    val path: Path,
)

/**
 * HR / SQ / HQ traced from the first generated badge sheet.
 * Coordinates are the simplified alpha contours from that source image; the matching SVG sources
 * live under app/src/main/assets/quality_badges/.
 */
internal fun tracedQualityBadgeVector(label: String): QualityBadgeVector? = when (label) {
    "HR" -> QualityBadgeVector(505f, 353f, Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(1f, 6f)
        lineTo(1f, 300f)
        lineTo(66f, 300f)
        lineTo(67f, 188f)
        lineTo(169f, 188f)
        lineTo(171f, 192f)
        lineTo(171f, 300f)
        lineTo(237f, 300f)
        lineTo(237f, 6f)
        lineTo(171f, 6f)
        lineTo(171f, 126f)
        lineTo(169f, 129f)
        lineTo(152f, 130f)
        lineTo(67f, 129f)
        lineTo(66f, 6f)
        close()

        moveTo(265f, 2f)
        lineTo(265f, 300f)
        lineTo(330f, 301f)
        lineTo(330f, 182f)
        lineTo(332f, 181f)
        lineTo(438f, 301f)
        lineTo(501f, 301f)
        lineTo(501f, 264f)
        lineTo(418f, 173f)
        lineTo(420f, 171f)
        lineTo(503f, 171f)
        lineTo(503f, 2f)
        close()

        moveTo(328f, 68f)
        lineTo(339f, 66f)
        lineTo(435f, 67f)
        lineTo(436f, 114f)
        lineTo(434f, 116f)
        lineTo(329f, 115f)
        close()
    })

    "SQ" -> QualityBadgeVector(525f, 353f, Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(275f, 1f)
        lineTo(275f, 300f)
        lineTo(404f, 301f)
        lineTo(430f, 351f)
        lineTo(511f, 351f)
        lineTo(479f, 281f)
        lineTo(523f, 279f)
        lineTo(523f, 1f)
        close()

        moveTo(344f, 66f)
        lineTo(447f, 66f)
        lineTo(453f, 68f)
        lineTo(453f, 231f)
        lineTo(451f, 233f)
        lineTo(348f, 234f)
        lineTo(342f, 231f)
        lineTo(342f, 113f)
        close()

        moveTo(1f, 1f)
        lineTo(1f, 181f)
        lineTo(175f, 182f)
        lineTo(174f, 234f)
        lineTo(1f, 234f)
        lineTo(1f, 300f)
        lineTo(243f, 301f)
        lineTo(244f, 120f)
        lineTo(69f, 120f)
        lineTo(67f, 118f)
        lineTo(67f, 68f)
        lineTo(85f, 66f)
        lineTo(243f, 67f)
        lineTo(245f, 66f)
        lineTo(245f, 1f)
        close()
    })

    "HQ" -> QualityBadgeVector(513f, 353f, Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(1f, 3f)
        lineTo(1f, 300f)
        lineTo(68f, 300f)
        lineTo(68f, 189f)
        lineTo(70f, 187f)
        lineTo(172f, 188f)
        lineTo(174f, 190f)
        lineTo(174f, 300f)
        lineTo(242f, 300f)
        lineTo(242f, 3f)
        lineTo(174f, 3f)
        lineTo(174f, 126f)
        lineTo(172f, 128f)
        lineTo(70f, 128f)
        lineTo(68f, 126f)
        lineTo(68f, 3f)
        close()

        moveTo(272f, 1f)
        lineTo(271f, 299f)
        lineTo(394f, 300f)
        lineTo(419f, 350f)
        lineTo(498f, 350f)
        lineTo(468f, 280f)
        lineTo(511f, 279f)
        lineTo(511f, 1f)
        close()

        moveTo(336f, 66f)
        lineTo(441f, 65f)
        lineTo(443f, 67f)
        lineTo(443f, 228f)
        lineTo(441f, 233f)
        lineTo(337f, 233f)
        lineTo(335f, 231f)
        close()
    })

    else -> null
}
