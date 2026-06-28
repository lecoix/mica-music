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
        lyricsFocus: Float,
    ): CoverFrame {
        val particleInfoTopPadding = input.statusBarTop + InfoTopExtraPadding
        val particleCoverTopPadding = particleInfoTopPadding +
            InfoBlockHeight +
            HifiSpacing.lg +
            CoverDrop
        val coverSize = input.screenWidth * CoverScreenFraction
        val useLyricsLayout = lyricsFocus > ImmersiveProgressEpsilon
        val coverWidth = if (useLyricsLayout) {
            coverSize
        } else {
            lerpDp(coverSize, LyricsFocusMiniCoverSize, lyricsFocus)
        }
        val coverHeight = if (useLyricsLayout) {
            coverSize
        } else {
            lerpDp(coverSize, LyricsFocusMiniCoverSize, lyricsFocus)
        }
        val coverTopPadding = lerpDp(particleCoverTopPadding, input.statusBarTop, lyricsFocus)
        val expandedCoverStartPadding = Dp(((input.screenWidth - coverSize).value / 2f).coerceAtLeast(0f))
        val coverStartPadding = if (useLyricsLayout) {
            expandedCoverStartPadding
        } else {
            lerpDp(
                expandedCoverStartPadding,
                LyricsFocusCoverStartPadding,
                lyricsFocus,
            )
        }
        val coverBlockHeight = lerpDp(
            coverHeight + coverTopPadding + HifiSpacing.lg,
            input.statusBarTop,
            lyricsFocus,
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
        lyricsFocus: Float,
    ): ParticleCoverFrame {
        val enabled = input.particleCoverMode
        return ParticleCoverFrame(
            enabled = enabled,
            normalLayerVisible = enabled &&
                !input.lyricsExpanded &&
                lyricsFocus <= ImmersiveProgressEpsilon,
            lyricsBackgroundVisible = enabled &&
                (input.lyricsExpanded || lyricsFocus > ImmersiveProgressEpsilon),
            hostBaseSize = input.screenWidth,
        )
    }

    fun compactContentAlpha(
        lyricsFocus: Float,
        metaAlpha: Float,
    ): Float =
        if (lyricsFocus > ImmersiveProgressEpsilon) 0f else metaAlpha
}
