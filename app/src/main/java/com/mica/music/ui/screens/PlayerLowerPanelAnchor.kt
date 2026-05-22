package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp

internal const val PlayerLowerPanelProgressEpsilon = 0.001f

/** Lower-panel geometry captured before opening lyrics from cover-edge progress mode. */
@Immutable
internal data class PlayerLowerPanelPlaySnapshot(
    val controlsBottomFromPanel: Dp,
    val chromeHeight: Dp,
    val spacing: PlayerLowerPanelSpacing,
    val lyricLineSlots: Int,
)

/** Lower-panel geometry after the expanded lyrics page has settled. */
@Immutable
internal data class PlayerLowerPanelLyricsSnapshot(
    val controlsBottomFromPanel: Dp,
    val chromeHeight: Dp,
)

/**
 * Keeps lower-panel controls anchored to the panel bottom while switching between
 * cover-edge progress and the standard lyrics chrome.
 */
@Immutable
internal class PlayerLowerPanelEdgeAnchorState(
    val active: Boolean,
    val inTransition: Boolean,
    val controlsBottomPadding: Dp,
    val chromeHeight: Dp,
    val playSpacing: PlayerLowerPanelSpacing,
    val playLyricLineSlots: Int,
    val onOpenLyrics: () -> Unit,
    val onControlsBottomMeasured: (bottomFromPanel: Dp, settledOnLyrics: Boolean) -> Unit,
)

@Composable
internal fun rememberPlayerLowerPanelEdgeAnchorState(
    useCoverEdgeProgress: Boolean,
    activeSongId: String,
    lyricsExpanded: Boolean,
    lyricsLayoutFocus: Float,
    lyricsChromeFade: Float,
    layoutChromeHeight: Dp,
    spacing: PlayerLowerPanelSpacing,
): PlayerLowerPanelEdgeAnchorState {
    var playSnapshot by remember { mutableStateOf<PlayerLowerPanelPlaySnapshot?>(null) }
    var lyricsSnapshot by remember { mutableStateOf<PlayerLowerPanelLyricsSnapshot?>(null) }
    var measuredPlayControlsBottom by remember { mutableStateOf<Dp?>(null) }
    LaunchedEffect(activeSongId) {
        playSnapshot = null
        lyricsSnapshot = null
        measuredPlayControlsBottom = null
    }

    val inTransition =
        playSnapshot != null &&
            (lyricsExpanded ||
                lyricsLayoutFocus > PlayerLowerPanelProgressEpsilon ||
                lyricsChromeFade > PlayerLowerPanelProgressEpsilon)

    val playControlsBottom =
        playSnapshot?.controlsBottomFromPanel
            ?: measuredPlayControlsBottom
            ?: spacing.afterControls
    val lyricsControlsBottomFallback = maxOf(
        0.dp,
        playControlsBottom -
            spacing.afterControls * (1f - lyricsChromeBottomInsetScale(1f)),
    )
    // Keep the transition endpoint stable instead of feeding a fresh measurement at focus=1.
    val lyricsControlsBottom =
        lyricsSnapshot?.controlsBottomFromPanel ?: lyricsControlsBottomFallback

    val controlsBottomPadding = when {
        !useCoverEdgeProgress || playSnapshot == null -> spacing.afterControls
        inTransition -> lerpDp(playControlsBottom, lyricsControlsBottom, lyricsLayoutFocus)
        else -> playControlsBottom
    }

    val playChromeHeight = playSnapshot?.chromeHeight ?: layoutChromeHeight
    val lyricsChromeHeight = lyricsSnapshot?.chromeHeight ?: layoutChromeHeight
    val chromeHeight: Dp = when {
        !useCoverEdgeProgress || playSnapshot == null -> layoutChromeHeight
        // Shrink chrome height with focus so the compact lyrics do not jump at the end.
        inTransition -> lerpDp(playChromeHeight, lyricsChromeHeight, lyricsLayoutFocus)
        else -> playChromeHeight
    }
    val playLyricLineSlots = playSnapshot?.lyricLineSlots ?: 3
    val playSpacing = playSnapshot?.spacing ?: spacing

    SideEffect {
        if (lyricsExpanded &&
            lyricsLayoutFocus >= 1f - PlayerLowerPanelProgressEpsilon &&
            lyricsSnapshot == null
        ) {
            lyricsSnapshot = PlayerLowerPanelLyricsSnapshot(
                controlsBottomFromPanel = lyricsControlsBottomFallback,
                chromeHeight = layoutChromeHeight,
            )
        }
    }

    val onOpenLyrics: () -> Unit = {
        playSnapshot = PlayerLowerPanelPlaySnapshot(
            controlsBottomFromPanel = measuredPlayControlsBottom ?: spacing.afterControls,
            chromeHeight = layoutChromeHeight,
            spacing = spacing,
            lyricLineSlots = spacing.lyricLineSlots,
        )
    }

    val onControlsBottomMeasured: (Dp, Boolean) -> Unit = { bottom, settledOnLyrics ->
        if (!settledOnLyrics && !lyricsExpanded && lyricsLayoutFocus <= PlayerLowerPanelProgressEpsilon) {
            measuredPlayControlsBottom = bottom
        }
    }

    return PlayerLowerPanelEdgeAnchorState(
        active = useCoverEdgeProgress && playSnapshot != null,
        inTransition = inTransition,
        controlsBottomPadding = controlsBottomPadding,
        chromeHeight = chromeHeight,
        playSpacing = playSpacing,
        playLyricLineSlots = playLyricLineSlots,
        onOpenLyrics = onOpenLyrics,
        onControlsBottomMeasured = onControlsBottomMeasured,
    )
}
