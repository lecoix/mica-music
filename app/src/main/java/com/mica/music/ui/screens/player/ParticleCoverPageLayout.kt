package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.mica.music.ui.components.PlayerCoverMaxScreenFraction
import com.mica.music.ui.theme.HifiSpacing

internal object ParticleCoverPageLayout {
    private const val CoverScreenFraction = 0.78f
    private val CoverDrop = 24.dp
    private val InfoBlockHeight = 96.dp
    internal val InfoTopExtraPadding = HifiSpacing.lg

    fun computeCoverFrame(
        input: PlayerPageLayoutInput,
        headerFocus: Float,
        titleToCoverExtraGap: Dp = 0.dp,
    ): CoverFrame {
        val halfExtraGap = titleToCoverExtraGap / 2
        val preferredCoverSize = input.screenWidth * CoverScreenFraction
        val coverSize = input.coverSizeLimit?.let { minOf(preferredCoverSize, it) }
            ?: preferredCoverSize
        val coverFraction = if (preferredCoverSize.value > 0f) {
            (coverSize.value / preferredCoverSize.value).coerceIn(0f, 1f)
        } else {
            1f
        }
        // Short vertical windows keep the particle theme's vertical semantics, but the fixed
        // 96dp info slot / 24dp drop cannot stay rigid or it consumes the lower controls.
        val compactness = coverFraction * coverFraction
        val infoTopExtra = lerpDp(8.dp, InfoTopExtraPadding, compactness)
        val infoBlockHeight = lerpDp(72.dp, InfoBlockHeight, compactness)
        val infoToCoverGap = lerpDp(8.dp, HifiSpacing.lg, compactness)
        val coverDrop = lerpDp(8.dp, CoverDrop, compactness)
        val particleInfoTopPadding = input.statusBarTop + infoTopExtra + halfExtraGap
        val particleCoverTopPadding = particleInfoTopPadding +
            infoBlockHeight +
            infoToCoverGap +
            coverDrop +
            halfExtraGap
        val useParticleLyricsLayout =
            headerFocus > ImmersiveProgressEpsilon &&
                input.queueProgress <= ImmersiveProgressEpsilon &&
                !input.queueExpanded
        val coverWidth = if (useParticleLyricsLayout) {
            coverSize
        } else {
            lerpDp(coverSize, LyricsFocusMiniCoverSize, headerFocus)
        }
        val coverHeight = if (useParticleLyricsLayout) {
            coverSize
        } else {
            lerpDp(coverSize, LyricsFocusMiniCoverSize, headerFocus)
        }
        val coverTopPadding = lerpDp(particleCoverTopPadding, input.statusBarTop, headerFocus)
        val expandedCoverStartPadding = Dp(((input.screenWidth - coverSize).value / 2f).coerceAtLeast(0f))
        val coverStartPadding = if (useParticleLyricsLayout) {
            expandedCoverStartPadding
        } else {
            lerpDp(
                expandedCoverStartPadding,
                LyricsFocusCoverStartPadding,
                headerFocus,
            )
        }
        val coverBlockHeight = lerpDp(
            coverHeight + coverTopPadding + HifiSpacing.lg,
            if (useParticleLyricsLayout) {
                input.statusBarTop
            } else {
                input.statusBarTop + LyricsFocusMiniCoverSize + HifiSpacing.sm
            },
            headerFocus,
        )
        val zoneStop = (coverBlockHeight.value / input.screenHeight.value)
            .coerceIn(0.12f, PlayerCoverMaxScreenFraction)

        return CoverFrame(
            width = coverWidth,
            height = coverHeight,
            startPadding = coverStartPadding,
            topPadding = coverTopPadding,
            blockHeight = coverBlockHeight,
            particleInfoTopPadding = particleInfoTopPadding,
            letterboxAlpha = 0f,
            zoneStop = zoneStop,
        )
    }

    fun computeParticleFrame(
        input: PlayerPageLayoutInput,
        headerFocus: Float,
    ): ParticleCoverFrame {
        val enabled = input.particleCoverMode
        val queueOpen =
            input.queueExpanded || input.queueProgress > ImmersiveProgressEpsilon
        val lyricsBackgroundVisible = enabled &&
            !queueOpen &&
            (input.lyricsExpanded || headerFocus > ImmersiveProgressEpsilon)
        return ParticleCoverFrame(
            enabled = enabled,
            normalLayerVisible = enabled && !lyricsBackgroundVisible,
            lyricsBackgroundVisible = lyricsBackgroundVisible,
            hostBaseSize = input.screenWidth,
        )
    }

    fun compactContentAlpha(
        headerFocus: Float,
        metaAlpha: Float,
    ): Float =
        if (headerFocus > ImmersiveProgressEpsilon) 0f else metaAlpha
}
