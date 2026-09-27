package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerCoverFlowMode

internal enum class LandscapePlayerViewport {
    Compact,
    Wide,
    Stage,
}

internal enum class PlayerViewportLayout {
    Vertical,
    Landscape,
}

/**
 * Single playback-page viewport authority. [widthDp]/[heightDp] are safe app bounds in dp
 * (system bars already excluded by Configuration), not physical display pixels.
 */
internal data class PlayerViewportPlan(
    val layout: PlayerViewportLayout,
    val compactVertical: Boolean,
    /** Maximum artwork/card width while the page keeps its vertical semantics. */
    val verticalCoverMaxDp: Float,
) {
    val usesLandscapeLayout: Boolean
        get() = layout == PlayerViewportLayout.Landscape
}

private const val ClassicPortraitLowerReserveDp = 180f
private const val CustomVerticalReserveDp = 48f
private const val ParticleVerticalReserveDp = 240f
private const val PhotoStackVerticalReserveDp = 112f
private const val PhotoStackAspectRatio = 0.78f
private const val ParticleCoverFraction = 0.78f
private const val PhotoStackCoverFraction = 0.80f
private const val MinimumCompactCoverDp = 136f
private const val MinimumParticleCompactCoverDp = 120f
private const val StandardLandscapeMinWidthDp = 360f
private const val CoverFlowLandscapeMinWidthDp = 320f
private const val LandscapeMinHeightDp = 260f

internal fun playerViewportPlan(
    widthDp: Float,
    heightDp: Float,
    mode: PlayerCoverFlowMode,
): PlayerViewportPlan {
    val width = widthDp.coerceAtLeast(0f)
    val height = heightDp.coerceAtLeast(0f)

    val portraitRequiredHeight = when (mode) {
        PlayerCoverFlowMode.PARTICLE_COVER ->
            width * ParticleCoverFraction + ParticleVerticalReserveDp
        PlayerCoverFlowMode.PHOTO_STACK ->
            width * PhotoStackCoverFraction / PhotoStackAspectRatio + PhotoStackVerticalReserveDp
        PlayerCoverFlowMode.STANDARD,
        PlayerCoverFlowMode.PAUSE_FOLD,
        PlayerCoverFlowMode.RETRO_3D,
        -> width + ClassicPortraitLowerReserveDp
        PlayerCoverFlowMode.CUSTOM_STANDARD -> width + CustomVerticalReserveDp
    }
    val portraitFits = width > 0f && height >= portraitRequiredHeight
    val landscapeFits = when (mode) {
        PlayerCoverFlowMode.STANDARD ->
            width >= StandardLandscapeMinWidthDp && height >= LandscapeMinHeightDp
        PlayerCoverFlowMode.PAUSE_FOLD,
        PlayerCoverFlowMode.RETRO_3D,
        -> width >= CoverFlowLandscapeMinWidthDp && height >= LandscapeMinHeightDp
        PlayerCoverFlowMode.CUSTOM_STANDARD,
        PlayerCoverFlowMode.PARTICLE_COVER,
        PlayerCoverFlowMode.PHOTO_STACK,
        -> false
    }
    val useLandscape = !portraitFits && landscapeFits
    val compactVertical = !useLandscape && !portraitFits
    val verticalCoverMax = when (mode) {
        PlayerCoverFlowMode.PARTICLE_COVER -> minOf(
            width * ParticleCoverFraction,
            (height - ParticleVerticalReserveDp).coerceAtLeast(MinimumParticleCompactCoverDp),
        )
        PlayerCoverFlowMode.PHOTO_STACK -> minOf(
            width * PhotoStackCoverFraction,
            (height - PhotoStackVerticalReserveDp)
                .coerceAtLeast(MinimumCompactCoverDp / PhotoStackAspectRatio) *
                PhotoStackAspectRatio,
        )
        PlayerCoverFlowMode.STANDARD,
        PlayerCoverFlowMode.PAUSE_FOLD,
        PlayerCoverFlowMode.RETRO_3D,
        -> minOf(
            width,
            (height - ClassicPortraitLowerReserveDp).coerceAtLeast(MinimumCompactCoverDp),
        )
        PlayerCoverFlowMode.CUSTOM_STANDARD -> minOf(
            width,
            (height - CustomVerticalReserveDp).coerceAtLeast(MinimumCompactCoverDp),
        )
    }.coerceAtLeast(0f)

    return PlayerViewportPlan(
        layout = if (useLandscape) PlayerViewportLayout.Landscape else PlayerViewportLayout.Vertical,
        compactVertical = compactVertical,
        verticalCoverMaxDp = verticalCoverMax,
    )
}

internal data class LandscapePlayerLayoutPlan(
    val viewport: LandscapePlayerViewport,
    val horizontalPaddingDp: Float,
    val columnGapDp: Float,
    val coverLaneWidthDp: Float,
    val detailLaneWidthDp: Float,
    val coverSizeDp: Float,
)

/** Stable page geometry derived from the viewport plan; renderers must not recompute these sizes. */
internal data class LandscapePlayerStableGeometry(
    val edgePaddingDp: Float,
    val playbackCoverSizeDp: Float,
    val lyricsCoverSizeDp: Float,
)

internal fun LandscapePlayerLayoutPlan.stableGeometry(
    widthDp: Float,
    heightDp: Float,
    topPaddingDp: Float,
): LandscapePlayerStableGeometry {
    val edgePadding = maxOf(horizontalPaddingDp, topPaddingDp.coerceAtLeast(0f))
    val heightBound = (heightDp - edgePadding * 2f).coerceAtLeast(0f)
    val widthBound = (
        widthDp - edgePadding * 2f - columnGapDp - 280f
    ).coerceAtLeast(0f)
    return LandscapePlayerStableGeometry(
        edgePaddingDp = edgePadding,
        playbackCoverSizeDp = minOf(heightBound, widthBound),
        lyricsCoverSizeDp = minOf(coverLaneWidthDp, heightDp * 0.50f).coerceAtLeast(0f),
    )
}

/**
 * Pure, testable landscape sizing policy. Dimensions are already inset-adjusted dp values.
 * Returns null for portrait and square windows so the existing portrait page remains authoritative.
 */
internal fun landscapePlayerLayoutPlan(
    widthDp: Float,
    heightDp: Float,
): LandscapePlayerLayoutPlan? {
    if (widthDp <= heightDp || widthDp <= 0f || heightDp <= 0f) return null
    return landscapePlayerLayoutPlanForBounds(widthDp, heightDp)
}

/** Geometry only. Whether landscape is selected is owned by [playerViewportPlan]. */
internal fun landscapePlayerLayoutPlanForBounds(
    widthDp: Float,
    heightDp: Float,
): LandscapePlayerLayoutPlan? {
    if (widthDp <= 0f || heightDp <= 0f) return null

    val viewport = when {
        widthDp >= 1_200f && heightDp >= 600f -> LandscapePlayerViewport.Stage
        widthDp >= 720f && heightDp >= 400f -> LandscapePlayerViewport.Wide
        else -> LandscapePlayerViewport.Compact
    }
    val horizontalPadding = when (viewport) {
        LandscapePlayerViewport.Compact -> 16f
        LandscapePlayerViewport.Wide -> 32f
        LandscapePlayerViewport.Stage -> 48f
    }
    val columnGap = (widthDp * 0.04f).coerceIn(24f, 96f)
    val contentWidth = (widthDp - horizontalPadding * 2f).coerceAtLeast(0f)
    val laneWidth = (contentWidth - columnGap).coerceAtLeast(0f)
    val coverFraction = when (viewport) {
        LandscapePlayerViewport.Compact -> 0.46f
        LandscapePlayerViewport.Wide -> 0.44f
        LandscapePlayerViewport.Stage -> 0.42f
    }
    val coverLaneWidth = laneWidth * coverFraction
    val detailLaneWidth = (laneWidth - coverLaneWidth).coerceAtLeast(0f)
    val verticalSafetyPadding = if (viewport == LandscapePlayerViewport.Compact) 16f else 24f
    val coverSize = minOf(
        coverLaneWidth,
        (heightDp - verticalSafetyPadding * 2f).coerceAtLeast(0f),
    )

    return LandscapePlayerLayoutPlan(
        viewport = viewport,
        horizontalPaddingDp = horizontalPadding,
        columnGapDp = columnGap,
        coverLaneWidthDp = coverLaneWidth,
        detailLaneWidthDp = detailLaneWidth,
        coverSizeDp = coverSize,
    )
}

internal fun landscapeCoverFlowStageCoverSizeDp(
    widthDp: Float,
    heightDp: Float,
    edgePaddingDp: Float,
    mode: PlayerCoverFlowMode,
): Float {
    if (widthDp <= 0f || heightDp <= 0f) return 0f
    val edge = edgePaddingDp.coerceAtLeast(0f)
    val contentHeight = (heightDp - edge).coerceAtLeast(0f)
    val barHeight = if (widthDp < 520f) {
        (contentHeight * 0.38f).coerceIn(132f, 148f)
    } else {
        (contentHeight * 0.22f).coerceIn(72f, 88f)
    }
    val stageHeight = (contentHeight - barHeight - edge).coerceAtLeast(0f)
    val centerScale = CoverFlowMath.centerScale(mode, foldProgress = 1f).coerceAtLeast(0.01f)
    val visibleHeightFactor = centerScale * (1f + CoverFlowMath.ReflectionHeightFraction)
    val heightBound = stageHeight / visibleHeightFactor.coerceAtLeast(0.01f)
    val widthBound = (widthDp - edge * 2f).coerceAtLeast(0f)
    return minOf(widthBound, heightBound).coerceAtLeast(0f)
}

/** Special landscape renderers opt in here as they become production-ready. */
internal fun landscapeFallbackCoverMode(mode: PlayerCoverFlowMode): PlayerCoverFlowMode = when (mode) {
    PlayerCoverFlowMode.STANDARD -> PlayerCoverFlowMode.STANDARD
    PlayerCoverFlowMode.PAUSE_FOLD -> PlayerCoverFlowMode.PAUSE_FOLD
    PlayerCoverFlowMode.RETRO_3D -> PlayerCoverFlowMode.RETRO_3D
    PlayerCoverFlowMode.CUSTOM_STANDARD,
    PlayerCoverFlowMode.PARTICLE_COVER,
    PlayerCoverFlowMode.PHOTO_STACK,
    -> PlayerCoverFlowMode.STANDARD
}

internal fun landscapeCoverModeForPage(
    mode: PlayerCoverFlowMode,
    lyricsExpanded: Boolean,
): PlayerCoverFlowMode = when {
    !lyricsExpanded -> landscapeFallbackCoverMode(mode)
    mode == PlayerCoverFlowMode.PAUSE_FOLD -> PlayerCoverFlowMode.PAUSE_FOLD
    mode == PlayerCoverFlowMode.RETRO_3D -> PlayerCoverFlowMode.RETRO_3D
    else -> PlayerCoverFlowMode.STANDARD
}

internal fun landscapeCoverFlowImmersiveEligible(
    landscapeMode: Boolean,
    mode: PlayerCoverFlowMode,
    lyricsExpanded: Boolean,
): Boolean =
    landscapeMode &&
        !lyricsExpanded &&
        (mode == PlayerCoverFlowMode.PAUSE_FOLD || mode == PlayerCoverFlowMode.RETRO_3D)

internal fun landscapeCoverFlowStageActive(
    landscapeMode: Boolean,
    mode: PlayerCoverFlowMode,
    lyricsCloudRequested: Boolean,
): Boolean =
    landscapeMode &&
        (mode == PlayerCoverFlowMode.PAUSE_FOLD || mode == PlayerCoverFlowMode.RETRO_3D) &&
        !lyricsCloudRequested

/** Landscape cover-flow themes use a dedicated cloud exit instead of STANDARD burst. */
internal fun landscapeCoverFlowCloudExitActive(
    landscapeMode: Boolean,
    mode: PlayerCoverFlowMode,
    lyricsCloudAvailable: Boolean,
): Boolean =
    landscapeMode &&
        lyricsCloudAvailable &&
        (mode == PlayerCoverFlowMode.PAUSE_FOLD || mode == PlayerCoverFlowMode.RETRO_3D)

/**
 * Moving the controls to the landscape bottom edge removes their portrait bottom padding.
 * Remove the same amount from the chrome container or it becomes progress-to-controls whitespace.
 */
internal fun landscapeChromeHeight(
    portraitChromeHeight: Dp,
    portraitControlsBottomPadding: Dp,
): Dp = (portraitChromeHeight - portraitControlsBottomPadding).coerceAtLeast(0.dp)
