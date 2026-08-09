package com.mica.music.ui.screens.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.PlayerCoverFlowMode

internal enum class LandscapePlayerViewport {
    Compact,
    Wide,
    Stage,
}

internal data class LandscapePlayerLayoutPlan(
    val viewport: LandscapePlayerViewport,
    val horizontalPaddingDp: Float,
    val columnGapDp: Float,
    val coverLaneWidthDp: Float,
    val detailLaneWidthDp: Float,
    val coverSizeDp: Float,
)

/**
 * Pure, testable landscape sizing policy. Dimensions are already inset-adjusted dp values.
 * Returns null for portrait and square windows so the existing portrait page remains authoritative.
 */
internal fun landscapePlayerLayoutPlan(
    widthDp: Float,
    heightDp: Float,
): LandscapePlayerLayoutPlan? {
    if (widthDp <= heightDp || widthDp <= 0f || heightDp <= 0f) return null

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
