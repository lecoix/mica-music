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
        val particleInfoTopPadding = input.statusBarTop + InfoTopExtraPadding + halfExtraGap
        val particleCoverTopPadding = particleInfoTopPadding +
            InfoBlockHeight +
            HifiSpacing.lg +
            CoverDrop +
            halfExtraGap
        val coverSize = input.screenWidth * CoverScreenFraction
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
